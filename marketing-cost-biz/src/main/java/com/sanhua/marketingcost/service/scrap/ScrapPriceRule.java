package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 可导入或人工编辑的规则；原始文件证据与计算结果不参与规则内容去重。 */
public record ScrapPriceRule(
    String materialCode,
    String materialName,
    String specification,
    String unit,
    String supplierCode,
    String supplierName,
    String priceType,
    @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        BigDecimal fixedPrice,
    String sourceFormula,
    String expression,
    Map<String, String> factorBindings) {

  public ScrapPriceRule {
    materialCode = required(materialCode, "废料料号", 64);
    materialName = required(materialName, "废料名称", 255);
    specification = specification == null ? "" : specification.trim();
    if (specification.length() > 512) throw new IllegalArgumentException("规格不能超过512字");
    unit = required(unit, "单位", 32);
    if (Set.of("千克", "公斤", "kg", "KG").contains(unit)) unit = "KG";
    if (!"KG".equals(unit)) throw new IllegalArgumentException("废料计价单位必须为千克/KG");
    supplierCode = optional(supplierCode, "供应商代码", 64);
    supplierName = optional(supplierName, "供应商名称", 255);
    priceType = required(priceType, "价格类型", 16);
    factorBindings =
        Collections.unmodifiableMap(
            new TreeMap<>(factorBindings == null ? Map.of() : factorBindings));
    if ("FIXED".equals(priceType)) {
      if (fixedPrice == null || fixedPrice.signum() < 0)
        throw new IllegalArgumentException("固定价金额必须为非负数");
      fixedPrice = fixedPrice.stripTrailingZeros();
      if (fixedPrice.scale() > 18 || fixedPrice.precision() - fixedPrice.scale() > 20)
        throw new IllegalArgumentException("固定价金额超出允许精度");
      if ((expression != null && !expression.isBlank()) || !factorBindings.isEmpty())
        throw new IllegalArgumentException("固定价不能同时保存联动公式");
      expression = null;
      sourceFormula = null;
    } else if ("LINKED".equals(priceType)) {
      if (fixedPrice != null) throw new IllegalArgumentException("联动价不能同时保存固定金额");
      expression = required(expression, "实际公式", 16000).replaceAll("\\s+", "");
      sourceFormula = required(sourceFormula, "源公式", 16000);
      ExpressionEvaluator.validate(expression);
      var variables = ExpressionEvaluator.extractVariables(expression);
      if (variables.isEmpty() || !variables.equals(factorBindings.keySet()))
        throw new IllegalArgumentException("联动公式与因素绑定不一致");
      for (String factor : factorBindings.values())
        if (factor == null || !Set.of("AVG|1#CU", "AVG|美国柜装黄铜", "AVG|A00").contains(factor))
          throw new IllegalArgumentException("废料联动因素仅支持商用铜、美国柜装黄铜、长江现货A00");
    } else throw new IllegalArgumentException("价格类型仅支持固定价/联动价");
  }

  public ScrapPriceIdentity identity(String businessUnit, String month) {
    return new ScrapPriceIdentity(businessUnit, month, materialCode, supplierCode, supplierName);
  }

  public String contentHash(ObjectMapper json) {
    var content = new TreeMap<String, Object>();
    content.put("materialCode", materialCode);
    content.put("materialName", materialName);
    content.put("specification", specification);
    content.put("unit", unit);
    content.put("supplierCode", supplierCode);
    content.put("supplierName", supplierName);
    content.put("priceType", priceType);
    content.put("fixedPrice", fixedPrice == null ? null : fixedPrice.toPlainString());
    content.put("expression", expression);
    content.put("factorBindings", factorBindings);
    try {
      return ScrapPriceIdentity.hash(json.writeValueAsString(content));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("废料规则序列化失败", failure);
    }
  }

  static String required(String value, String name, int length) {
    if (value == null || value.isBlank() || value.trim().length() > length)
      throw new IllegalArgumentException(name + "必填且长度不能超过" + length);
    return value.trim();
  }

  private static String optional(String value, String name, int length) {
    return value == null || value.isBlank() ? null : required(value, name, length);
  }
}
