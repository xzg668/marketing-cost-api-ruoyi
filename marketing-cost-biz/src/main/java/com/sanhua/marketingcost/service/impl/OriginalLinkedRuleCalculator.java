package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule;
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
