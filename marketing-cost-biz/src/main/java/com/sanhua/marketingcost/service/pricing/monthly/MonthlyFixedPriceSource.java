package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.monthly.MonthlyFixedSourceMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplierInputMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.util.*;
import org.springframework.stereotype.Component;

/** Fixed purchase candidates preserve the existing version/supplier policy and full raw rows. */
@Component
public final class MonthlyFixedPriceSource {
  public record Source(PriceFixedItem selected,List<PriceFixedItem> candidates,List<SupplierSupplyRatio> ratios,String selectionRule) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String unitConversion,String currencyConversion,String taxConversion) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private final MonthlyFixedSourceMapper rows;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyFixedPriceSource(MonthlyFixedSourceMapper rows,MonthlySupplierInputMapper supplierInputs,SupplierPriceSelectionService suppliers,MonthlyPriceDocumentCodec codec){this.rows=rows;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.codec=codec;}

  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Fixed source scope differs from request");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>()).computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    for(var organization:groups.entrySet()){
      var codes=new ArrayList<>(organization.getValue().keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var raw=rows.prices(context.businessUnitType(),organization.getKey(),batch);
        var byCode=new HashMap<String,List<PriceFixedItem>>();raw.forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        var ratioCodes=new TreeSet<>(byCode.keySet());
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();if(!ratioCodes.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),ratioCodes).forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var scopes=new HashMap<String,SupplierPriceScope>();for(String code:ratioCodes)if(byCode.get(code).size()>1)scopes.put(code,scope(context,organization.getKey(),code));
        var decisions=new HashMap<String,SupplierPriceDecision>();if(!scopes.isEmpty())supplierInputs.decisions(scopes.values().stream().map(SupplierPriceSelectionService::scopeKey).distinct().toList()).forEach(row->decisions.put(row.getScopeKey(),row));
        for(String code:batch)for(var request:organization.getValue().get(code)){
          var candidates=byCode.getOrDefault(code,List.of());if(candidates.isEmpty())continue;
          try{
            String requested=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
            var scoped=requested==null?candidates:candidates.stream().filter(row->requested.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode()))).toList();
            var preferred=SupplyPriceDatePolicy.preferCurrent(scoped,context.priceAsOfTime().toLocalDate(),PriceFixedItem::getEffectiveFrom,PriceFixedItem::getEffectiveTo);
            var versions=versions(preferred);
            if(versions.isEmpty())continue;
            var active=List.copyOf(versions.values());for(var row:active)validateBasis(request,row);
            var supplierCandidates=versions.entrySet().stream().map(entry->new SupplierPriceCandidate(entry.getKey(),entry.getKey(),entry.getValue().getSupplierName(),entry.getValue().getFixedPrice(),entry.getValue().getEffectiveFrom(),entry.getValue().getEffectiveTo())).toList();
            var scope=scope(context,organization.getKey(),code);var actualRatios=ratios.getOrDefault(code,List.of());
            var selected=suppliers.selectPrepared(scope,supplierCandidates,actualRatios,decisions.get(SupplierPriceSelectionService.scopeKey(scope)));
            if(selected.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(selected.review());
            if(selected.candidate()==null){problems.put(request,new Problem(Failure.WAITING,selected.message()));continue;}
            var chosen=versions.get(selected.candidate().key());
            var snapshot=new Snapshot(1,"PURCHASE_FIXED",new Source(chosen,active,actualRatios,selected.rule()),new Basis(request.family().unit(),"CNY",request.family().taxBasis(),"IDENTITY","EXISTING_CNY_COST_PRICE_CONTRACT","IDENTITY"));
            var fact=PriceFact.normal(chosen.getFixedPrice(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
            if(selected.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(selected.review(),fact.evidence().sha256());
            var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any()));
            values.put(request,new Candidate(new Selection(null,coverage,fact),null));
          }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"固定采购来源无效："+invalid.getMessage()));}
        }
      }
    }
    for(var request:requests) if(!values.containsKey(request))
      problems.putIfAbsent(request,new Problem(Failure.MISSING,"没有适用的固定采购价格"));
    return new Candidates(values,problems);
  }
  private LinkedHashMap<String,PriceFixedItem> versions(List<PriceFixedItem> candidates){
    var result=new LinkedHashMap<String,PriceFixedItem>();
    for(var row:candidates){
      String supplier=SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode());if(supplier==null)throw new IllegalArgumentException("固定采购价缺少供应商代码");
      var old=result.get(supplier);if(old==null){result.put(supplier,row);continue;}
      int order=Comparator.comparing(PriceFixedItem::getEffectiveFrom,Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(PriceFixedItem::getEffectiveTo,Comparator.nullsFirst(Comparator.naturalOrder())).compare(row,old);
      if(order>0)result.put(supplier,row);else if(order==0 && row.getFixedPrice().compareTo(old.getFixedPrice())!=0)throw new IllegalArgumentException("同供应商同有效期间存在冲突价格");
    }
    return result;
  }
  private void validateBasis(MonthlyPriceRequest request,PriceFixedItem row){
    if(row.getFixedPrice().signum()<0 || row.getUnit()==null || !row.getUnit().strip().equals(request.family().unit()) || !"CNY".equals(request.family().currency()) || row.getTaxIncluded()==null || row.getTaxIncluded()!=(request.family().taxBasis()==TaxBasis.INCLUDED?1:0))throw new IllegalArgumentException("单位、币种、税口径或价格与请求不匹配，不能猜测转换");
  }
  private SupplierPriceScope scope(Context context,String org,String code){return new SupplierPriceScope(context.businessUnitType(),context.oaNo(),context.oaFormItemId(),context.month().toString(),context.priceAsOfTime().toLocalDate(),org,code,"FIXED");}
}
