package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceStorageMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceLinkMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PricingType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
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

  public MonthlyPriceStorage(MonthlyPriceStorageMapper mapper,MonthlyPriceDocumentCodec codec,MonthlyPriceLinkMapper links) {
    this.mapper=mapper;this.codec=codec;this.links=links;
  }

  /** REPEATABLE_READ next-key locks cover existing rows and absent-key gaps, without lock rows. */
  public void lockFamilies(Collection<MonthlyPriceFamilyKey> families) {
    requireAdoptionTransaction();
    for(var key:new TreeSet<>(families)) {
      for(var row:mapper.lockFamily(MonthlyPriceDocumentCodec.sha256(key.canonical()))) {
        if(!read(row).family().equals(key))throw new IllegalStateException("Monthly identity hash conflict");
      }
    }
  }

  public StoredPrice insertPrice(MonthlyPriceFamilyKey key,MonthlyPriceApplicability applicability,
      PriceFact fact,Long householdSourceId,String firstUseJson,String actor) {
    requireAdoptionTransaction();Objects.requireNonNull(applicability);Objects.requireNonNull(fact);
    SourceEvidence.ofJson(firstUseJson);
    if(actor==null || actor.isBlank())throw new IllegalArgumentException("Adoption actor is required");
    String hash=MonthlyPriceDocumentCodec.sha256(key.canonical());
    for(var existing:mapper.lockFamily(hash)) {
      var saved=read(existing);
      if(!saved.family().equals(key))throw new IllegalStateException("Monthly identity hash conflict");
      for(var left:applicability.fragments())for(var right:saved.applicability().fragments()) {
        if(left.intersection(right).isPresent())throw new IllegalStateException("Monthly price applicability is already adopted");
      }
    }
    var row=new MonthlyMaterialPriceRow();
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
    for(int i=0;i<unique.size();i+=256)mapper.pricesByIds(unit,unique.subList(i,Math.min(i+256,unique.size())))
        .forEach(row->result.add(read(row)));
    return List.copyOf(result);
  }
  public List<StoredPrice> readFamilies(Collection<MonthlyPriceFamilyKey> families) {
    var groups=new LinkedHashMap<String,Set<String>>();
    for(var family:families)groups.computeIfAbsent(family.businessUnitType(),ignored->new LinkedHashSet<>())
        .add(MonthlyPriceDocumentCodec.sha256(family.canonical()));
    var result=new ArrayList<StoredPrice>();
    groups.forEach((unit,hashes)->{
      var unique=new ArrayList<>(hashes);
      for(int i=0;i<unique.size();i+=256)mapper.pricesByFamilies(unit,unique.subList(i,Math.min(i+256,unique.size())))
          .forEach(row->result.add(read(row)));
    });
    return List.copyOf(result);
  }
  private StoredPrice read(MonthlyMaterialPriceRow row) {
    var key=new MonthlyPriceFamilyKey(YearMonth.parse(row.getPricingMonth()),row.getBusinessUnitType(),row.getMaterialCode(),
        row.getSpecification(),row.getPricingUnit(),row.getCurrency(),TaxBasis.valueOf(row.getTaxBasis()),row.getRequestedSupplierCode());
    var applicability=codec.decode(row.getApplicabilityJson(),MonthlyPriceApplicability.class);
    var evidence=SourceEvidence.ofJson(codec.unwrap(row.getSourceEvidenceJson()));
    if(!MonthlyPriceDocumentCodec.sha256(key.canonical()).equals(row.getFamilyHash())
        || !MonthlyPriceDocumentCodec.sha256(applicability.canonical()).equals(row.getApplicabilityHash())
        || !evidence.sha256().equals(row.getSourceEvidenceHash())
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

  public void attachCostPart(String unit,long id,long priceId,PricingType type) {
    requireWriteTransaction(); requireBusinessUnit(unit); Objects.requireNonNull(type);
    var current=links.costPart(unit,id);
    if (sameLink(current,priceId,type)) return;
    if (current==null || current.monthlyPriceId()!=null || links.attachCostPart(unit,id,priceId,type.name())!=1) {
      throw new IllegalStateException("Cost part monthly price link conflict");
    }
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
