package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.TraceEntry;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.BoardPriceInputs;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/** 原稿规则运行时适配：读取一个完整财务发布，调用共享计算器，并留下实际输入证据。 */
@Service
public class OriginalLinkedPriceRuntime {
  private final OriginalLinkedRuleSnapshot snapshots;
  private final OriginalLinkedRuleCalculator calculator;
  private final FinanceFactorPriceReader finance;
  private final ObjectMapper json;

  public OriginalLinkedPriceRuntime(
      OriginalLinkedRuleSnapshot snapshots,
      OriginalLinkedRuleCalculator calculator,
      FinanceFactorPriceReader finance,
      ObjectMapper json) {
    this.snapshots = snapshots;
    this.calculator = calculator;
    this.finance = finance;
    this.json = json;
  }

  PriceFactorContext context(
      PriceLinkedItem item, String pricingMonth, BigDecimal customerCopperPerTon, OaForm oaForm) {
    var importContext =
        new FinanceFactorImportContext(
            item.getBusinessUnitType(), item.getOrgCode(), pricingMonth, null, null);
    PriceFactorContext context;
    if ("220".equals(item.getOrgCode())) {
      var board =
          new BoardPriceInputs(
              oaForm == null ? null : oaForm.getSus304Price(),
              oaForm == null ? null : oaForm.getSus316lPrice(),
              oaForm != null && Boolean.TRUE.equals(oaForm.getBoardTender()));
      context =
          finance
              .readEffective(
                  importContext,
                  board.finalTender() ? Condition.TENDER : Condition.NON_TENDER,
                  item.getMaterialCode())
              .withBoardInputs(board);
    } else {
      context = finance.readDefault(importContext, item.getMaterialCode());
    }
    if (customerCopperPerTon != null)
      context = context.withCustomerCopperPerTon(customerCopperPerTon);
    return context;
  }

  public PriceLinkedFormulaPreviewResponse preview(
      PriceLinkedItem item, String pricingMonth, BigDecimal customerCopperPerTon) {
    return preview(item, pricingMonth, customerCopperPerTon, null);
  }

  public PriceLinkedFormulaPreviewResponse preview(
      PriceLinkedItem item, String pricingMonth, BigDecimal customerCopperPerTon, OaForm oaForm) {
    return preview(item, pricingMonth, customerCopperPerTon, oaForm, null, null);
  }

  /** 正常报价复用发布结果；仅实际使用的客户因素变化时才计算报价专属单价。 */
  public PriceLinkedFormulaPreviewResponse previewSaved(
      PriceLinkedItem item,
      String pricingMonth,
      BigDecimal customerCopperPerTon,
      OaForm oaForm,
      FinanceEffectivePrice effective,
      FinanceEffectivePriceService.Calculation saved) {
    java.util.Objects.requireNonNull(effective, "财务有效价缺失");
    java.util.Objects.requireNonNull(saved, "财务有效价依据缺失");
    return preview(item, pricingMonth, customerCopperPerTon, oaForm, effective, saved);
  }

