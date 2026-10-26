package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.scrap.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

/** Read-only scrap rules and actual published commercial finance inputs; never generates carry rows. */
@Component
public final class MonthlyScrapPriceSource {
  public record Calculation(ScrapPrice original,ScrapPriceCalculator.Input input,BigDecimal price) {}
  public record Source(ScrapPrice selected,List<Calculation> candidates,List<SupplierSupplyRatio> ratios,String selectionRule) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String conversion) {}
  public record Snapshot(int snapshotSchema,String sourceKind,String requestedMonth,Source source,Basis financialBasis) {}
  private final MonthlyScrapSourceMapper rows;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final FinanceFactorPriceReader finance;
  private final ScrapPriceCalculator calculator;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyScrapPriceSource(MonthlyScrapSourceMapper rows,MonthlySupplierInputMapper supplierInputs,
      SupplierPriceSelectionService suppliers,FinanceFactorPriceReader finance,ScrapPriceCalculator calculator,MonthlyPriceDocumentCodec codec){
    this.rows=rows;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.finance=finance;this.calculator=calculator;this.codec=codec;
  }
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var byCode=new TreeMap<String,List<MonthlyPriceRequest>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Scrap source scope differs from request");
      byCode.computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var codes=new ArrayList<>(byCode.keySet());var financial=new FinancialInput(context);
    for(int start=0;start<codes.size();start+=256){
      var batch=codes.subList(start,Math.min(start+256,codes.size()));
      var history=new HashMap<String,List<ScrapPrice>>();rows.rules(context.businessUnitType(),batch).forEach(row->history.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
      var multiple=new TreeSet<String>();history.forEach((code,items)->{if(items.stream().map(ScrapRuleMonthPolicy::identity).map(ScrapPriceIdentity::supplierKey).distinct().count()>1)multiple.add(code);});
      var ratios=new HashMap<String,List<SupplierSupplyRatio>>();if(!multiple.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),multiple).forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
      var scopes=new TreeMap<String,SupplierPriceScope>();for(String code:multiple)for(var request:byCode.get(code)){var scope=scope(context,request);scopes.put(SupplierPriceSelectionService.scopeKey(scope),scope);}
      var decisions=new HashMap<String,SupplierPriceDecision>();if(!scopes.isEmpty())supplierInputs.decisions(scopes.keySet()).forEach(row->decisions.put(row.getScopeKey(),row));
      for(String code:batch){
        var registered=history.get(code);if(registered==null)continue;
        for(var request:byCode.get(code))try{
          validateBasis(request);
          String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
          // Replacement markers may live on another supplier's newer rule.
          var effective=ScrapRuleMonthPolicy.select(registered,context.businessUnitType(),context.month().toString()).values();
          var applicable=explicit==null?effective:effective.stream().filter(row->explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode()))).toList();
          if(applicable.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"已登记废料在请求月份没有适用规则或指定供应商"));continue;}
          var calculations=new LinkedHashMap<String,Calculation>();
          for(var original:applicable){
            if(applicable.size()>1 && SupplierSupplyRatioNormalizeUtils.normalizeToNull(original.getSupplierCode())==null)throw new IllegalArgumentException("多个废料候选必须具有明确供应商代码");
            var projected=new ScrapPrice();BeanUtils.copyProperties(original,projected);projected.setPriceMonth(context.month().toString());projected.setRuleSourceMonth(original.getPriceMonth());projected.setSourceRuleId(original.getId());projected.setSourceRuleRevision(original.getRuleRevision());
            var input=calculator.prepare(projected,"LINKED".equals(projected.getPriceType())?financial.get():null);
            if(calculations.putIfAbsent(input.fingerprint(),new Calculation(original,input,calculator.calculate(input)))!=null)throw new IllegalArgumentException("废料候选计算身份重复");
          }
          var candidates=calculations.entrySet().stream().map(entry->new SupplierPriceCandidate(entry.getKey(),entry.getValue().original().getSupplierCode(),entry.getValue().original().getSupplierName(),entry.getValue().price(),null,null)).toList();
          var scope=scope(context,request);var actualRatios=ratios.getOrDefault(code,List.of());
          var chosen=suppliers.selectPrepared(scope,candidates,actualRatios,decisions.get(SupplierPriceSelectionService.scopeKey(scope)));
          if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review());
          if(chosen.candidate()==null){problems.put(request,new Problem(Failure.WAITING,chosen.message()));continue;}
          var selected=calculations.get(chosen.candidate().key());
          var snapshot=new Snapshot(1,"SCRAP_PRICE",context.month().toString(),new Source(selected.original(),List.copyOf(calculations.values()),actualRatios,chosen.rule()),new Basis("KG","CNY",TaxBasis.EXCLUDED,"IDENTITY"));
          var fact=PriceFact.normal(selected.price(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
          if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review(),fact.evidence().sha256());
          var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));
          values.put(request,new Candidate(new Selection(null,coverage,fact),null));
        }catch(FinanceFactorPriceReader.MissingPublicationException waiting){problems.put(request,new Problem(Failure.WAITING,waiting.getMessage()));}
        catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"废料来源无效："+invalid.getMessage()));}
      }
    }
    return new Candidates(values,problems);
  }
  private void validateBasis(MonthlyPriceRequest request){
    if(!"KG".equals(request.family().unit()) || !"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED)throw new IllegalArgumentException("废料仅支持KG/CNY/不含税明确口径，不能猜测转换");
  }
  private SupplierPriceScope scope(Context context,MonthlyPriceRequest request){return new SupplierPriceScope(context.businessUnitType(),context.oaNo(),context.oaFormItemId(),context.month().toString(),context.priceAsOfTime().toLocalDate(),request.priceOrgCode(),request.family().materialCode(),"SCRAP_PRICE");}
  private final class FinancialInput {
    private final Context context;private boolean loaded;private FactorPriceSnapshot value;private RuntimeException failure;
    private FinancialInput(Context context){this.context=context;}
    private FactorPriceSnapshot get(){
      if(!loaded){loaded=true;try{value=finance.readDefault(new FinanceFactorImportContext(context.businessUnitType(),"210",context.month().toString(),null,null),"MONTHLY_SCRAP").financial();}catch(IllegalArgumentException | IllegalStateException invalid){failure=invalid;}}
      if(failure!=null)throw failure;return value;
    }
  }
}
