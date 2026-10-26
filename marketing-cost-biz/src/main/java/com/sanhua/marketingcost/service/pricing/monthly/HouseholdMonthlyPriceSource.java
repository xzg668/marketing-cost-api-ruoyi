package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Household configuration is an exact BU/material source, independent of product S/Q/K. */
@Component
public final class HouseholdMonthlyPriceSource {
  public record Source(long versionId,int revisionNo,long importBatchId,String fileKey,String fileSha256,
      String recordFingerprint,String pricingFingerprint,StoredEvidence evidence) {}
  public record Snapshot(int snapshotSchema,String sourceKind,long householdSourceId,Source source,
      PriceContext financialBasis,String unitConversion,String currencyConversion,String taxConversion) {}
  private final HouseholdInquiryStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final com.sanhua.marketingcost.service.MaterialPricePriorityService priorities;
  public HouseholdMonthlyPriceSource(HouseholdInquiryStorage storage,MonthlyPriceDocumentCodec codec,
      com.sanhua.marketingcost.service.MaterialPricePriorityService priorities){this.storage=storage;this.codec=codec;this.priorities=priorities;}

  /** An absent source is left for ordinary routing. A matched but invalid source blocks fallback. */
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    for(var request:requests)if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))
      throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"Household source scope differs from request");
    requests=automatic(context,requests);
    var codes=new TreeSet<String>();
    for(var request:requests){if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"Household source scope differs from request");codes.add(request.family().materialCode());}
    var rows=new HashMap<String,HouseholdInquiryPriceRow>();
    for(var row:storage.current(context.businessUnitType(),codes))if(rows.putIfAbsent(row.getMaterialCode(),row)!=null)throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.PERSISTENCE_INCOMPLETE,"Multiple current household versions");
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();var facts=new HashMap<Long,PriceFact>();
    for(var request:requests){
      var row=rows.get(request.family().materialCode());if(row==null)continue;
      var family=request.family();
      if(!family.unit().equals(row.getPricingUnit()) || !family.currency().equals(row.getCurrency()) || !family.taxBasis().name().equals(row.getTaxBasis()) || family.requestedSupplierCode()!=null){
        problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,"家用询价来源与请求的单位、币种、税口径或指定供应商条件不匹配；需要明确依据"));continue;
      }
      try{
        var fact=facts.computeIfAbsent(row.getId(),ignored->capture(row));
        var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
        values.put(request,new Candidate(new Selection(null,coverage,fact),row.getId()));
      }catch(IllegalArgumentException invalid){problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.EVIDENCE_UNAVAILABLE,"家用来源依据不完整："+invalid.getMessage()));}
    }
    return new Candidates(values,problems);
  }
  /** Scope probes also call this adapter directly; finance designations must apply there too. */
  private Set<MonthlyPriceRequest> automatic(Context context,Set<MonthlyPriceRequest> requests) {
    var result=new LinkedHashSet<>(requests);var groups=new TreeMap<String,Map<String,String>>();
    requests.forEach(request->groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>())
        .put(request.family().materialCode(),request.family().materialCode()));
    groups.forEach((org,names)->{
      var codes=new ArrayList<>(names.keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=new LinkedHashMap<String,String>();codes.subList(start,Math.min(start+256,codes.size())).forEach(code->batch.put(code,code));
        var scope=new com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope(context.businessUnitType(),context.month().toString(),org,batch.keySet().iterator().next());
        priorities.readBatch(scope,batch,false).forEach((code,config)->{
          if(config.manual())result.removeIf(request->org.equals(request.priceOrgCode()) && code.equals(request.family().materialCode()));
        });
      }
    });return Set.copyOf(result);
  }
  private PriceFact capture(HouseholdInquiryPriceRow row){
    var evidence=codec.decode(codec.wrap(row.getReferenceJson()),StoredEvidence.class);
    if(!row.getMaterialCode().equals(evidence.source().materialCode()) || row.getUnitPrice().compareTo(new BigDecimal(evidence.source().unitPrice()))!=0 || row.getHouseholdRate().compareTo(new BigDecimal(evidence.source().householdRate()))!=0 || !row.getSourceMonth().equals(evidence.sourceMonth()) || !row.getPricingUnit().equals(evidence.priceContext().unit()) || !row.getCurrency().equals(evidence.priceContext().currency()) || !row.getTaxBasis().equals(evidence.priceContext().taxBasis().name()))throw new IllegalArgumentException("保存行与原始P/H及来源口径不一致");
    var source=new Source(row.getId(),row.getRevisionNo(),row.getImportBatchId(),row.getFileKey(),row.getFileSha256(),row.getRecordFingerprint(),row.getPricingFingerprint(),evidence);
    var snapshot=new Snapshot(1,"HOUSEHOLD_INQUIRY",row.getId(),source,evidence.priceContext(),"IDENTITY","IDENTITY","IDENTITY");
    return PriceFact.household(row.getUnitPrice(),row.getHouseholdRate(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))));
  }
}
