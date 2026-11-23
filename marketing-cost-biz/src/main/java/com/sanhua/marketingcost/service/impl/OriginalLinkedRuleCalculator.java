package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 执行原稿的完整表达式；主价格格已是不含税时不再重复除税。 */
@Component
public final class OriginalLinkedRuleCalculator {
  private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.13");
  private final OriginalLinkedPublicationSource sources;
  private final FinanceFactorPriceReader finance;

  public OriginalLinkedRuleCalculator() {
    this(null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public OriginalLinkedRuleCalculator(OriginalLinkedPublicationSource sources, FinanceFactorPriceReader finance) {
    this.sources = sources;
    this.finance = finance;
  }

  public record QuotationInputs(Rule rule, PriceFactorContext context) {}

  /** Restore proven bindings in memory and retain the exact adopted financial result. */
  public static QuotationInputs quotationInputs(OriginalLinkedRuleCalculator calculator,
      PriceLinkedItem item, Rule saved, PriceFactorContext context) {
    if (calculator.sources == null || item.getSourceUploadBatchId() == null)
      return new QuotationInputs(saved, context);
    return calculator.restoreQuotationInputs(item, saved, context);
  }

  private QuotationInputs restoreQuotationInputs(PriceLinkedItem item, Rule saved, PriceFactorContext context) {
    Rule restored = sources.restoreQuotationFactors(item, saved);
    if (restored == saved) return new QuotationInputs(saved, context);
    var captured = context.financial();
    if (restored.factors().values().stream().anyMatch(factor -> !captured.factors().containsKey(factor.canonicalKey()))) {
      var scope = captured.scope();
      var full = finance.readCaptured(captured.batchId(), captured.revision(), scope.businessUnitType(),
          scope.priceOrgCode(), scope.priceMonth(), scope.schemeCode(), scope.condition());
      for (var entry : captured.factors().entrySet()) {
        var prior = entry.getValue();
        var source = full.required(entry.getKey());
        if (!java.util.Objects.equals(source.monthlyPriceId(), prior.monthlyPriceId())
            || source.price().compareTo(prior.price()) != 0 || !source.unit().equals(prior.unit())
            || source.taxIncluded() != prior.taxIncluded())
          throw new IllegalArgumentException("保存的财务因素与原发布不一致：" + entry.getKey());
      }
      var restoredContext = new PriceFactorContext(context.materialCode(), context.months(), full,
          context.customerCopperPricePerTon(), context.boardInputs(), context.customerZincPricePerTon(), context.customerMaterialPrices());
      return checkedQuotationInputs(saved, restored, context, restoredContext);
    }
    return checkedQuotationInputs(saved, restored, context, context);
  }

  private QuotationInputs checkedQuotationInputs(Rule saved, Rule restored, PriceFactorContext before,
      PriceFactorContext after) {
    var original = calculate(saved, before.financialOnly()).priceExcludingTax();
    var rebound = calculate(restored, after.financialOnly()).priceExcludingTax();
    if (original.compareTo(rebound) != 0)
      throw new IllegalArgumentException("原稿因素重新绑定后的财务单价与保存月价不一致：" + saved.identity().materialCode());
    return new QuotationInputs(restored, after);
  }

  public Result calculate(Rule rule, PriceFactorContext context) {
    var identity = rule.identity();
    var scope = context.financial().scope();
    if (!identity.materialCode().equals(context.materialCode())
        || !identity.businessUnitType().equals(scope.businessUnitType())
        || !identity.orgCode().equals(scope.priceOrgCode())) {
      throw new IllegalArgumentException("原稿规则与财务因素的料号/业务单元/组织不一致：" + identity.materialCode());
    }
    Map<String, BigDecimal> inputs = new LinkedHashMap<>();
    try {
      rule.factors()
          .forEach(
              (variable, factor) ->
                  inputs.put(
                      variable, context.requiredPrice(factor.canonicalKey(), factor.unit(), true)));
      BigDecimal sourceResult = ExpressionEvaluator.evaluate(rule.compiled().expression(), inputs);
      if (sourceResult == null) throw new IllegalArgumentException("公式计算结果为空");
      BigDecimal priceExcludingTax =
          rule.taxIncluded()
              ? sourceResult.divide(VAT_DIVISOR, MathContext.DECIMAL128)
              : sourceResult;
      return new Result(sourceResult, priceExcludingTax, Collections.unmodifiableMap(inputs));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "物料="
              + identity.materialCode()
              + "，"
              + rule.sheet()
              + "!"
              + rule.cell()
              + "："
              + error.getMessage(),
          error);
    }
  }

  public Pair calculatePair(
      Rule rule, PriceFactorContext financial, BigDecimal customerCopperPerTon) {
    PriceFactorContext p0 = financial.financialOnly();
    PriceFactorContext p1 =
        customerCopperPerTon == null ? p0 : p0.withCustomerCopperPerTon(customerCopperPerTon);
    return new Pair(calculate(rule, p0), calculate(rule, p1));
  }

  public record Result(
      BigDecimal sourceResult, BigDecimal priceExcludingTax, Map<String, BigDecimal> inputs) {}

  public record Pair(Result financial, Result customer) {}
}
