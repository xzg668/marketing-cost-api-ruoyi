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

/** Published original factor bands; queries reuse their complete financial results without generating prices. */
@Component
public final class MonthlyOriginalRangePriceSource {
  public record SourceBatch(Long id,String batchNo,String fileName,String fileSha256,String contentHash,
      String parserVersion,String importType,String businessUnit,String priceMonth,String status) {
    static SourceBatch of(FactorUploadBatch row){return new SourceBatch(row.getId(),row.getBatchNo(),row.getFileName(),row.getFileSha256(),row.getContentHash(),row.getParserVersion(),row.getImportType(),row.getBusinessUnitType(),row.getPriceMonth(),row.getStatus());}
  }
  public record Source(String originalMonth,PriceRangeFactorRule selected,List<PriceRangeFactorRule> candidates,
      List<PriceRangeItem> bands,List<SourceBatch> originalBatches,
      FinanceEffectivePrice selectedPublication,List<FinanceEffectivePrice> publications,
      List<SupplierSupplyRatio> ratios,String selectionRule,String calculationJson,Long selectedBandId) {}
  public record Basis(String unit,String currency,TaxBasis taxBasis,String taxConversion,String rounding) {}
  public record Snapshot(int snapshotSchema,String sourceKind,Source source,Basis financialBasis) {}
  private final MonthlyOriginalRangeSourceMapper rows;
  private final MaterialPriceMonthSelector months;
  private final FinanceFactorPriceReader finance;
  private final FinanceEffectivePriceService effective;
  private final MonthlySupplierInputMapper supplierInputs;
  private final SupplierPriceSelectionService suppliers;
  private final OriginalRangePriceRuntime runtime;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyOriginalRangePriceSource(MonthlyOriginalRangeSourceMapper rows,MaterialPriceMonthSelector months,
      FinanceFactorPriceReader finance,FinanceEffectivePriceService effective,MonthlySupplierInputMapper supplierInputs,
      SupplierPriceSelectionService suppliers,OriginalRangePriceRuntime runtime,MonthlyPriceDocumentCodec codec){
    this.rows=rows;this.months=months;this.finance=finance;this.effective=effective;this.supplierInputs=supplierInputs;this.suppliers=suppliers;this.runtime=runtime;this.codec=codec;
  }
  private record PublicationInput(FactorPriceSnapshot financial,Map<Long,FinanceEffectivePrice> prices,RuntimeException error) {}
  public Candidates read(Context context,Set<MonthlyPriceRequest> requests){
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,Map<String,List<MonthlyPriceRequest>>>();
    for(var request:requests){
      if(!context.businessUnitType().equals(request.family().businessUnitType()) || !context.month().equals(request.family().month()))throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Factor-range source scope differs from request");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new TreeMap<>()).computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request);
    }
    var financeCache=new HashMap<String,FactorPriceSnapshot>();var financeErrors=new HashMap<String,RuntimeException>();
    for(var organization:groups.entrySet()){
      var selectedMonth=months.select(context.businessUnitType(),organization.getKey(),context.month().toString());
      if(selectedMonth==null){organization.getValue().values().forEach(items->items.forEach(request->problems.put(request,new Problem(Failure.MISSING,"当前范围没有适用的原稿月份"))));continue;}
      var codes=new ArrayList<>(organization.getValue().keySet());
      for(int start=0;start<codes.size();start+=256){
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var byCode=new HashMap<String,List<PriceRangeFactorRule>>();rows.rules(context.businessUnitType(),organization.getKey(),selectedMonth.sourceMonth(),batch).forEach(row->byCode.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
        var originalIds=byCode.values().stream().flatMap(List::stream).filter(OriginalRangeRuleSnapshot::isOriginal).map(PriceRangeFactorRule::getId).distinct().toList();
        var bands=new HashMap<Long,List<PriceRangeItem>>();if(!originalIds.isEmpty())rows.bands(originalIds).forEach(row->bands.computeIfAbsent(row.getFactorRuleId(),ignored->new ArrayList<>()).add(row));
        var batchIds=byCode.values().stream().flatMap(List::stream).map(PriceRangeFactorRule::getSourceUploadBatchId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        var originalBatches=new HashMap<Long,SourceBatch>();if(!batchIds.isEmpty())rows.batches(batchIds).forEach(row->originalBatches.put(row.getId(),SourceBatch.of(row)));
        var multiple=new TreeSet<String>();byCode.forEach((code,items)->{if(items.stream().map(PriceRangeFactorRule::getSupplierCode).distinct().count()>1)multiple.add(code);});
        var ratios=new HashMap<String,List<SupplierSupplyRatio>>();if(!multiple.isEmpty())supplierInputs.ratios(context.businessUnitType(),context.priceAsOfTime().toLocalDate(),multiple).forEach(row->ratios.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row));
        var scopes=new HashMap<String,SupplierPriceScope>();for(String code:multiple){var scope=scope(context,organization.getKey(),code);scopes.put(SupplierPriceSelectionService.scopeKey(scope),scope);}
        var decisions=new HashMap<String,SupplierPriceDecision>();if(!scopes.isEmpty())supplierInputs.decisions(scopes.keySet()).forEach(row->decisions.put(row.getScopeKey(),row));
        var publications=new HashMap<String,PublicationInput>();
        for(String code:batch)for(var request:organization.getValue().get(code)){
          var originals=byCode.getOrDefault(code,List.of());if(originals.isEmpty()){problems.put(request,new Problem(Failure.MISSING,MaterialPriceMonthSelector.missingMessage(code,request.priceOrgCode(),selectedMonth)));continue;}
          try{
            String explicit=SupplierSupplyRatioNormalizeUtils.normalizeToNull(request.family().requestedSupplierCode());
            var heads=new LinkedHashMap<String,PriceRangeFactorRule>();
            for(var row:originals){
              if(explicit!=null && !explicit.equals(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode())))continue;
              if(!request.family().unit().equals(row.getPriceUnit()) || request.family().specification()!=null && !request.family().specification().equals(row.getSpecModel()))continue;
              String identity=row.getRuleIdentityKey();
              if(identity==null || identity.isBlank())identity=new MaterialPriceRuleKey(row.getBusinessUnitType(),row.getOrgCode(),row.getMaterialCode(),row.getSupplierCode(),row.getSupplierName(),row.getSpecModel(),row.getPriceUnit()).identityKey();
              heads.putIfAbsent(identity,row);
            }
            // Historical expression versions do not invalidate the selected latest revision.
            if(heads.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"原稿没有指定供应商"));continue;}
            var applicable=heads.values().stream().filter(row->request.family().unit().equals(row.getPriceUnit()) && (request.family().specification()==null || request.family().specification().equals(row.getSpecModel()))).toList();
            if(applicable.isEmpty() || !"CNY".equals(request.family().currency()) || request.family().taxBasis()!=TaxBasis.EXCLUDED)throw new IllegalArgumentException("原稿规格、单位、币种或税价口径不适用，不能猜测转换");
            for(var head:applicable){
              if(head.getRuleIdentityKey()==null || head.getRuleIdentityKey().isBlank())throw new IllegalArgumentException("Selected original head lacks its stored stable identity");
              if(head.getVersionNo()==null || head.getVersionNo()<1)throw new IllegalArgumentException("Applicable latest original rule lacks its revision");
              if(!OriginalRangeRuleSnapshot.isOriginal(head))throw new IllegalArgumentException("Unrelated identities excluded; this applicable source requires its legacy formula adapter");
            }
            var supplierCodes=new HashSet<String>();for(var row:applicable)if(row.getSupplierCode()==null || row.getSupplierCode().isBlank() || !supplierCodes.add(row.getSupplierCode()))throw new IllegalArgumentException("同供应商存在多个规格/单位或供应商身份缺失，须明确请求身份");
            String condition=condition(request).name();
            var publication=publications.computeIfAbsent(condition,ignored->{
              String cacheKey=request.priceOrgCode()+":"+condition;
              try{
                if(financeErrors.containsKey(cacheKey))throw financeErrors.get(cacheKey);
                var financial=financeCache.get(cacheKey);
                if(financial==null){try{financial=finance.readEffective(new FinanceFactorImportContext(context.businessUnitType(),request.priceOrgCode(),context.month().toString(),null,null),condition(request),code).financial();financeCache.put(cacheKey,financial);}catch(IllegalArgumentException | IllegalStateException invalid){financeErrors.put(cacheKey,invalid);throw invalid;}}
                var ids=byCode.values().stream().flatMap(List::stream).filter(OriginalRangeRuleSnapshot::isOriginal).map(PriceRangeFactorRule::getId).toList();
                return new PublicationInput(financial,effective.read("RANGE",ids,financial),null);
              }catch(IllegalArgumentException | IllegalStateException invalid){return new PublicationInput(null,Map.of(),invalid);}
            });
            if(publication.error()!=null)throw publication.error();
            var prices=new LinkedHashMap<String,FinanceEffectivePrice>();boolean missing=false;
            for(var row:applicable){var saved=publication.prices().get(row.getId());var identity=FinanceEffectivePriceService.SourceRule.range(row);if(saved==null || !identity.fingerprint().equals(saved.getRuleFingerprint()) || !Objects.equals(identity.revision(),saved.getOriginalRevision())){missing=true;break;}effective.require(identity,publication.prices());if(saved.getUnitPrice()==null || saved.getInputFingerprint()==null)throw new IllegalArgumentException("已发布联动价缺少金额或依据身份");prices.put(saved.getInputFingerprint(),saved);}
            if(missing){problems.put(request,new Problem(Failure.WAITING,"原稿/财务发布的完整联动有效价尚未就绪"));continue;}
            var candidatePrices=prices.values().stream().map(row->new SupplierPriceCandidate(row.getInputFingerprint(),row.getSupplierCode(),row.getSupplierName(),row.getUnitPrice(),null,null)).toList();
            var scope=scope(context,request.priceOrgCode(),code);var actualRatios=ratios.getOrDefault(code,List.of());var chosen=suppliers.selectPrepared(scope,candidatePrices,actualRatios,decisions.get(SupplierPriceSelectionService.scopeKey(scope)));
            if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review());
            if(chosen.candidate()==null){problems.put(request,new Problem(Failure.WAITING,chosen.message()));continue;}
            var saved=prices.get(chosen.candidate().key());var selected=applicable.stream().filter(row->row.getId().equals(saved.getOriginalId())).findFirst().orElseThrow();var calculation=effective.calculation(saved);
            var usedBatches=new LinkedHashMap<Long,SourceBatch>();for(var candidate:applicable){var original=originalBatches.get(candidate.getSourceUploadBatchId());if(original==null || !context.businessUnitType().equals(original.businessUnit()) || !candidate.getPricingMonth().equals(original.priceMonth()))throw new IllegalArgumentException("Original factor workbook batch is missing or belongs to another scope");usedBatches.put(original.id(),original);}
            var run=new com.sanhua.marketingcost.dto.CostRunContext();run.setOaNo(context.oaNo());run.setOaFormItemId(context.oaFormItemId());run.setBusinessUnitType(context.businessUnitType());run.setPriceOrgCode(request.priceOrgCode());run.setPricingMonth(context.month().toString());run.setPriceAsOfTime(context.priceAsOfTime());run.setPriceScenarioType("FINANCE_QUOTE_BASE");
            var selectedRatio=chosen.ratio();var selectedSupplier=new SupplierPreferredPriceSelection<>(selected,chosen.message(),applicable.size(),selected.getSupplierName(),selected.getSupplierCode(),selectedRatio==null?null:selectedRatio.getSupplyRatio(),selectedRatio==null?null:selectedRatio.getId(),chosen.rule(),null,null);
            var result=runtime.resolveSaved(selected,context.oaNo(),run,selectedSupplier,new OriginalRangePriceRuntime.PreparedInputs(null,bands),saved,calculation);
            if(result.unitPrice()==null || result.hasBlockingFailure() || result.evidence().inputSnapshotJson()==null || result.resultRefId()==null || result.resultRefId()<=0)throw new IllegalArgumentException("Original factor result or selected band evidence is incomplete: "+result.remark());
            var sourceBands=applicable.stream().flatMap(row->bands.getOrDefault(row.getId(),List.of()).stream()).toList();
            var snapshot=new Snapshot(1,"RANGE_FACTOR",new Source(selectedMonth.sourceMonth(),selected,applicable,sourceBands,List.copyOf(usedBatches.values()),saved,List.copyOf(prices.values()),actualRatios,chosen.rule(),result.evidence().inputSnapshotJson(),result.resultRefId()),new Basis(request.family().unit(),"CNY",TaxBasis.EXCLUDED,"ORIGINAL_RANGE_EXCLUDED_TAX_RESULT","FINANCE_PUBLICATION_DECIMAL_38_18_HALF_UP"));
            var fact=PriceFact.normal(result.unitPrice().setScale(18,RoundingMode.HALF_UP),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
            var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any()));
            if(chosen.review()!=null)context.supplierReviews().forOrganization(request.priceOrgCode()).add(chosen.review(),fact.evidence().sha256());
            values.put(request,new Candidate(new Selection(null,coverage,fact),null));
          }catch(FinanceFactorPriceReader.MissingPublicationException waiting){problems.put(request,new Problem(Failure.WAITING,waiting.getMessage()));}
          catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"原稿联动来源无效："+invalid.getMessage()));}
        }
      }
    }
    return new Candidates(values,problems);
  }
  // Existing range P0 reads the default financial scheme; no OA/customer factor lookup occurs here.
  private FactorPriceScope.Condition condition(MonthlyPriceRequest request){return "220".equals(request.priceOrgCode())?FactorPriceScope.Condition.NON_TENDER:FactorPriceScope.Condition.GENERAL;}
  private SupplierPriceScope scope(Context context,String organization,String code){return new SupplierPriceScope(context.businessUnitType(),context.oaNo(),context.oaFormItemId(),context.month().toString(),context.priceAsOfTime().toLocalDate(),organization,code,"RANGE");}
}
