package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Published original-workbook linked prices; legacy variable formulas have a separate adapter. */
@Component
public final class MonthlyOriginalLinkedPriceSource {
  public record Source(String originalMonth,PriceLinkedItem selected,List<PriceLinkedItem> candidates,
      FinanceEffectivePrice selectedPublication,List<FinanceEffectivePrice> publications,
      List<SupplierSupplyRatio> ratios,String selectionRule,String calculationJson) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String taxConversion,String rounding) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private final MonthlyOriginalLinkedSourceMapper rows;
  private final MaterialPriceMonthSelector months;
  private final FinanceFactorPriceReader finance;
  private final FinanceEffectivePriceService effective;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final OriginalLinkedPriceRuntime runtime;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyOriginalLinkedPriceSource(MonthlyOriginalLinkedSourceMapper rows,MaterialPriceMonthSelector months,
      FinanceFactorPriceReader finance,FinanceEffectivePriceService effective,MonthlySupplierInputMapper supplierInputs,
      SupplierPriceSelectionService suppliers,OriginalLinkedPriceRuntime runtime,MonthlyPriceDocumentCodec codec){
    this.rows=rows;this.months=months;this.finance=finance;this.effective=effective;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.runtime=runtime;this.codec=codec;
  }
  private record PublicationInput(FactorPriceSnapshot financial,Map<Long,FinanceEffectivePrice> prices,RuntimeException error) {}
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Linked source scope differs from request");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>()).computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var financeCache=new HashMap<String,FactorPriceSnapshot>();var financeErrors=new HashMap<String,RuntimeException>();
    for(var organization:groups.entrySet()){
      var selectedMonth=months.select(context.businessUnitType(),organization.getKey(),context.month().toString());
      if(selectedMonth==null)selectedMonth=new PriceMonthSelection(context.month().toString(),context.month().toString());
      var codes=new ArrayList<>(organization.getValue().keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var byCode=new HashMap<String,List<PriceLinkedItem>>();rows.rules(context.businessUnitType(),organization.getKey(),selectedMonth.sourceMonth(),context.month().toString(),context.priceAsOfTime().toLocalDate(),batch).forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        var multiple=new TreeSet<String>();byCode.forEach((code,items)->{if(items.stream().map(PriceLinkedItem::getSupplierCode).distinct().count()>1)multiple.add(code);});
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();if(!multiple.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),multiple).forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var scopes=new HashMap<String,SupplierPriceScope>();for(String code:multiple){var scope=scope(context,organization.getKey(),code);scopes.put(SupplierPriceSelectionService.scopeKey(scope),scope);}
        var decisions=new HashMap<String,SupplierPriceDecision>();if(!scopes.isEmpty())supplierInputs.decisions(scopes.keySet()).forEach(row->decisions.put(row.getScopeKey(),row));
        var publications=new HashMap<String,PublicationInput>();
        for(String code:batch)for(var request:organization.getValue().get(code)){
          var originals=byCode.getOrDefault(code,List.of());if(originals.isEmpty()){problems.put(request,new Problem(Failure.MISSING,MaterialPriceMonthSelector.missingMessage(code,request.priceOrgCode(),selectedMonth)));continue;}
          try{
            String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
            var heads=new LinkedHashMap<String,PriceLinkedItem>();
            for(var row:originals){
              if(explicit!=null && !explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))continue;
              if(!request.family().unit().equals(row.getUnit()) || request.family().specification()!=null && !request.family().specification().equals(row.getSpecModel()))continue;
              String identity=row.getRuleIdentityKey();
              if(identity==null || identity.isBlank())identity=new MaterialPriceRuleKey(row.getBusinessUnitType(),row.getOrgCode(),row.getMaterialCode(),row.getSupplierCode(),row.getSupplierName(),row.getSpecModel(),row.getUnit()).identityKey();
              heads.putIfAbsent(identity,row);
            }
            // Historical expression versions do not invalidate the selected latest revision.
            if(heads.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"原稿没有指定供应商"));continue;}
            var applicable=heads.values().stream().filter(row->request.family().unit().equals(row.getUnit()) && (request.family().specification()==null || request.family().specification().equals(row.getSpecModel()))).toList();
            if(applicable.isEmpty() || !"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED)throw new IllegalArgumentException("原稿规格、单位、币种或税价口径不适用，不能猜测转换");
            for(var head:applicable){
              if(head.getRuleIdentityKey()==null || head.getRuleIdentityKey().isBlank())throw new IllegalArgumentException("Selected original head lacks its stored stable identity");
              if(head.getRuleRevision()==null || head.getRuleRevision()<1)throw new IllegalArgumentException("Applicable latest original rule lacks its revision");
              if(!OriginalLinkedRuleSnapshot.VERSION.equals(head.getExpressionVersion()))throw new IllegalArgumentException("Unrelated identities excluded; this applicable source requires its legacy formula adapter");
            }
            var supplierCodes=new HashSet<String>();for(var row:applicable)if(row.getSupplierCode()==null || row.getSupplierCode().isBlank() || !supplierCodes.add(row.getSupplierCode()))throw new IllegalArgumentException("同供应商存在多个规格/单位或供应商身份缺失，须明确请求身份");
            String condition=condition(request).name();
            var publication=publications.computeIfAbsent(condition,ignored->{
              String cacheKey=request.priceOrgCode()+":"+condition;
              try{
                if(financeErrors.containsKey(cacheKey))throw financeErrors.get(cacheKey);
                var financial=financeCache.get(cacheKey);
                if(financial==null){try{financial=finance.readEffective(new FinanceFactorImportContext(context.businessUnitType(),request.priceOrgCode(),context.month().toString(),null,null),condition(request),code).financial();financeCache.put(cacheKey,financial);}catch(IllegalArgumentException | IllegalStateException invalid){financeErrors.put(cacheKey,invalid);throw invalid;}}
                var ids=byCode.values().stream().flatMap(List::stream).filter(row->OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())).map(PriceLinkedItem::getId).toList();
                return new PublicationInput(financial,effective.read("LINKED",ids,financial),null);
              }catch(IllegalArgumentException | IllegalStateException invalid){return new PublicationInput(null,Map.of(),invalid);}
            });
            if(publication.error()!=null)throw publication.error();
            var prices=new LinkedHashMap<String,FinanceEffectivePrice>();boolean missing=false;
            for(var row:applicable){var saved=publication.prices().get(row.getId());var identity=FinanceEffectivePriceService.SourceRule.linked(row);if(saved==null || !identity.fingerprint().equals(saved.getRuleFingerprint()) || !Objects.equals(identity.revision(),saved.getOriginalRevision())){missing=true;break;}effective.require(identity,publication.prices());if(saved.getUnitPrice()==null || saved.getInputFingerprint()==null)throw new IllegalArgumentException("已发布联动价缺少金额或依据身份");prices.put(saved.getInputFingerprint(),saved);}
            if(missing){problems.put(request,new Problem(Failure.WAITING,"原稿/财务发布的完整联动有效价尚未就绪"));continue;}
            var candidatePrices=prices.values().stream().map(row->new SupplierPriceCandidate(row.getInputFingerprint(),row.getSupplierCode(),row.getSupplierName(),row.getUnitPrice(),null,null)).toList();
            var scope=scope(context,request.priceOrgCode(),code);var actualRatios=ratios.getOrDefault(code,List.of());var chosen=suppliers.selectPrepared(scope,candidatePrices,actualRatios,decisions.get(SupplierPriceSelectionService.scopeKey(scope)));
            if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review());
            if(chosen.candidate()==null){problems.put(request,new Problem(Failure.WAITING,chosen.message()));continue;}
            var saved=prices.get(chosen.candidate().key());var selected=applicable.stream().filter(row->row.getId().equals(saved.getOriginalId())).findFirst().orElseThrow();var calculation=effective.calculation(saved);
            OaForm inputs=null;var board=request.conditions().boardTerms();if("220".equals(request.priceOrgCode())){inputs=new OaForm();inputs.setBoardTender(board!=null && board.finalTender());inputs.setSus304Price(board==null?null:board.sus304PricePerTon());inputs.setSus316lPrice(board==null?null:board.sus316lPricePerTon());}
            var preview=runtime.previewSaved(selected,context.month().toString(),null,inputs,saved,calculation);if(preview.getResult()==null || preview.getError()!=null || preview.getInputSnapshotJson()==null)throw new IllegalArgumentException("联动计算依据无效："+preview.getError());
            var snapshot=new Snapshot(1,"LINKED",new Source(selected.getPricingMonth(),selected,applicable,saved,List.copyOf(prices.values()),actualRatios,chosen.rule(),preview.getInputSnapshotJson()),new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"ORIGINAL_V1_EXCLUDED_TAX_RESULT","FINANCE_PUBLICATION_DECIMAL_38_18_HALF_UP"));
            var fact=PriceFact.normal(preview.getResult().setScale(18,RoundingMode.HALF_UP),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
            var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),"220".equals(request.priceOrgCode())?ConditionDomain.exact(request.conditions()):ConditionDomain.any()));
            if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review(),fact.evidence().sha256());
            values.put(request,new Candidate(new Selection(null,coverage,fact),null));
          }catch(FinanceFactorPriceReader.MissingPublicationException waiting){problems.put(request,new Problem(Failure.WAITING,waiting.getMessage()));}
          catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"原稿联动来源无效："+invalid.getMessage()));}
        }
      }
    }
    return new Candidates(values,problems);
  }
  private FactorPriceScope.Condition condition(MonthlyPriceRequest request){var board=request.conditions().boardTerms();return "220".equals(request.priceOrgCode())?(board!=null && board.finalTender()?FactorPriceScope.Condition.TENDER:FactorPriceScope.Condition.NON_TENDER):FactorPriceScope.Condition.GENERAL;}
  private SupplierPriceScope scope(Context context,String organization,String code){return new SupplierPriceScope(context.businessUnitType(),context.oaNo(),context.oaFormItemId(),context.month().toString(),context.priceAsOfTime().toLocalDate(),organization,code,"LINKED");}
}
