package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceStorageMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceLinkMapper;
import com.sanhua.marketingcost.service.pricing.QuotationPreviewReads;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PricingType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One immutable price row contains its identity, source, supplier and first-use evidence. */
@Repository
public class MonthlyPriceStorage {
  public record StoredPrice(long id,MonthlyPriceFamilyKey family,MonthlyPriceApplicability applicability,
      PricingType pricingType,BigDecimal unitPrice,BigDecimal householdRate,SourceEvidence evidence,
      Long householdSourceId,String firstUseJson) {}
  private final MonthlyPriceStorageMapper mapper;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPriceLinkMapper links;
  private record ParsedEvidence(SourceEvidence evidence, String hash) {}
  // Cache parsing only, never a database lookup or price/applicability decision.
  private final Cache<String, ParsedEvidence> parsedEvidence = Caffeine.newBuilder()
          .maximumWeight(16L * 1024 * 1024)
          .weigher((String encoded, ParsedEvidence value) -> (int) Math.min(Integer.MAX_VALUE,
              128L + 2L * encoded.length() + 2L * value.evidence().json().length()))
          .expireAfterAccess(Duration.ofMinutes(5)).build();

  public MonthlyPriceStorage(MonthlyPriceStorageMapper mapper,MonthlyPriceDocumentCodec codec,MonthlyPriceLinkMapper links) {
    this.mapper=mapper;this.codec=codec;this.links=links;
  }

