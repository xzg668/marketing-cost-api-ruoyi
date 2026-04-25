package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.CalcTrace;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 材料价 × 下料重 + 加工费 模板（套管 / 连杆 / 阀套类）。
 *
 * <p>Excel 公式：
 * <pre>
 *   单价 = materialPrice × blankWeight + processFee
 * </pre>
 */
@Component
public class MaterialUnitPlusFeeTemplate implements FormulaTemplate {
  public static final String CODE = "MATERIAL_UNIT_PLUS_FEE";

  @Override
  public String templateCode() {
    return CODE;
  }

  @Override
  public CalcResult evaluate(Map<String, Object> inputs) {
    BigDecimal materialPrice = TemplateInputs.requiredDecimal(inputs, "materialPrice");
    BigDecimal blankWeight = TemplateInputs.requiredDecimal(inputs, "blankWeight");
    BigDecimal processFee = TemplateInputs.optionalDecimal(inputs, "processFee", BigDecimal.ZERO);

    BigDecimal materialCost = materialPrice.multiply(blankWeight);
    BigDecimal unitPrice = materialCost.add(processFee);

    CalcTrace trace = new CalcTrace(CODE)
        .input("materialPrice", materialPrice)
        .input("blankWeight", blankWeight)
        .input("processFee", processFee)
        .step("materialCost = materialPrice × blankWeight", materialCost)
        .step("unitPrice = materialCost + processFee", unitPrice);
    return new CalcResult(unitPrice, trace);
  }
}