  private PriceLinkedFormulaPreviewResponse preview(
      PriceLinkedItem item,
      String pricingMonth,
      BigDecimal customerCopperPerTon,
      OaForm oaForm,
      FinanceEffectivePrice effective,
      FinanceEffectivePriceService.Calculation saved) {
    var response = new PriceLinkedFormulaPreviewResponse();
    try {
      var rule = saved == null ? snapshots.read(item) : saved.linkedRule();
      if (rule == null) throw new IllegalArgumentException("联动有效价缺少发布时绑定的原稿规则");
      PriceFactorContext context =
          saved == null
              ? context(item, pricingMonth, customerCopperPerTon, oaForm)
              : quoteContext(saved.context(), pricingMonth, customerCopperPerTon, oaForm);
      var result =
          saved == null || changedInputs(rule, saved.linked(), context)
              ? calculator.calculate(rule, context)
              : saved.linked();
      response.setNormalizedExpr(rule.compiled().expression());
      response.setResult(result.priceExcludingTax());
      for (var entry : result.inputs().entrySet()) {
        var input = rule.factors().get(entry.getKey());
        var detail = new VariableDetail();
        detail.setCode(entry.getKey());
        detail.setName(input.canonicalKey());
        detail.setValue(entry.getValue());
        detail.setSource(context.inputSource(input.canonicalKey()));
        response.getVariables().add(detail);
      }
      var evidence = new LinkedHashMap<String, Object>();
      if (effective != null) {
        evidence.put("effectivePriceId", effective.getId());
        evidence.put("effectivePriceFingerprint", effective.getInputFingerprint());
      }
      evidence.put("calculatorVersion", OriginalLinkedRuleSnapshot.VERSION);
      evidence.put("ruleId", item.getId());
      evidence.put("ruleRevision", item.getRuleRevision());
      evidence.put("ruleIdentity", rule.identity());
      evidence.put("ruleMonth", item.getPricingMonth());
      evidence.put("sourceUploadBatchId", item.getSourceUploadBatchId());
      evidence.put("sourceSheet", rule.sheet());
      evidence.put("sourceCell", rule.cell());
      evidence.put("sourceFormula", rule.sourceFormula());
      evidence.put("expression", rule.compiled().expression());
      evidence.put("numberFormat", rule.numberFormat());
      evidence.put("sourceTaxIncluded", rule.taxIncluded());
      evidence.put(
          "context",
          context.evidenceForFactors(
              rule.factors().values().stream()
                  .map(factor -> factor.canonicalKey())
                  .collect(
                      java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))));
      evidence.put("factorBindings", rule.factors());
      evidence.put("inputs", result.inputs());
      if (context.boardInputs() != null) {
        var boardFactors = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var entry : result.inputs().entrySet()) {
          var factor = rule.factors().get(entry.getKey());
          var grade =
              com.sanhua.marketingcost.service.pricing.BoardStainlessGrade.fromFactorKey(
                  factor.canonicalKey());
          if (grade != null)
            boardFactors.add(
                java.util.Map.of(
                    "grade",
                    grade.name(),
                    "factorKey",
                    factor.canonicalKey(),
                    "value",
                    entry.getValue(),
                    "unit",
                    factor.unit().name(),
                    "source",
                    context.inputSource(factor.canonicalKey())));
        }
        evidence.put(
            "boardPricing",
            java.util.Map.of(
                "finalTender",
                context.boardInputs().finalTender(),
                "factors",
                boardFactors,
                "financialMonth",
                context.months().sourceMonth()));
      }
      evidence.put("sourceResult", result.sourceResult());
      evidence.put("priceExcludingTax", result.priceExcludingTax());
      String serialized = json.writeValueAsString(evidence);
      response.setInputSnapshotJson(serialized);
      response.setCalcFingerprint(
          PriceLinkedImportFileDigest.sha256(serialized.getBytes(StandardCharsets.UTF_8)));
      response.setCalculatorVersion(OriginalLinkedRuleSnapshot.VERSION);
      response
          .getTrace()
          .add(
              new TraceEntry(
                  "finance",
                  "请求月="
                      + pricingMonth
                      + "，财务来源月="
                      + context.months().sourceMonth()
                      + "，发布="
                      + context.financial().batchId()
                      + "，修订="
                      + context.financial().revision()));
      response
          .getTrace()
          .add(
              new TraceEntry(
                  "tax", rule.taxIncluded() ? "主价格格含税，按1.13转换为不含税价" : "主价格格已是不含税价，保留原公式结果"));
    } catch (IllegalArgumentException | JsonProcessingException error) {
      response.setResult(null);
      response.setError(error.getMessage());
      response.getTrace().add(new TraceEntry("error", error.getMessage()));
    }
    return response;
  }

  private PriceFactorContext quoteContext(
      PriceFactorContext saved, String month, BigDecimal copper, OaForm oaForm) {
    var context =
        new PriceFactorContext(
            saved.materialCode(),
            new PriceMonthSelection(month, saved.months().sourceMonth()),
            saved.financial(),
            copper,
            saved.boardInputs());
    if (saved.boardInputs() != null) {
      context =
          context.withBoardInputs(
              new BoardPriceInputs(
                  oaForm == null ? null : oaForm.getSus304Price(),
                  oaForm == null ? null : oaForm.getSus316lPrice(),
                  saved.boardInputs().finalTender()));
    }
    return context;
  }

  public static boolean changedInputs(
      OriginalLinkedWorkbookParser.Rule rule,
      OriginalLinkedRuleCalculator.Result saved,
      PriceFactorContext context) {
    if (saved == null) throw new IllegalArgumentException("联动有效价缺少已发布计算结果");
    for (var entry : rule.factors().entrySet()) {
      var value =
          context.requiredPrice(entry.getValue().canonicalKey(), entry.getValue().unit(), true);
      var previous = saved.inputs().get(entry.getKey());
      if (previous == null || value.compareTo(previous) != 0) return true;
    }
    return false;
  }
}
