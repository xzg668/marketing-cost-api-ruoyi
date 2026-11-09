package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** BOM code selects by code; an absent BOM code selects the exact specification. */
@Component
public final class HouseholdMonthlyPriceSource {
  public record Source(long versionId,int revisionNo,long importBatchId,String fileKey,String fileSha256,
      String recordFingerprint,String pricingFingerprint,StoredEvidence evidence) {}
  public record Match(String kind,String requestedMaterialCode,String requestedSpecification,String matchedValue,String organization) {}
  public record Snapshot(int snapshotSchema,String sourceKind,long householdSourceId,Source source,
      PriceContext financialBasis,String unitConversion,String currencyConversion,String taxConversion,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) HouseholdMetalCalculation.Inputs metalInputs,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Match match) {
    public Snapshot(int snapshotSchema,String sourceKind,long householdSourceId,Source source,PriceContext financialBasis,String unitConversion,String currencyConversion,String taxConversion) {
      this(snapshotSchema,sourceKind,householdSourceId,source,financialBasis,unitConversion,currencyConversion,taxConversion,null,null);
    }
    public Snapshot(int snapshotSchema,String sourceKind,long householdSourceId,Source source,PriceContext financialBasis,String unitConversion,String currencyConversion,String taxConversion,HouseholdMetalCalculation.Inputs metalInputs) {
      this(snapshotSchema,sourceKind,householdSourceId,source,financialBasis,unitConversion,currencyConversion,taxConversion,metalInputs,null);
    }
  }
  private final HouseholdInquiryStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final com.sanhua.marketingcost.service.MaterialPricePriorityService priorities;
  private final com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader finance;
  public HouseholdMonthlyPriceSource(HouseholdInquiryStorage storage,MonthlyPriceDocumentCodec codec,
      com.sanhua.marketingcost.service.MaterialPricePriorityService priorities,com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader finance){this.storage=storage;this.codec=codec;this.priorities=priorities;this.finance=finance;}

  /** An absent source is left for ordinary routing. A matched but invalid source blocks fallback. */
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    for(var request:requests)if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"Household source scope differs from request");
    requests=automatic(context,requests);
    var codes=new TreeSet<String>();
    for(var request:requests){if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"Household source scope differs from request");codes.add(request.materialCode());}
    var rows=new HashMap<String,HouseholdInquiryPriceRow>();
    for(var row:storage.asOfMonth(context.businessUnitType(),codes,context.month()))if(rows.putIfAbsent(row.getMaterialCode(),row)!=null)throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.PERSISTENCE_INCOMPLETE,"Multiple current household versions");
    var missing=requests.stream().filter(request->request.family().specificationOnly() && !rows.containsKey(request.materialCode())).toList();
    var terms=new LinkedHashMap<MonthlyPriceRequest,Set<String>>();
    missing.forEach(request->terms.put(request,Set.of(request.family().specification())));
    var keys=new TreeSet<String>();terms.values().forEach(values->values.forEach(value->keys.add(com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryKey.of(null,value))));
    var fallback=storage.asOfKeys(context.businessUnitType(),keys,context.month());
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();var facts=new HashMap<Long,PriceFact>();
    for(var request:requests){
      var selected=rows.get(request.materialCode());
      if(selected!=null && !request.matchesSpecification(selected.getSpecification()))selected=null;
      if(selected==null) {
        if(!request.family().specificationOnly())continue;
        var matches=fallback.stream().filter(value->value.getMaterialCode()==null && terms.getOrDefault(request,Set.of()).contains(value.getSpecification())).toList();
        if(matches.size()>1) {
          problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"家用询价规格匹配到多个来源，请明确匹配："+matches.stream().map(HouseholdInquiryPriceRow::getSpecification).toList()));continue;
        }
        if(matches.isEmpty())continue;
        selected=matches.getFirst();
      }
      final var row=selected;
      if(!request.matchesSpecification(row.getSpecification()))continue;
      var family=request.family();
      if(!family.currency().equals(row.getCurrency()) || !family.taxBasis().name().equals(row.getTaxBasis()) || family.requestedSupplierCode()!=null){
        problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"家用询价来源与请求的币种、税口径或指定供应商条件不匹配；需要明确依据"));continue;
      }
      try{
        var fact=row.getMaterialCode()==null?capture(row,context,request):facts.computeIfAbsent(row.getId(),ignored->capture(row,context,request));
        var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(row.getMaterialCode()==null?Set.of(request.priceOrgCode()):Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
        values.put(request,new Candidate(new Selection(null,coverage,fact),row.getId()));
      }catch(IllegalArgumentException invalid){problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.EVIDENCE_UNAVAILABLE,"家用来源依据不完整："+invalid.getMessage()));}
    }
    return new Candidates(values,problems);
  }
  /** Scope probes also call this adapter directly; finance designations must apply there too. */
  private Set<MonthlyPriceRequest> automatic(Context context,Set<MonthlyPriceRequest> requests) {
    var result=new LinkedHashSet<>(requests);var groups=new TreeMap<String,Map<String,String>>();
    requests.stream().filter(request->!request.materialCode().isBlank()).forEach(request->groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
        .put(request.materialCode(),request.materialCode()));
    groups.forEach((org,names)->{
      var codes=new ArrayList<>(names.keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=new LinkedHashMap<String,String>();codes.subList(start,Math.min(start+256,codes.size())).forEach(code->batch.put(code,code));
        var scope=new com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope(context.businessUnitType(),context.month().toString(),org,batch.keySet().iterator().next());
        priorities.readBatch(scope,batch,false).forEach((code,config)->{
          if(config.manual())result.removeIf(request->org.equals(request.priceOrgCode()) && code.equals(request.materialCode()));
        });
      }
    });return Set.copyOf(result);
  }
  private PriceFact capture(HouseholdInquiryPriceRow row,Context context,MonthlyPriceRequest request){
    var evidence=codec.decode(codec.wrap(row.getReferenceJson()),StoredEvidence.class);
    if(!Objects.equals(row.getMaterialCode(),evidence.source().materialCode()) || row.getUnitPrice().compareTo(new BigDecimal(evidence.source().unitPrice()))!=0 || row.getHouseholdRate().compareTo(new BigDecimal(evidence.source().householdRate()))!=0 || !row.getSourceMonth().equals(evidence.sourceMonth()) || !row.getPricingUnit().equals(evidence.priceContext().unit()) || !row.getCurrency().equals(evidence.priceContext().currency()) || !row.getTaxBasis().equals(evidence.priceContext().taxBasis().name()))throw new IllegalArgumentException("保存行与原始P/H及来源口径不一致");
    var source=new Source(row.getId(),row.getRevisionNo(),row.getImportBatchId(),row.getFileKey(),row.getFileSha256(),row.getRecordFingerprint(),row.getPricingFingerprint(),evidence);
    var original=evidence.source();
    var financial=original.copperGrams().signum()==0 && original.zincGrams().signum()==0 ? null :
        finance.readDefault(new com.sanhua.marketingcost.dto.FinanceFactorImportContext(context.businessUnitType(),"210",context.month().toString(),null,null),request.materialCode().isBlank()?"MONTHLY_HOUSEHOLD":request.materialCode())
            .evidenceForFactors(Set.of(com.sanhua.marketingcost.service.pricing.PriceFactorContext.COPPER,com.sanhua.marketingcost.service.pricing.PriceFactorContext.ZINC));
    var inputs=new HouseholdMetalCalculation.Inputs(original.copperGrams(),original.zincGrams(),financial);
    var match=row.getMaterialCode()==null?new Match("SPECIFICATION",request.materialCode(),request.family().specification(),row.getSpecification(),request.priceOrgCode()):null;
    var snapshot=new Snapshot(1,"HOUSEHOLD_INQUIRY",row.getId(),source,evidence.priceContext(),"IDENTITY","IDENTITY","IDENTITY",inputs,match);
    return PriceFact.household(row.getUnitPrice(),row.getHouseholdRate(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))));
  }
}
