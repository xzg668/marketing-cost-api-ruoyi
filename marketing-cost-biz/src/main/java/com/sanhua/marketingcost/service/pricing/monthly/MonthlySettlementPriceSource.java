package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Adapts existing published settlement results without generating or recalculating a price. */
@Component
public final class MonthlySettlementPriceSource {
  public record Basis(String unit,String currency,TaxBasis taxBasis,String unitContract,String currencyContract,String taxContract) {}
  public record Snapshot(int snapshotSchema,String sourceKind,JsonNode source,Basis financialBasis) {}
  private final SettlementPriceReader reader;
  private final MonthlyPriceDocumentCodec codec;
  private final ObjectMapper json;
  public MonthlySettlementPriceSource(SettlementPriceReader reader,MonthlyPriceDocumentCodec codec,ObjectMapper json){
    this.reader=reader;this.codec=codec;this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  }
  public Candidates read(Context context,Map<MonthlyPriceRequest,SettlementPriceType> requested){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var codes=new TreeMap<String,List<MonthlyPriceRequest>>();
    for(var request:requested.keySet()){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()) || requested.get(request)==null)throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Settlement source scope or price type differs from request");
      codes.computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var materials=new ArrayList<>(codes.keySet());
    for(int start=0;start<materials.size();start+=256){
      var batch=materials.subList(start,Math.min(start+256,materials.size()));
      var bindings=new LinkedHashMap<MonthlyPriceRequest,SettlementPriceRequest>();
      for(String code:batch)for(var request:codes.get(code))bindings.put(request,new SettlementPriceRequest(context.businessUnitType(),code,context.month().toString(),request.priceOrgCode(),requested.get(request)));
      var results=reader.resolveBatch(List.copyOf(new LinkedHashSet<>(bindings.values())));
      for(var binding:bindings.entrySet()){
        var request=binding.getKey();var result=results.get(binding.getValue());
        if(result==null){problems.put(request,new Problem(Failure.EVIDENCE_UNAVAILABLE,"结算读取未返回请求结果"));continue;}
        if(result.unitPrice()==null || result.hasBlockingFailure()){
          var reason=Set.of("SETTLEMENT_BASE_MISSING","SETTLEMENT_EFFECTIVE_PRICE_MISSING","SETTLEMENT_WEIGHTS_MISSING").contains(Objects.toString(result.failureCode(),""))?Failure.WAITING:result.hasBlockingFailure()?Failure.INVALID_INPUT:Failure.MISSING;
          problems.put(request,new Problem(reason,result.remark()));continue;
        }
        try{
          if(result.evidence().inputSnapshotJson()==null)throw new IllegalArgumentException("结算结果缺少完整保存依据");
          var decoded=json.readTree(result.evidence().inputSnapshotJson());if(!(decoded instanceof ObjectNode captured))throw new IllegalArgumentException("结算结果依据必须是对象");
          var rule=captured.path("rule");
          if(!"lp_settlement_price".equals(captured.path("sourceTable").asText()) || rule.path("id").asLong()<=0 || captured.path("effectivePrice").path("id").asLong()<=0
              || !context.businessUnitType().equals(rule.path("businessUnitType").asText()) || !request.family().materialCode().equals(rule.path("materialCode").asText())
              || !binding.getValue().priceType().name().equals(rule.path("priceType").asText()) || !captured.path("unitPrice").isNumber() || captured.path("unitPrice").decimalValue().compareTo(result.unitPrice())!=0)throw new IllegalArgumentException("结算保存金额、身份或来源依据不一致");
          String sourceUnit=rule.path("materialUnit").asText("");
          if(!"BOM_MATERIAL_UNIT".equals(rule.path("unitBasis").asText()) || (!sourceUnit.isBlank() && !sourceUnit.strip().equals(request.family().unit()))
              || !"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED || request.family().requestedSupplierCode()!=null)throw new IllegalArgumentException("结算单位、币种、税或指定供应商口径不适用，不能猜测转换");
          // SettlementPriceEvidence adds this per-read consumer field; it is not a public price input.
          captured.remove("consumerOrgCode");
          var snapshot=new Snapshot(1,binding.getValue().priceType().name(),captured,new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"BOM_MATERIAL_UNIT","EXISTING_CNY_SETTLEMENT_CONTRACT","EXISTING_SETTLEMENT_EXCLUDED_TAX_CONTRACT"));
          var fact=PriceFact.normal(result.unitPrice(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
          var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
          values.put(request,new Candidate(new Selection(null,coverage,fact),null));
        }catch(java.io.IOException | IllegalArgumentException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"结算来源依据无效："+invalid.getMessage()));}
      }
    }
    return new Candidates(values,problems);
  }
}
