package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.CalcTrace;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 计划价上浮模板 —— 家用件结算价等。
 *
 * <p>Excel 公式：
 * <pre>
 *   单价 = planPrice × (1 + upliftRatio)
 * </pre>
 */
@Component
public class PlanUpliftTemplate implements FormulaTemplate {
  public static final String CODE = "PLAN_UPLIFT";

  @Override
  public String templateCode() {
    return CODE;
  }

  @Override
  public CalcResult evaluate(Map<String, Object> inputs) {
    BigDecimal planPrice = TemplateInputs.requiredDecimal(inputs, "planPrice");
    BigDecimal upliftRatio = TemplateInputs.requiredDecimal(inputs, "upliftRatio");

    BigDecimal upliftFactor = BigDecimal.ONE.add(upliftRatio);
    BigDecimal unitPrice = planPrice.multiply(upliftFactor);

    CalcTrace trace = new CalcTrace(CODE)
        .input("planPrice", planPrice)
        .input("upliftRatio", upliftRatio)
        .step("upliftFactor = 1 + upliftRatio", upliftFactor)
        .step("unitPrice = planPrice × upliftFactor", unitPrice);
    return new CalcResult(unitPrice, trace);
  }
}
