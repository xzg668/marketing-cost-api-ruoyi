package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.formula.registry.FinanceFactorBinding;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot;
import com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Captures existing linked-table legacy rules and evaluates them through the existing formula pipeline. */
@Component
public final class MonthlyLegacyLinkedPriceSource {
  public record SupplierProof(String mode,int candidateSupplierCount,String supplierCode,String supplierName,
      Long ratioRecordId,BigDecimal ratio,String message) {}
  public record Source(String originalMonth,PriceLinkedItem selected,List<PriceLinkedItem> candidateRules,
      String supplierRatioMaterialCode,List<SupplierSupplyRatio> ratios,SupplierProof supplier,
      Map<String,Object> calculation,List<FactorUploadBatch> sourceFiles,List<FactorRowRef> privateFactorSources,
      Set<Long> unavailableLegacyArchiveIds) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String calculation,String rounding) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private record Selected(String month,PriceLinkedItem rule,List<PriceLinkedItem> candidates,
      String ratioCode,List<SupplierSupplyRatio> ratios,SupplierProof supplier) {}
  private record Financial(FactorPriceSnapshot snapshot,RuntimeException failure) {}
  private record Calculated(Selected selected,PreparedFormulaInputs.Requirements needs,
      Map<String,Object> calculation,BigDecimal price,MonthlyFormulaScrapDependencies.Input scrap,
      Set<Long> batchIds,Set<Long> privatePriceIds) {}

  private final MonthlyOriginalLinkedSourceMapper rules;
  private final MonthlyLinkedFormulaInputMapper inputs;
  private final MonthlyPrivateFactorInputMapper privatePrices;
  private final MonthlySupplierInputMapper ratios;
  private final MaterialPriceMonthSelector months;
  private final FinanceFactorPriceReader finance;
  private final SupplierPreferredPriceSelector suppliers;
  private final PriceLinkedFormulaPreviewServiceImpl calculator;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyFormulaScrapDependencies scrapDependencies;
  private final FactorCanonicalKeyService factorKeys;

  public MonthlyLegacyLinkedPriceSource(MonthlyOriginalLinkedSourceMapper rules,
      MonthlyLinkedFormulaInputMapper inputs,MonthlyPrivateFactorInputMapper privatePrices,
      MonthlySupplierInputMapper ratios,MaterialPriceMonthSelector months,FinanceFactorPriceReader finance,
      SupplierPreferredPriceSelector suppliers,PriceLinkedFormulaPreviewServiceImpl calculator,
      MonthlyPriceDocumentCodec codec,FactorCanonicalKeyService factorKeys) {
    this.rules=rules;this.inputs=inputs;this.privatePrices=privatePrices;this.ratios=ratios;
    this.months=months;this.finance=finance;this.suppliers=suppliers;this.calculator=calculator;
    this.codec=codec;this.scrapDependencies=new MonthlyFormulaScrapDependencies(codec);
    this.factorKeys=factorKeys;
  }

  public Candidates read(Context context,Set<MonthlyPriceRequest> requests,
      Function<Set<MonthlyPriceRequest>,Candidates> dependencies) {
    var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var selected=select(context,requests,problems);
    if(selected.isEmpty())return new Candidates(Map.of(),problems);
    var bindings=new ArrayList<PriceVariableBinding>();
    for(var ids:batches(selected.values().stream().map(value->value.rule().getId()).toList()))
      bindings.addAll(inputs.bindings(context.businessUnitType(),ids));
    var catalog=PreparedFormulaInputs.Catalog.fromScopedSql(inputs.variables(context.businessUnitType()),inputs.placeholders(),bindings);
    var requirements=new LinkedHashMap<MonthlyPriceRequest,PreparedFormulaInputs.Requirements>();
    var identityIds=new TreeSet<Long>();
    for(var entry:selected.entrySet())try {
      var needs=catalog.requirements(entry.getValue().rule());
      requirements.put(entry.getKey(),needs);identityIds.addAll(needs.factorIdentityIds());
    }catch(IllegalArgumentException invalid){problems.put(entry.getKey(),invalid(invalid));}
    var identities=new HashMap<Long,FactorIdentity>();
    for(var batch:batches(identityIds))inputs.identities(context.businessUnitType(),batch)
        .forEach(row->identities.put(row.getId(),row));
    var privateByIdentity=new HashMap<Long,List<FactorMonthlyPrice>>();
    privatePrices.read(context.businessUnitType(),context.month().toString(),identityIds)
        .forEach(row->privateByIdentity.computeIfAbsent(row.getFactorIdentityId(),ignored->new ArrayList<>()).add(row));
    var financialCache=new HashMap<String,Financial>();
    var managedCache=new HashMap<String,Set<String>>();
    var keysByRequest=new HashMap<MonthlyPriceRequest,Set<String>>();
    var keysByOrganization=new HashMap<String,Set<String>>();
    for(var entry:requirements.entrySet())try {
      var keys=new TreeSet<String>();
      for(var binding:entry.getValue().financialBindings()) {
        Long id=FinanceFactorBinding.identityId(binding);
        keys.add(FinanceFactorBinding.canonicalKey(binding,context.businessUnitType(),id==null?null:identities.get(id),factorKeys));
      }
      keysByRequest.put(entry.getKey(),keys);
      keysByOrganization.computeIfAbsent(entry.getKey().priceOrgCode(),ignored->new TreeSet<>()).addAll(keys);
    }catch(IllegalArgumentException invalid){problems.put(entry.getKey(),invalid(invalid));}
    keysByOrganization.forEach((org,keys)->{
      var managed=new HashSet<String>();
      for(var batch:batches(keys))managed.addAll(inputs.managedFactors(context.businessUnitType(),org,batch));
      managedCache.put(org,Set.copyOf(managed));
    });
    var captured=new HashMap<MonthlyPriceRequest,PreparedFormulaInputs.Financial>();
    for(var entry:requirements.entrySet())try {
      var request=entry.getKey();var needs=entry.getValue();PriceFactorContext factors=null;
      if(problems.containsKey(request))continue;
      Set<String> managed=Set.of();
      if(needs.financialRequired()) {
        String key=request.priceOrgCode()+":"+condition(request);
        var financial=financialCache.computeIfAbsent(key,ignored->captureFinancial(context,request));
        if(financial.failure()!=null)throw financial.failure();
        factors=new PriceFactorContext(request.family().materialCode(),
            new PriceMonthSelection(context.month().toString(),financial.snapshot().scope().priceMonth()),financial.snapshot(),null);
        var board=request.conditions().boardTerms();
        if("220".equals(request.priceOrgCode()) && board!=null)
          factors=factors.withBoardInputs(new BoardPriceInputs(board.sus304PricePerTon(),board.sus316lPricePerTon(),board.finalTender()));
        var orgManaged=managedCache.getOrDefault(request.priceOrgCode(),Set.of());
        managed=keysByRequest.get(request).stream().filter(orgManaged::contains).collect(java.util.stream.Collectors.toSet());
      }
      var usedIdentities=new HashMap<Long,FactorIdentity>();var usedPrices=new ArrayList<FactorMonthlyPrice>();
      for(Long id:needs.factorIdentityIds()) {
        if(identities.containsKey(id))usedIdentities.put(id,identities.get(id));
        usedPrices.addAll(privateByIdentity.getOrDefault(id,List.of()));
      }
      captured.put(request,new PreparedFormulaInputs.Financial(factors,usedIdentities,managed,usedPrices));
    }catch(FinanceFactorPriceReader.MissingPublicationException waiting){problems.put(entry.getKey(),new Problem(Failure.WAITING,waiting.getMessage()));}
    catch(IllegalArgumentException | IllegalStateException invalid){problems.put(entry.getKey(),invalid(invalid));}

    var mappings=new LinkedHashMap<MonthlyPriceRequest,List<MaterialScrapRef>>();
    var scrapCodes=new TreeSet<String>();
    requirements.forEach((request,needs)->{if(needs.scrapRequired() && captured.containsKey(request))scrapCodes.add(request.family().materialCode());});
    var references=new HashMap<String,List<MaterialScrapRef>>();
    for(var batch:batches(scrapCodes))inputs.scrapReferences(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),batch)
        .forEach(row->references.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row.reference()));
    requirements.forEach((request,needs)->{if(needs.scrapRequired() && captured.containsKey(request))
      mappings.put(request,references.getOrDefault(request.family().materialCode(),List.of()));});
    var scraps=scrapDependencies.resolve(context,mappings,dependencies);
    problems.putAll(scraps.problems());
    var calculated=new LinkedHashMap<MonthlyPriceRequest,Calculated>();
    for(var entry:captured.entrySet()) {
      var request=entry.getKey();if(problems.containsKey(request))continue;
      var item=selected.get(request);var needs=requirements.get(request);var scrap=scraps.values().get(request);
      try {
        var prepared=new PreparedFormulaInputs(catalog,entry.getValue(),scrap==null?PreparedFormulaInputs.Scrap.empty():scrap.scrap());
        var result=calculator.previewPrepared(item.rule(),context.month().toString(),context.priceAsOfTime().toLocalDate(),prepared);
        if(result.getError()!=null || result.getResult()==null || result.getInputSnapshotJson()==null)
          throw new IllegalArgumentException("联动计算失败："+result.getError());
        if(result.getResult().signum()<0)throw new IllegalArgumentException("联动价格不能为负数，舍入前校验");
        var calculation=decode(result.getInputSnapshotJson());
        var batchIds=new TreeSet<Long>();var priceIds=new TreeSet<Long>();
        if(item.rule().getSourceUploadBatchId()!=null)batchIds.add(item.rule().getSourceUploadBatchId());
        Object used=calculation.get("usedPrivateFactors");
        if(used instanceof Map<?,?> map)for(Object raw:map.values()) {
          if(!(raw instanceof Map<?,?> price))throw new IllegalArgumentException("私有因素计算依据无效");
          addId(priceIds,price.get("id"));addId(batchIds,price.get("sourceUploadBatchId"));
        }
        calculated.put(request,new Calculated(item,needs,calculation,result.getResult().setScale(6,RoundingMode.HALF_UP),scrap,batchIds,priceIds));
      }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,invalid(invalid));}
    }
    var requiredBatches=new TreeSet<Long>();var requiredPrices=new TreeSet<Long>();
    calculated.values().forEach(value->{requiredBatches.addAll(value.batchIds());requiredPrices.addAll(value.privatePriceIds());});
    var archives=new HashMap<Long,FactorUploadBatch>();
    for(var batch:batches(requiredBatches))inputs.batches(context.businessUnitType(),batch).forEach(row->archives.put(row.getId(),row));
    var privateSources=new HashMap<Long,List<FactorRowRef>>();
    for(var batch:batches(requiredPrices))inputs.priceReferences(context.businessUnitType(),batch)
        .forEach(row->privateSources.computeIfAbsent(row.getFactorMonthlyPriceId(),ignored->new ArrayList<>()).add(row));
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();
    for(var entry:calculated.entrySet())try {
      var request=entry.getKey();var result=entry.getValue();var item=result.selected();
      var missing=new TreeSet<>(result.batchIds());missing.removeAll(archives.keySet());
      var source=new Source(item.month(),item.rule(),item.candidates(),item.ratioCode(),item.ratios(),item.supplier(),
          result.calculation(),result.batchIds().stream().map(archives::get).filter(Objects::nonNull).toList(),
          result.privatePriceIds().stream().flatMap(id->privateSources.getOrDefault(id,List.of()).stream()).toList(),missing);
      var snapshot=new Snapshot(1,"LINKED_LEGACY",source,new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"EXISTING_VARIABLE_FORMULA_PREVIEW","DECIMAL_6_HALF_UP"));
      Set<MonthlyPriceRequest> childRequests=result.scrap()==null?Set.of():result.scrap().dependencies();
      var fact=PriceFact.normal(result.price(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),childRequests);
      var own=new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),
          "220".equals(request.priceOrgCode()) && result.needs().financialRequired()?ConditionDomain.exact(request.conditions()):ConditionDomain.any());
      var coverage=result.scrap()==null?MonthlyPriceApplicability.of(own):new MonthlyPriceApplicability(
          result.scrap().availability().fragments().stream().map(own::intersection).flatMap(Optional::stream).toList());
      values.put(request,new Candidate(new Selection(null,coverage,fact),null));
      if(result.scrap()!=null)for(var dependency:result.scrap().resolvedDependencies().entrySet()) {
        var old=values.putIfAbsent(dependency.getKey(),dependency.getValue());
        if(old!=null && !old.equals(dependency.getValue()))throw new MonthlyPriceProblem(Failure.REPLAN_REQUIRED,"A formula dependency changed within the source batch");
        var issue=problems.get(dependency.getKey());
        if(issue!=null && issue.reason()==Failure.MISSING)problems.remove(dependency.getKey());
      }
    }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(entry.getKey(),invalid(invalid));}
    return new Candidates(values,problems);
  }

  private Map<MonthlyPriceRequest,Selected> select(Context context,Set<MonthlyPriceRequest> requests,
      Map<MonthlyPriceRequest,Problem> problems) {
    var organizations=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests) {
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))
        throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Linked formula source scope differs from request");
      organizations.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
          .computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var result=new LinkedHashMap<MonthlyPriceRequest,Selected>();
    for(var organization:organizations.entrySet()) {
      var month=months.select(context.businessUnitType(),organization.getKey(),context.month().toString());
      if(month==null)month=new PriceMonthSelection(context.month().toString(),context.month().toString());
      for(var batch:batches(organization.getValue().keySet())) {
        var byCode=new HashMap<String,List<PriceLinkedItem>>();
        rules.rules(context.businessUnitType(),organization.getKey(),month.sourceMonth(),context.month().toString(),context.priceAsOfTime().toLocalDate(),batch)
            .forEach(row->byCode.computeIfAbsent(row.getMaterialCode().trim(),ignored->new ArrayList<>()).add(row));
        var ratioCodes=new TreeSet<String>();
        byCode.forEach((code,rows)->{if(rows.stream().map(row->supplierKey(row)).distinct().count()>1)
          ratioCodes.add(SupplierSupplyRatioNormalizeUtils.normalizeToNull(code));});
        var capturedRatios=new HashMap<String,List<SupplierSupplyRatio>>();
        if(!ratioCodes.isEmpty())ratios.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),ratioCodes)
            .forEach(row->capturedRatios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var primary=suppliers.prepareSuppliers(context.businessUnitType(),ratioCodes,context.priceAsOfTime().toLocalDate(),capturedRatios);
        for(String code:batch)for(var request:organization.getValue().get(code))try {
          var candidates=heads(request,byCode.getOrDefault(code,List.of()));
          if(candidates.isEmpty()){problems.put(request,new Problem(Failure.MISSING,MaterialPriceMonthSelector.missingMessage(code,request.priceOrgCode(),month)));continue;}
          long original=candidates.stream().filter(row->OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())).count();
          if(original==candidates.size()){problems.put(request,new Problem(Failure.MISSING,"原稿联动由原稿适配器处理"));continue;}
          if(original>0 || candidates.stream().anyMatch(row->!"LEGACY".equals(row.getExpressionVersion())))
            throw new IllegalArgumentException("同一有效物料范围混有不同表达式契约");
          if(!"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED)
            throw new IllegalArgumentException("旧联动核算价要求CNY及既有不含税成本口径");
          String ratioCode=SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);
          var chosen=suppliers.select(candidates,context.businessUnitType(),code,candidates.getFirst().getMaterialName(),
              candidates.getFirst().getSpecModel(),context.priceAsOfTime().toLocalDate(),PriceLinkedItem::getSupplierName,
              PriceLinkedItem::getSupplierCode,primary.getOrDefault(ratioCode,SupplierSupplyRatioResolveResult.miss("没有有效主供应商")));
          if(chosen.failed() || chosen.row()==null){problems.put(request,new Problem(Failure.WAITING,chosen.failureMessage()));continue;}
          if(candidates.stream().filter(row->chosen.matchesSupplier(row.getSupplierName(),row.getSupplierCode())).count()>1)
            throw new IllegalArgumentException("同供应商存在多个适用联动规则身份，须明确规格和单位");
          var proof=new SupplierProof(chosen.matchMode(),chosen.candidateSupplierCount(),chosen.mainSupplierCode(),chosen.mainSupplierName(),chosen.supplyRatioRecordId(),chosen.supplyRatio(),chosen.traceMessage());
          result.put(request,new Selected(chosen.row().getPricingMonth(),chosen.row(),candidates,ratioCode,capturedRatios.getOrDefault(ratioCode,List.of()),proof));
        }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,invalid(invalid));}
      }
    }
    return result;
  }

  private List<PriceLinkedItem> heads(MonthlyPriceRequest request,List<PriceLinkedItem> rows) {
    String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    return LinkedPriceRuleVersions.current(rows.stream()
        .filter(row->request.family().unit().equals(row.getUnit()) && (request.family().specification()==null || request.family().specification().equals(row.getSpecModel())))
        .filter(row->explicit==null || explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode()))).toList());
  }

  private Financial captureFinancial(Context context,MonthlyPriceRequest request) {
    try{return new Financial(finance.readEffective(new FinanceFactorImportContext(context.businessUnitType(),request.priceOrgCode(),context.month().toString(),null,null),condition(request),request.family().materialCode()).financial(),null);}
    catch(IllegalArgumentException | IllegalStateException invalid){return new Financial(null,invalid);}
  }
  private static FactorPriceScope.Condition condition(MonthlyPriceRequest request) {
    var board=request.conditions().boardTerms();return "220".equals(request.priceOrgCode())
        ?board!=null && board.finalTender()?FactorPriceScope.Condition.TENDER:FactorPriceScope.Condition.NON_TENDER:FactorPriceScope.Condition.GENERAL;
  }
  private static String supplierKey(PriceLinkedItem row) {
    return LinkedPriceRuleVersions.supplierKey(row);
  }
  private static Problem invalid(RuntimeException failure){return new Problem(Failure.INVALID_INPUT,"旧联动来源无效："+failure.getMessage());}
  private Map<String,Object> decode(String json) {
    var raw=codec.decode(codec.wrap(json),Map.class);var result=new LinkedHashMap<String,Object>();
    raw.forEach((key,value)->result.put(key.toString(),value));return result;
  }
  private static void addId(Set<Long> ids,Object value){if(value!=null)ids.add(Long.valueOf(value.toString()));}
  private static <T extends Comparable<? super T>> List<List<T>> batches(Collection<T> values) {
    var all=new ArrayList<>(new TreeSet<>(values));var result=new ArrayList<List<T>>();
    for(int start=0;start<all.size();start+=256)result.add(all.subList(start,Math.min(start+256,all.size())));
    return result;
  }
}
