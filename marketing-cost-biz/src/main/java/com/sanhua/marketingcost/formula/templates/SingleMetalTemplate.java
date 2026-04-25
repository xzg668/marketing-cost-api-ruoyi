package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.CalcTrace;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 单金属 + 加工费模板。
 *
 * <p>Excel 公式：
 * <pre>
 *   单价 = materialPrice × netWeight + processFee
 * </pre>
 */
@Component
public class SingleMetalTemplate implements FormulaTemplate {
  public static final String CODE = "SINGLE_METAL";

  @Override
  public String templateCode() {
    return CODE;
  }

  @Override
  public CalcResult evaluate(Map<String, Object> inputs) {
    BigDecimal materialPrice = TemplateInputs.requiredDecimal(inputs, "materialPrice");
    BigDecimal netWeight = TemplateInputs.requiredDecimal(inputs, "netWeight");
    BigDecimal processFee = TemplateInputs.optionalDecimal(inputs, "processFee", BigDecimal.ZERO);

    BigDecimal materialCost = materialPrice.multiply(netWeight);
    BigDecimal unitPrice = materialCost.add(processFee);

    CalcTrace trace = new CalcTrace(CODE)
        .input("materialPrice", materialPrice)
        .input("netWeight", netWeight)
        .input("processFee", processFee)
        .step("materialCost = materialPrice × netWeight", materialCost)
        .step("unitPrice = materialCost + processFee", unitPrice);
    return new CalcResult(unitPrice, trace);
  }
}
