package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.CalcTrace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 焊料 - 多金属配比模板。
 *
 * <p>Excel 公式：
 * <pre>
 *   weightedPrice = Σ ( metals[i].price × metals[i].ratio )   // ratio 总和应 ≈ 1
 *   单价         = weightedPrice × netWeight + processFee
 * </pre>
 *
 * <p>校验：ratio 总和必须 = 1.0000（容差 0.0001），否则配比不齐报错；
 *  避免脏数据导致单价偏离。
 */
@Component
public class WeldAlloyTemplate implements FormulaTemplate {
  public static final String CODE = "WELD_ALLOY";

  /** ratio 总和容差，用于校验配比是否完整 */
  private static final BigDecimal RATIO_TOLERANCE = new BigDecimal("0.0001");

  @Override
  public String templateCode() {
    return CODE;
  }

  @Override
  public CalcResult evaluate(Map<String, Object> inputs) {
    List<Map<String, Object>> metals = TemplateInputs.requiredArray(inputs, "metals");
    BigDecimal netWeight = TemplateInputs.requiredDecimal(inputs, "netWeight");
    BigDecimal processFee = TemplateInputs.optionalDecimal(inputs, "processFee", BigDecimal.ZERO);

    BigDecimal weightedPrice = BigDecimal.ZERO;
    BigDecimal ratioSum = BigDecimal.ZERO;
    for (Map<String, Object> metal : metals) {
      BigDecimal price = TemplateInputs.requiredDecimal(metal, "price");
      BigDecimal ratio = TemplateInputs.requiredDecimal(metal, "ratio");
      weightedPrice = weightedPrice.add(price.multiply(ratio));
      ratioSum = ratioSum.add(ratio);
    }
    if (ratioSum.subtract(BigDecimal.ONE).abs().compareTo(RATIO_TOLERANCE) > 0) {
      throw new IllegalArgumentException(
          "焊料配比总和必须 ≈ 1，当前为 " + ratioSum);
    }

    BigDecimal materialCost = weightedPrice.multiply(netWeight);
    BigDecimal unitPrice = materialCost.add(processFee);

    CalcTrace trace = new CalcTrace(CODE)
        .input("metals", metals)
        .input("netWeight", netWeight)
        .input("processFee", processFee)
        .step("weightedPrice = Σ(price × ratio)", weightedPrice)
        .step("ratioSum (校验=1)", ratioSum)
        .step("materialCost = weightedPrice × netWeight", materialCost)
        .step("unitPrice = materialCost + processFee", unitPrice);
    return new CalcResult(unitPrice, trace);
  }
}
