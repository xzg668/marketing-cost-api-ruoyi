package com.sanhua.marketingcost.service.scrap;

import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** 只计算明确规则和不可变财务输入；客户Cu场景也复用此计算器，不修改公共月价。 */
@Component
public class ScrapPriceCalculator {
  public static final String VERSION = "SCRAP_PRICE_V1";
  private final ScrapPriceRuleCodec rules;

  public ScrapPriceCalculator(ScrapPriceRuleCodec rules) {
    this.rules = rules;
  }

  public record Input(
      ScrapPriceRule rule,
      Map<String, BigDecimal> variables,
      PriceFactorContext factors,
      String fingerprint,
      String evidenceJson) {}

  public Input prepare(ScrapPrice row, FactorPriceSnapshot snapshot) {
    PriceFactorContext factors =
        snapshot == null
            ? null
            : new PriceFactorContext(
                row.getMaterialCode(),
                new PriceMonthSelection(row.getPriceMonth(), snapshot.scope().priceMonth()),
                snapshot,
                null);
    return prepare(row, factors);
  }

  public Input prepare(ScrapPrice row, PriceFactorContext factors) {
    ScrapPriceRule rule = rules.read(row);
    var variables = new TreeMap<String, BigDecimal>();
    if ("LINKED".equals(rule.priceType())) {
      if (factors == null)
        throw new IllegalArgumentException("废料" + row.getMaterialCode() + "缺少商用财务基价");
      if (!"210".equals(factors.financial().scope().priceOrgCode())
          || !row.getBusinessUnitType().equals(factors.financial().scope().businessUnitType())
          || !row.getPriceMonth().equals(factors.months().requestedMonth()))
        throw new IllegalArgumentException("废料规则和商用基价范围不一致");
      for (var binding : rule.factorBindings().entrySet())
        variables.put(
            binding.getKey(),
            factors.requiredPrice(binding.getValue(), FactorPriceUnit.CNY_KG, true));
      factors = factors.evidenceForFactors(new java.util.HashSet<>(rule.factorBindings().values()));
    } else factors = null;
    var evidence = new TreeMap<String, Object>();
    evidence.put("calculatorVersion", VERSION);
    evidence.put("priceMonth", row.getPriceMonth());
    evidence.put("ruleSourceMonth", row.getRuleSourceMonth());
    evidence.put("ruleId", row.getSourceRuleId() == null ? row.getId() : row.getSourceRuleId());
    evidence.put(
        "ruleRevision",
        row.getSourceRuleId() == null ? row.getRuleRevision() : row.getSourceRuleRevision());
    evidence.put("rule", rule);
    evidence.put("factors", factors);
    evidence.put("variables", variables);
    String encoded = rules.encode(evidence);
    return new Input(
        rule, Map.copyOf(variables), factors, ScrapPriceIdentity.hash(encoded), encoded);
  }

  public BigDecimal calculate(Input input) {
    BigDecimal value;
    try {
      value =
          "FIXED".equals(input.rule().priceType())
              ? input.rule().fixedPrice()
              : ExpressionEvaluator.evaluate(input.rule().expression(), input.variables());
    } catch (IllegalArgumentException | ArithmeticException error) {
      throw new IllegalArgumentException(
          "废料" + input.rule().materialCode() + "公式计算失败：" + error.getMessage(), error);
    }
    if (value.signum() < 0 || value.precision() - value.scale() > 20)
      throw new IllegalArgumentException("废料" + input.rule().materialCode() + "计算金额不能为负或超出精度");
    return value.setScale(18, RoundingMode.HALF_UP);
  }
}
