package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sanhua.marketingcost.formula.registry.FinanceFactorBinding;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Legacy financial factor bands; keeps the existing supplier and band calculation. */
@Component
public final class MonthlyLegacyRangePriceSource {
  public record FactorBinding(String canonicalKey,PriceVariable variable,FactorIdentity identity) {}
  public record SupplierProof(String mode,int candidateSupplierCount,String supplierCode,String supplierName,
      Long ratioRecordId,BigDecimal ratio,String message) {}
  public record Source(String originalMonth,PriceRangeFactorRule selectedRule,List<PriceRangeFactorRule> candidateRules,
      List<PriceRangeItem> bands,String supplierRatioMaterialCode,List<SupplierSupplyRatio> ratios,
      SupplierProof supplier,FactorBinding factorBinding,PriceFactorContext financialInputs,
      BigDecimal factorValue,PriceResolveResult calculation) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String priceColumn,String conversion) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private final MonthlyLegacyRangeSourceMapper rows;
  private final MaterialPriceMonthSelector months;
  private final FinanceFactorPriceReader finance;
  private final FactorCanonicalKeyService keys;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPreferredPriceSelector suppliers;
  private final RangePriceResolver engine;
  private final MonthlyPriceDocumentCodec codec;
  private final ObjectMapper json;
  public MonthlyLegacyRangePriceSource(MonthlyLegacyRangeSourceMapper rows,MaterialPriceMonthSelector months,
      FinanceFactorPriceReader finance,FactorCanonicalKeyService keys,MonthlySupplierInputMapper supplierInputs,
      SupplierPreferredPriceSelector suppliers,RangePriceResolver engine,MonthlyPriceDocumentCodec codec,ObjectMapper json){
    this.rows=rows;this.months=months;this.finance=finance;this.keys=keys;this.supplierInputs=supplierInputs;
    this.suppliers=suppliers;this.engine=engine;this.codec=codec;this.json=json;
  }
  private record Financial(FactorPriceSnapshot value,RuntimeException failure) {}
  private record BindingInput(PriceVariable variable,Map<String,Object> params,RuntimeException failure) {}
  private final class Bindings {
    private final String unit;
    private List<PriceVariable> variables;
    private final Map<String,BindingInput> inputs=new HashMap<>();
    private final Map<Long,FactorIdentity> identities=new HashMap<>();
    private final Set<Long> capturedIds=new HashSet<>();
    Bindings(String unit){this.unit=unit;}
    void capture(Collection<PriceRangeFactorRule> rules){
      var aliases=rules.stream().map(PriceRangeFactorRule::getFactorCode).filter(raw->raw!=null && !raw.isBlank() && directKey(raw)==null).distinct().toList();
      if(aliases.isEmpty())return;
      if(variables==null)variables=rows.variables(unit);
      var required=new TreeSet<Long>();
      for(String raw:aliases){
        var input=inputs.computeIfAbsent(raw,this::captureInput);
        if(input.failure()==null){Long id=FinanceFactorBinding.identityId(input.params());if(id!=null && capturedIds.add(id))required.add(id);}
      }
      var all=new ArrayList<>(required);
      for(int start=0;start<all.size();start+=256)rows.identities(unit,all.subList(start,Math.min(start+256,all.size()))).forEach(row->identities.put(row.getId(),row));
    }
    private BindingInput captureInput(String raw){
      try{
        var matching=variables.stream().filter(row->raw.equals(row.getVariableCode())).toList();
        if(matching.isEmpty())matching=variables.stream().filter(row->raw.equalsIgnoreCase(row.getVariableCode())).toList();
        if(matching.size()!=1 || !"FINANCE".equals(matching.getFirst().getResolverKind()))throw new IllegalArgumentException("旧行情因素没有唯一有效的财务变量绑定："+raw);
        var variable=matching.getFirst();var tree=json.readTree(variable.getResolverParams());
        if(tree==null || !tree.isObject())throw new IllegalArgumentException("财务变量绑定参数缺失："+raw);
        Map<String,Object> params=json.convertValue(tree,new TypeReference<Map<String,Object>>(){});
        FinanceFactorBinding.identityId(params); // Validate without truncating fractional or overflowing IDs.
        return new BindingInput(variable,params,null);
      }catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid){return new BindingInput(null,Map.of(),new IllegalArgumentException("财务变量绑定无效："+raw+"："+invalid.getMessage(),invalid));}
    }
    FactorBinding resolve(String raw){
      if(raw==null || raw.isBlank())throw new IllegalArgumentException("旧行情规则缺少因素身份");
      String direct=directKey(raw);if(direct!=null)return new FactorBinding(direct,null,null);
      var input=inputs.get(raw);if(input==null)throw new IllegalStateException("财务变量没有捕获："+raw);
      if(input.failure()!=null)throw input.failure();
      Long id=FinanceFactorBinding.identityId(input.params());var identity=id==null?null:identities.get(id);
      return new FactorBinding(FinanceFactorBinding.canonicalKey(input.params(),unit,identity,keys),input.variable(),identity);
    }
    private String directKey(String raw){
      if(raw.contains("|"))return keys.normalizeExistingKey(raw);
      return "CU".equalsIgnoreCase(raw) || "1#CU".equalsIgnoreCase(raw)?PriceFactorContext.COPPER:null;
    }
  }
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Legacy factor source scope differs from request");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>()).computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var financeCache=new HashMap<String,Financial>();var bindings=new Bindings(context.businessUnitType());
    for(var organization:groups.entrySet()){
      var selectedMonth=months.select(context.businessUnitType(),organization.getKey(),context.month().toString());
      var codes=new ArrayList<>(organization.getValue().keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=codes.subList(start,Math.min(start+256,codes.size()));var byCode=new HashMap<String,List<PriceRangeFactorRule>>();
        rows.rules(context.businessUnitType(),organization.getKey(),batch).forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        bindings.capture(byCode.values().stream().flatMap(List::stream).toList());
        var ids=byCode.values().stream().flatMap(List::stream).filter(row->!OriginalRangeRuleSnapshot.isOriginal(row)).map(PriceRangeFactorRule::getId).distinct().toList();
        var bands=new HashMap<Long,List<PriceRangeItem>>();if(!ids.isEmpty())rows.bands(ids).forEach(row->bands.computeIfAbsent(row.getFactorRuleId(),ignored->new ArrayList<>()).add(row));
        var ratioCodes=byCode.keySet().stream().map(SupplierSupplyRatioNormalizeUtils::normalizeToNull).collect(java.util.stream.Collectors.toSet());
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();if(!ratioCodes.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),ratioCodes).forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var primary=suppliers.prepareSuppliers(context.businessUnitType(),byCode.keySet(),context.priceAsOfTime().toLocalDate(),ratios);
        for(String code:batch)for(var request:organization.getValue().get(code))try{
          var original=byCode.getOrDefault(code,List.of());boolean dated=original.stream().anyMatch(row->row.getPricingMonth()!=null && !row.getPricingMonth().isBlank());
          var candidates=selectRules(request,original,dated,selectedMonth);
          if(candidates.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"当前月份没有适用的旧行情规则"));continue;}
          if(candidates.stream().allMatch(OriginalRangeRuleSnapshot::isOriginal)){problems.put(request,new Problem(Failure.MISSING,"原稿行情由原稿适配器处理"));continue;}
          if(candidates.stream().anyMatch(OriginalRangeRuleSnapshot::isOriginal))throw new IllegalArgumentException("同一有效范围混有不同行情表达式契约");
          if(!"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED)throw new IllegalArgumentException("旧行情成本价使用CNY不含税价，不能猜测转换");
          String ratioCode=SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);
          var prepared=primary.getOrDefault(ratioCode,SupplierSupplyRatioResolveResult.miss("没有有效主供应商"));
          SupplierPreferredPriceSelection<PriceRangeItem> selectedSupplier;PriceRangeFactorRule selectedRule;
          if(dated){
            var chosen=suppliers.select(candidates,context.businessUnitType(),code,candidates.getFirst().getMaterialName(),candidates.getFirst().getSpecModel(),context.priceAsOfTime().toLocalDate(),PriceRangeFactorRule::getSupplierName,PriceRangeFactorRule::getSupplierCode,prepared);
            if(chosen.failed() || chosen.row()==null){problems.put(request,new Problem(Failure.WAITING,chosen.failureMessage()));continue;}
            selectedRule=chosen.row();selectedSupplier=new SupplierPreferredPriceSelection<>(null,chosen.traceMessage(),chosen.candidateSupplierCount(),chosen.mainSupplierName(),chosen.mainSupplierCode(),chosen.supplyRatio(),chosen.supplyRatioRecordId(),chosen.matchMode(),chosen.failureCode(),chosen.failureMessage());
          }else{
            selectedRule=candidates.getFirst();var available=applicableBands(context,request,selectedRule,bands.getOrDefault(selectedRule.getId(),List.of()),false);
            selectedSupplier=suppliers.select(available,context.businessUnitType(),code,selectedRule.getMaterialName(),selectedRule.getSpecModel(),context.priceAsOfTime().toLocalDate(),PriceRangeItem::getSupplierName,PriceRangeItem::getSupplierCode,prepared);
            if(selectedSupplier.failed()){problems.put(request,new Problem(Failure.WAITING,selectedSupplier.failureMessage()));continue;}
          }
          var available=applicableBands(context,request,selectedRule,bands.getOrDefault(selectedRule.getId(),List.of()),dated);
          if(available.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"选定旧行情规则没有适用档位"));continue;}
          var binding=bindings.resolve(selectedRule.getFactorCode());var condition=condition(request);
          String financialKey=request.priceOrgCode()+":"+condition;
          var financial=financeCache.computeIfAbsent(financialKey,ignored->{try{return new Financial(finance.readEffective(new FinanceFactorImportContext(context.businessUnitType(),request.priceOrgCode(),context.month().toString(),null,null),condition,code).financial(),null);}catch(IllegalArgumentException | IllegalStateException invalid){return new Financial(null,invalid);}});
          if(financial.failure()!=null)throw financial.failure();
          var factors=new PriceFactorContext(code,new PriceMonthSelection(context.month().toString(),financial.value().scope().priceMonth()),financial.value(),null);
          var board=request.conditions().boardTerms();if("220".equals(request.priceOrgCode()) && board!=null)factors=factors.withBoardInputs(new BoardPriceInputs(board.sus304PricePerTon(),board.sus316lPricePerTon(),board.finalTender()));
          BigDecimal factor=factors.requiredPrice(binding.canonicalKey(),FactorPriceUnit.require(selectedRule.getFactorUnit()),true);
          var result=engine.resolveFactorPrepared(selectedRule,available,factor,context.priceAsOfTime().toLocalDate(),selectedSupplier);
          if(result.unitPrice()==null){problems.put(request,new Problem(result.hasBlockingFailure()?Failure.INVALID_INPUT:Failure.MISSING,result.remark()));continue;}
          var proof=new SupplierProof(selectedSupplier.matchMode(),selectedSupplier.candidateSupplierCount(),selectedSupplier.mainSupplierCode(),selectedSupplier.mainSupplierName(),selectedSupplier.supplyRatioRecordId(),selectedSupplier.supplyRatio(),selectedSupplier.traceMessage());
          var rawBands=candidates.stream().flatMap(row->bands.getOrDefault(row.getId(),List.of()).stream()).toList();
          var snapshot=new Snapshot(1,"RANGE_LEGACY_FACTOR",new Source(selectedRule.getPricingMonth(),selectedRule,candidates,rawBands,ratioCode,ratios.getOrDefault(ratioCode,List.of()),proof,binding,factors.evidenceForFactors(Set.of(binding.canonicalKey())),factor,result),new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"price_excl_tax","IDENTITY"));
          var fact=PriceFact.normal(result.unitPrice(),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
          var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),"220".equals(request.priceOrgCode())?ConditionDomain.exact(request.conditions()):ConditionDomain.any()));
          values.put(request,new Candidate(new Selection(null,coverage,fact),null));
        }catch(FinanceFactorPriceReader.MissingPublicationException waiting){problems.put(request,new Problem(Failure.WAITING,waiting.getMessage()));}
        catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"旧行情来源无效："+invalid.getMessage()));}
      }
    }
    return new Candidates(values,problems);
  }
  private List<PriceRangeFactorRule> selectRules(MonthlyPriceRequest request,List<PriceRangeFactorRule> original,boolean dated,PriceMonthSelection month){
    if(dated && month==null)return List.of();
    var eligible=dated?original.stream().filter(row->month.sourceMonth().equals(row.getPricingMonth())).toList():original.stream().filter(row->Integer.valueOf(1).equals(row.getCurrentFlag())).limit(1).toList();
    String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    var heads=new LinkedHashMap<String,PriceRangeFactorRule>();
    for(var row:eligible){
      if(!request.family().unit().equals(row.getPriceUnit()) || request.family().specification()!=null && !request.family().specification().equals(row.getSpecModel()))continue;
      if(dated && explicit!=null && !explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))continue;
      String identity=row.getRuleIdentityKey();
      if(identity==null || identity.isBlank())identity=dated?new MaterialPriceRuleKey(row.getBusinessUnitType(),row.getOrgCode(),row.getMaterialCode(),row.getSupplierCode(),row.getSupplierName(),row.getSpecModel(),row.getPriceUnit()).identityKey():Long.toString(row.getId());
      heads.putIfAbsent(identity,row);
    }
    var seen=new HashSet<String>();for(var row:heads.values())if(dated && !seen.add(supplierKey(row.getSupplierName(),row.getSupplierCode())))throw new IllegalArgumentException("同供应商存在多个适用规则身份，须明确规格和单位");
    return List.copyOf(heads.values());
  }
  private List<PriceRangeItem> applicableBands(Context context,MonthlyPriceRequest request,PriceRangeFactorRule rule,List<PriceRangeItem> original,boolean dated){
    String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
    return original.stream().filter(row->dated || row.getEffectiveFrom()==null || !row.getEffectiveFrom().isAfter(context.priceAsOfTime().toLocalDate()))
        .filter(row->request.family().unit().equals(row.getUnit()) && (request.family().specification()==null || request.family().specification().equals(row.getSpecModel())))
        .filter(row->explicit==null || explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode()))).toList();
  }
  private static String supplierKey(String name,String code){String normalized=SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);return normalized==null?"NAME:"+SupplierSupplyRatioNormalizeUtils.normalizeToNull(name):"CODE:"+normalized;}
  private FactorPriceScope.Condition condition(MonthlyPriceRequest request){var board=request.conditions().boardTerms();return "220".equals(request.priceOrgCode())?(board!=null && board.finalTender()?FactorPriceScope.Condition.TENDER:FactorPriceScope.Condition.NON_TENDER):FactorPriceScope.Condition.GENERAL;}
}
