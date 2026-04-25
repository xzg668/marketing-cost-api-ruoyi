package com.sanhua.marketingcost.formula.templates;

import com.sanhua.marketingcost.formula.CalcResult;
import com.sanhua.marketingcost.formula.CalcTrace;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 合金 + 废料抵减模板 —— 端盖/连杆/拖动架等 6 件适用。
 *
 * <p>Excel 公式：
 * <pre>
 *   单价 = alloyPrice × blankWeight − (blankWeight − netWeight) × scrapPrice × scrapRatio + processFee
 * </pre>
 *
 * <p>单位：单价单位与材料价单位一致（元/g 或 元/kg）；调用方负责单位换算。
 */
@Component
public class AlloyScrapTemplate implements FormulaTemplate {
  /** 模板编码常量（避免散落的字符串）*/
  public static final String CODE = "ALLOY_SCRAP";

  @Override
  public String templateCode() {
    return CODE;
  }

  @Override
  public CalcResult evaluate(Map<String, Object> inputs) {
    BigDecimal alloyPrice = TemplateInputs.requiredDecimal(inputs, "alloyPrice");
    BigDecimal blankWeight = TemplateInputs.requiredDecimal(inputs, "blankWeight");
    BigDecimal netWeight = TemplateInputs.requiredDecimal(inputs, "netWeight");
    BigDecimal scrapPrice = TemplateInputs.requiredDecimal(inputs, "scrapPrice");
    BigDecimal scrapRatio = TemplateInputs.optionalDecimal(inputs, "scrapRatio", BigDecimal.ONE);
    BigDecimal processFee = TemplateInputs.optionalDecimal(inputs, "processFee", BigDecimal.ZERO);

    BigDecimal alloyCost = alloyPrice.multiply(blankWeight);
    BigDecimal scrapWeight = blankWeight.subtract(netWeight);
    BigDecimal scrapDeduction = scrapWeight.multiply(scrapPrice).multiply(scrapRatio);
    BigDecimal unitPrice = alloyCost.subtract(scrapDeduction).add(processFee);

    CalcTrace trace = new CalcTrace(CODE)
        .input("alloyPrice", alloyPrice)
        .input("blankWeight", blankWeight)
        .input("netWeight", netWeight)
        .input("scrapPrice", scrapPrice)
        .input("scrapRatio", scrapRatio)
        .input("processFee", processFee)
        .step("alloyCost = alloyPrice × blankWeight", alloyCost)
        .step("scrapWeight = blankWeight − netWeight", scrapWeight)
        .step("scrapDeduction = scrapWeight × scrapPrice × scrapRatio", scrapDeduction)
        .step("unitPrice = alloyCost − scrapDeduction + processFee", unitPrice);
    return new CalcResult(unitPrice, trace);
  }
}