  /** A concurrent import must not publish an old highest-price plan after the actual ratios. */
  public void lockAndCheckSupplierRatios(MonthlyMaterialPrices.Context context, MonthlyAdoptionPlan planned) {
    requireAdoptionTransaction();
    var materials = new TreeSet<String>();
    var expected = new HashSet<Long>();
    planned.selections().forEach((request, selection) -> {
      if (selection.monthlyPriceId() != null) return;
      var source = codec.decode(codec.wrap(selection.fact().evidence().json()), JsonNode.class).path("source");
      if (!source.hasNonNull("selectionRule") || !source.path("ratios").isArray()) return;
      materials.add(request.family().materialCode());
      source.path("ratios").forEach(ratio -> expected.add(ratio.path("id").asLong()));
    });
    var current = new HashSet<Long>();
    var codes = new ArrayList<>(materials);
    for (int start=0; start<codes.size(); start+=256)
      current.addAll(mapper.lockSupplierRatioVersions(context.businessUnitType(), context.priceAsOfTime().toLocalDate(),
          codes.subList(start, Math.min(start+256, codes.size()))));
    if (!current.equals(expected))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
          "供货比率在取价期间已更新，请按实际比率重新取价");
  }

  /** Publication takes the same catalog lock exclusively, before touching monthly selections. */
  public void lockAndCheckFinance(MonthlyAdoptionPlan planned) {
    requireAdoptionTransaction();
    var settlementIds=new TreeMap<String,Set<Long>>();
    var newSettlements=new LinkedHashMap<MonthlyPriceFamilyKey,String>();
    var invoiceImports=new TreeMap<String,Set<Long>>();
    var rangeImports=new TreeMap<String,Set<Long>>();
    var scrapImports=new TreeMap<String,Set<String>>();
    var financeUnits=new TreeSet<String>();
    var references=new LinkedHashMap<MonthlyPriceRequest,Set<MonthlyFinanceReferences.Reference>>();
    planned.selections().forEach((request,selection)->{
      var evidence=codec.decode(codec.wrap(selection.fact().evidence().json()),JsonNode.class);
      var refs=MonthlyFinanceReferences.read(evidence);
      if(Set.of("SETTLE_FIXED","SETTLE_LINKED").contains(evidence.path("sourceKind").asText())) {
        long id=evidence.path("source").path("rule").path("id").asLong();
        if(id>0 && selection.monthlyPriceId()==null) {
          settlementIds.computeIfAbsent(request.family().businessUnitType(),ignored->new TreeSet<>()).add(id);
          newSettlements.put(request.family(),evidence.path("sourceKind").asText());
        }
      }
      if(!refs.isEmpty()) {
        references.put(request,refs);
        financeUnits.add(request.family().businessUnitType());
      }
      if(selection.monthlyPriceId()==null) {
        String unit=request.family().businessUnitType();
        switch(evidence.path("sourceKind").asText()) {
          case "INVOICE" -> invoiceImports.computeIfAbsent(unit,ignored->new TreeSet<>()).addAll(PriceImportReferences.invoice(evidence));
          case "RANGE_FACTOR" -> {
            financeUnits.add(unit);
            rangeImports.computeIfAbsent(unit,ignored->new TreeSet<>()).addAll(PriceImportReferences.range(evidence));
          }
          case "SCRAP_PRICE" -> {
            financeUnits.add(unit);
            scrapImports.computeIfAbsent(unit,ignored->new TreeSet<>()).addAll(PriceImportReferences.scrap(evidence));
          }
          default -> {}
        }
      }
    });
    financeUnits.forEach(mapper::lockFinancePublication);
    invoiceImports.forEach((unit,ids)->ids.forEach(id->requireActiveImport(mapper.lockInvoiceImport(unit,id))));
    rangeImports.forEach((unit,ids)->ids.forEach(id->requireActiveImport(mapper.lockRangeImport(unit,id))));
    scrapImports.forEach((unit,ids)->ids.forEach(id->requireActiveImport(mapper.lockScrapImport(unit,id))));
    planned.selections().entrySet().stream()
        .filter(entry->entry.getValue().monthlyPriceId()==null)
        .sorted(java.util.Comparator.comparing(entry->entry.getKey().family().canonical()))
        .forEach(entry->lockSelectedSources(entry.getKey().family().businessUnitType(),
            codec.decode(codec.wrap(entry.getValue().fact().evidence().json()),JsonNode.class)));
    settlementIds.keySet().forEach(mapper::lockSettlementPublication);
    settlementIds.forEach((unit,ids)->{
      for(long id:ids)if(mapper.lockSettlementSource(unit,id)==null)
        throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"结算原稿已失效，请重新取价");
    });
    newSettlements.forEach((family,type)->{
      if(Boolean.TRUE.equals(mapper.latestSettlementVoided(family.businessUnitType(),family.materialCode(),type,family.month().toString())))
        throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"适用结算原稿已失效，请重新取价");
    });
    planned.selections().entrySet().stream().filter(entry->entry.getValue().fact().type()==PricingType.HOUSEHOLD_INQUIRY)
        .sorted(java.util.Comparator.comparing(entry->entry.getKey().family().canonical())).forEach(entry->{
          var family=entry.getKey().family();
          var source=codec.decode(codec.wrap(entry.getValue().fact().evidence().json()),JsonNode.class);
          var original=source.path("source").path("evidence").path("source");
          String material=original.path("materialCode").isTextual()?original.path("materialCode").asText():null;
          String matchKey=com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryKey.of(material,original.path("specification").asText(null));
          for(String key:new TreeSet<>(List.of("C:"+family.materialCode(),matchKey)))mapper.lockHouseholdCurrent(family.businessUnitType(),key);
          String exactKey="C:"+family.materialCode();
          Long latest=mapper.currentHouseholdSource(family.businessUnitType(),family.month().toString(),exactKey);
          if(latest==null && !exactKey.equals(matchKey))
            latest=mapper.currentHouseholdSource(family.businessUnitType(),family.month().toString(),matchKey);
          if(!Objects.equals(latest,source.path("householdSourceId").asLong()))
            throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"适用家用询价在采用前已更新，请重新取价");
        });
    var current=new HashMap<String,Long>();
    references.forEach((request,refs)->{
      if(planned.selections().get(request).monthlyPriceId()!=null)return;
      for(var ref:refs) {
        var family=request.family();String key=family.businessUnitType()+":"+family.month()+":"+ref.organization();
        Long batch=current.computeIfAbsent(key,ignored->mapper.currentFinanceBatch(family.businessUnitType(),ref.organization(),family.month().toString()));
        if(!Objects.equals(batch,ref.batchId()))
          throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"财务基价在采用前已更新，请重新取价");
      }
    });
  }

  private static void requireActiveImport(Long id) {
    if(id==null)throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,
        "导入来源已失效，请重新取价");
  }

  private void lockSelectedSources(String unit,JsonNode evidence) {
    switch(evidence.path("sourceKind").asText()) {
      case "INQUIRY" -> PriceImportReferences.inquiryRows(evidence)
          .forEach(id->requireActiveImport(mapper.lockInquiryRow(unit,id)));
      case "LINKED","LINKED_LEGACY" -> PriceImportReferences.linkedRows(evidence)
          .forEach(id->requireActiveImport(mapper.lockLinkedRow(unit,id)));
      case "INVOICE" -> {
        for(var row:PriceImportReferences.invoiceRows(evidence)) {
          long id=row.path("id").asLong(),batch=row.path("batch_id").asLong();
          if(id>0 && batch>0)requireActiveImport(mapper.lockInvoiceRow(unit,id,batch));
        }
      }
      case "SCRAP_PRICE" -> {
        for(var row:PriceImportReferences.scrapRows(evidence)) {
          long id=row.path("id").asLong();
          int revision=row.path("ruleRevision").asInt();
          if(id>0 && revision>0)requireActiveImport(mapper.lockScrapRow(unit,id,revision));
        }
      }
      case "RANGE_FACTOR","RANGE_LEGACY_FACTOR","RANGE_QTY" -> {
        var ids=new TreeSet<Long>();
        long selected=PriceImportReferences.selectedRangeRow(evidence);
        if(selected>0)ids.add(selected);
        if("RANGE_FACTOR".equals(evidence.path("sourceKind").asText()))
          for(var band:evidence.path("source").path("bands"))if(band.path("id").asLong()>0)ids.add(band.path("id").asLong());
        for(long id:ids)requireActiveImport(mapper.lockRangeRow(unit,id));
      }
      default -> {}
    }
  }

  /** REPEATABLE_READ next-key locks cover existing rows and absent-key gaps, without lock rows. */
  public void lockFamilies(Collection<MonthlyPriceFamilyKey> families) {
    lockFamilies(families, false);
  }

  /** A reuse-only plan needs a stable current read of each adopted price until commit. */
  void lockFamilies(Collection<MonthlyPriceFamilyKey> families, boolean reuseOnly) {
    requireAdoptionTransaction();
    var byHash=new TreeMap<String,MonthlyPriceFamilyKey>();
    for(var key:families) {
      String hash=MonthlyPriceDocumentCodec.sha256(key.canonical());
      var previous=byHash.putIfAbsent(hash,key);
      if(previous!=null && !previous.equals(key))throw new IllegalStateException("Monthly identity hash conflict");
    }
    var hashes=new ArrayList<>(byHash.keySet());
    for(int start=0;start<hashes.size();start+=128) {
      var chunk=hashes.subList(start,Math.min(start+128,hashes.size()));
      for(var row:mapper.lockFamilies(chunk, reuseOnly)) {
        if(!read(row).family().equals(byHash.get(row.getFamilyHash())))
          throw new IllegalStateException("Monthly identity hash conflict");
      }
    }
  }

  public StoredPrice insertPrice(MonthlyPriceFamilyKey key,MonthlyPriceApplicability applicability,
      PriceFact fact,Long householdSourceId,String firstUseJson,String actor) {
    requireAdoptionTransaction();Objects.requireNonNull(applicability);Objects.requireNonNull(fact);
    SourceEvidence.ofJson(firstUseJson);
    if(actor==null || actor.isBlank())throw new IllegalArgumentException("Adoption actor is required");
    var source=codec.decode(codec.wrap(fact.evidence().json()),JsonNode.class);
    long revision=mapper.invoiceRevisions(key.businessUnitType(),List.of(key.materialCode())).stream()
        .filter(value->applicability.fragments().stream().anyMatch(fragment->fragment.organizations().contains(value.orgCode())))
        .mapToLong(MonthlyPriceStorageMapper.InvoiceRevision::revision).max().orElse(0);
    if(Set.of("INVOICE","ODS_ORDER","INQUIRY","TECH_FIXED").contains(source.path("sourceKind").asText())
        && revision!=source.path("invoiceRevision").asLong(0))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"发票来源在采用前已更新，请重新取价");
    String hash=MonthlyPriceDocumentCodec.sha256(key.canonical());
    for(var existing:mapper.lockFamily(hash)) {
      var saved=read(existing);
      if(!saved.family().equals(key))throw new IllegalStateException("Monthly identity hash conflict");
      if(!Objects.equals(applicability.specialPolicyRevision(),saved.applicability().specialPolicyRevision()))continue;
      for(var left:applicability.fragments())for(var right:saved.applicability().fragments()) {
        // A current locking read can see a winner committed after this transaction's snapshot.
        // Reject the obsolete candidate and let the existing bounded executor rebuild the whole plan.
        if(left.intersection(right).isPresent())throw new MonthlyPriceProblem(
            MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"Monthly price applicability is already adopted; replan from the committed winner");
      }
    }
    var row=new MonthlyMaterialPriceRow();
    // Even a newly available higher source needs a new identity after an old order was superseded.
    // Its typed evidence stays unchanged, and later invoice imports never invalidate that higher source.
    row.setInvoiceRevision(revision);
    row.setFinanceRevision(mapper.financeRevision(hash));
    row.setHouseholdRevision(householdSourceId==null?0L:householdSourceId);
    row.setFamilyHash(hash);row.setPricingMonth(key.month().toString());row.setBusinessUnitType(key.businessUnitType());
    row.setMaterialCode(key.materialCode());row.setSpecification(key.specification());row.setPricingUnit(key.unit());
    row.setCurrency(key.currency());row.setTaxBasis(key.taxBasis().name());row.setRequestedSupplierCode(key.requestedSupplierCode());
    row.setApplicabilityHash(MonthlyPriceDocumentCodec.sha256(applicability.canonical()));
    row.setApplicabilityJson(codec.encode(applicability));row.setOrganizationMask(organizationMask(applicability));
    row.setPricingType(fact.type().name());row.setUnitPrice(fact.price());row.setHouseholdRate(fact.householdRate());
    row.setSourceEvidenceHash(fact.evidence().sha256());row.setSourceEvidenceJson(codec.wrap(fact.evidence().json()));
    sourceSummary(row,fact.evidence());
    row.setHouseholdSourceId(householdSourceId);row.setFirstUseJson(codec.wrap(firstUseJson));row.setCreatedBy(actor);
    mapper.insertPrice(row);
    return readByIds(key.businessUnitType(),Set.of(row.getId())).getFirst();
  }

  /** Display columns are taken from the same immutable source snapshot used for the price. */
  private void sourceSummary(MonthlyMaterialPriceRow row,SourceEvidence evidence) {
    var root=codec.decode(codec.wrap(evidence.json()),JsonNode.class);
    String type=root.path("sourceKind").asText();
    if(type.isBlank())throw new IllegalArgumentException("Monthly price requires its explicit sourceKind");
    var source=root.path("source");
    JsonNode selected=switch(type) {
      case "RANGE_LEGACY_FACTOR" -> source.path("selectedRule");
      case "HOUSEHOLD_INQUIRY" -> source;
      case "SETTLE_FIXED", "SETTLE_LINKED" -> source.path("rule");
      default -> source.path("selected");
    };
    var id=type.equals("HOUSEHOLD_INQUIRY")?selected.path("versionId"):selected.path("id");
    row.setSourceType(type);row.setSourceRecordId(id.isIntegralNumber()&&id.canConvertToLong()?id.longValue():null);
    row.setSupplierCode(nullableText(selected,"supplierCode"));row.setSupplierName(nullableText(selected,"supplierName"));
  }
  private static String nullableText(JsonNode node,String key) {
    var value=node.get(key);return value==null || value.isNull()?null:value.asText();
  }

  public List<StoredPrice> readByIds(String unit,Collection<Long> ids) {
    requireBusinessUnit(unit);var unique=new ArrayList<>(new LinkedHashSet<>(ids));var result=new ArrayList<StoredPrice>();
    for(int i=0;i<unique.size();i+=256) {
      var chunk=List.copyOf(unique.subList(i,Math.min(i+256,unique.size())));
      result.addAll(QuotationPreviewReads.read(this,List.of("ids",unit,chunk),
          ()->mapper.pricesByIds(unit,chunk).stream().map(this::read).toList()));
    }
    return List.copyOf(result);
  }
  public List<StoredPrice> readFamilies(Collection<MonthlyPriceFamilyKey> families) {
    var groups=new LinkedHashMap<String,Set<String>>();
    for(var family:families)groups.computeIfAbsent(family.businessUnitType(),ignored->new LinkedHashSet<>())
        .add(MonthlyPriceDocumentCodec.sha256(family.canonical()));
    var result=new ArrayList<StoredPrice>();
    groups.forEach((unit,hashes)->{
      var unique=new ArrayList<>(hashes);
      for(int i=0;i<unique.size();i+=256) {
        var chunk=List.copyOf(unique.subList(i,Math.min(i+256,unique.size())));
        result.addAll(QuotationPreviewReads.read(this,List.of("families",unit,chunk),
            ()->mapper.pricesByFamilies(unit,chunk).stream().map(this::read).toList()));
      }
    });
    return List.copyOf(result);
  }
  public List<StoredPrice> previousSupplemental(String unit,YearMonth month,Collection<String> codes) {
    requireBusinessUnit(unit);
    var unique=new ArrayList<>(new LinkedHashSet<>(codes));var result=new ArrayList<StoredPrice>();
    for(int start=0;start<unique.size();start+=256)
      mapper.previousSupplemental(unit,month.toString(),unique.subList(start,Math.min(start+256,unique.size())))
          .forEach(row->result.add(read(row)));
    return List.copyOf(result);
  }

  public Map<MonthlyPriceRequest,Long> invoiceRevisions(Collection<MonthlyPriceRequest> requests) {
    var result=new LinkedHashMap<MonthlyPriceRequest,Long>();
    var groups=new LinkedHashMap<String,Set<String>>();
    requests.forEach(request->{var key=request.family();result.put(request,0L);groups.computeIfAbsent(key.businessUnitType(),ignored->new LinkedHashSet<>()).add(key.materialCode());});
    groups.forEach((unit,codes)->{
      var list=new ArrayList<>(codes);var versions=new HashMap<String,Long>();
      for(int start=0;start<list.size();start+=256)mapper.invoiceRevisions(unit,list.subList(start,Math.min(start+256,list.size())))
          .forEach(row->versions.put(row.materialCode()+"\u0000"+row.orgCode(),row.revision()));
      result.replaceAll((request,value)->request.family().businessUnitType().equals(unit)?versions.getOrDefault(request.family().materialCode()+"\u0000"+request.priceOrgCode(),0L):value);
    });
    return result;
  }
  private StoredPrice read(MonthlyMaterialPriceRow row) {
    var firstUse=codec.decode(row.getFirstUseJson(),JsonNode.class);
    var key=new MonthlyPriceFamilyKey(YearMonth.parse(row.getPricingMonth()),row.getBusinessUnitType(),row.getMaterialCode(),
        row.getSpecification(),row.getPricingUnit(),row.getCurrency(),TaxBasis.valueOf(row.getTaxBasis()),row.getRequestedSupplierCode(),firstUse.path("specificationOnly").asBoolean(false));
    var applicability=codec.decode(row.getApplicabilityJson(),MonthlyPriceApplicability.class);
    var parsed=parsedEvidence.get(row.getSourceEvidenceJson(), encoded -> {
      var evidence=SourceEvidence.ofJson(codec.unwrap(encoded));
      return new ParsedEvidence(evidence,evidence.sha256());
    });
    var evidence=parsed.evidence();
    if(!MonthlyPriceDocumentCodec.sha256(key.canonical()).equals(row.getFamilyHash())
        || !MonthlyPriceDocumentCodec.sha256(applicability.canonical()).equals(row.getApplicabilityHash())
        || !parsed.hash().equals(row.getSourceEvidenceHash())
        || !Objects.equals(row.getOrganizationMask(),organizationMask(applicability)))
      throw new IllegalStateException("Stored monthly price identity/evidence conflict");
    var type=PricingType.valueOf(row.getPricingType());
    new PriceFact(type,row.getUnitPrice(),row.getHouseholdRate(),evidence,Set.of());
    return new StoredPrice(row.getId(),key,applicability,type,row.getUnitPrice(),row.getHouseholdRate(),evidence,
        row.getHouseholdSourceId(),codec.unwrap(row.getFirstUseJson()));
  }
  private int organizationMask(MonthlyPriceApplicability applicability) {
    int mask=0;for(var fragment:applicability.fragments()) {
      if(fragment.organizations().contains("210"))mask|=1;
      if(fragment.organizations().contains("220"))mask|=2;
    }return mask;
  }

  public record PreparationReference(long itemId, MonthlyPriceRequest request,
      MonthlyAdoptionPlan.Selection selection) {
    public PreparationReference {
      Objects.requireNonNull(request); Objects.requireNonNull(selection);
      if (itemId <= 0 || selection.monthlyPriceId() == null || selection.monthlyPriceId() <= 0)
        throw new IllegalArgumentException("Preparation reference requires saved item and monthly price identities");
    }
  }

  /** Attach the validated price set without changing the caller's BOM or calculation order. */
  public void attachPreparations(String unit, String repriceNo, List<PreparationReference> references) {
    requireWriteTransaction(); requireBusinessUnit(unit);
    record OrderedReference(String key, PreparationReference reference) {}
    // Foreign-key checks hold shared price locks until customer commit. Match adoption's
    // family/applicability index order so another adopter cannot hold our next price.
    var ordered = references.stream().map(reference -> {
      if (!unit.equals(reference.request().family().businessUnitType()))
        throw new IllegalArgumentException("Preparation reference belongs to a different business unit");
      String key = MonthlyPriceDocumentCodec.sha256(reference.request().family().canonical())
          + MonthlyPriceDocumentCodec.sha256(reference.selection().applicability().canonical());
      return new OrderedReference(key, reference);
    }).sorted(Comparator.comparing(OrderedReference::key)).toList();
    for (var entry : ordered) {
      var reference = entry.reference();
      var selection = reference.selection();
      if (repriceNo == null) attachPreparation(unit, reference.itemId(), selection.monthlyPriceId(), selection.fact().type());
      else attachRepricePreparation(unit, repriceNo, reference.itemId(), selection.monthlyPriceId(), selection.fact().type());
    }
  }

  public void attachPreparation(String unit,long id,long priceId,PricingType type) {
    requireWriteTransaction(); requireBusinessUnit(unit); Objects.requireNonNull(type);
    var current=links.preparation(unit,id);
    if (sameLink(current,priceId,type)) return;
    if (current==null || current.monthlyPriceId()!=null || links.attachPreparation(unit,id,priceId,type.name())!=1) {
      throw new IllegalStateException("Preparation monthly price link conflict");
    }
  }

  public void attachRepricePreparation(String unit,String no,long id,long priceId,PricingType type) {
    requireWriteTransaction(); requireBusinessUnit(unit); Objects.requireNonNull(type);
    var current=links.preparation(unit,id);
    if (sameLink(current,priceId,type)) return;
    if (current==null || current.monthlyPriceId()!=null
        || links.attachRepricePreparation(unit,no,id,priceId,type.name())!=1)
      throw new IllegalStateException("Reprice preparation monthly price link conflict");
  }

  private boolean sameLink(MonthlyPriceLinkMapper.PriceLink current,long id,PricingType type) {
    return current!=null && Objects.equals(current.monthlyPriceId(),id) && type.name().equals(current.monthlyPricingType());
  }

  public MonthlyPriceLinkMapper.PriceLink preparationLink(String unit,long id) {
    requireBusinessUnit(unit); return links.preparation(unit,id);
  }

  public MonthlyPriceLinkMapper.PriceLink costPartLink(String unit,long id) {
    requireBusinessUnit(unit); return links.costPart(unit,id);
  }

  public void saveVersionSnapshot(String unit,long id,String policy,int schema,String snapshot) {
    requireWriteTransaction(); requireBusinessUnit(unit); codec.validateObject(snapshot);
    if (schema!=1 || policy==null || policy.isBlank() || policy.length()>64) throw new IllegalArgumentException("Explicit snapshot schema/policy required");
    if (links.saveSnapshot(unit,id,policy,schema,codec.wrap(snapshot))!=1) throw new IllegalStateException("Successful/history version snapshots are immutable");
  }

  public MonthlyPriceLinkMapper.VersionSnapshot versionSnapshot(String unit,long id) {
    requireBusinessUnit(unit); var row=links.snapshot(unit,id);
    if (row==null || row.jianjiSnapshotJson()==null) return row;
    String snapshot=codec.unwrap(row.jianjiSnapshotJson()); codec.validateObject(snapshot);
    return new MonthlyPriceLinkMapper.VersionSnapshot(row.id(),row.feePolicyVersion(),row.jianjiSnapshotVersion(),snapshot);
  }

  static void requireWriteTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Monthly price writes require a writable transaction");
    }
  }

  private static void requireAdoptionTransaction() {
    requireWriteTransaction();
    if (!Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
        .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Monthly price writes require explicit REPEATABLE_READ isolation");
    }
  }

  static void requireBusinessUnit(String value) {
    if (!"COMMERCIAL".equals(value) && !"HOUSEHOLD".equals(value)) throw new IllegalArgumentException("Explicit business unit required");
  }
}
