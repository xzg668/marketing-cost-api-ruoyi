package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** The exact business configuration captured with a priced BOM occurrence. */
public record ProductPartCostRule(long id, long revision, String businessUnitType,
    String productCode, String productName, String productSpec, String productModel,
    String partCode, String partName, String partModel, String shape, BigDecimal manufactureRate,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String quotationShape) {

  public ProductPartCostRule(long id,long revision,String businessUnitType,String productCode,String productName,
      String productSpec,String productModel,String partCode,String partName,String partModel,String shape,BigDecimal manufactureRate) {
    this(id,revision,businessUnitType,productCode,productName,productSpec,productModel,partCode,partName,partModel,shape,manufactureRate,null);
  }

  public ProductPartCostRule {
    if (shape == null || !List.of("采购件", "制造件").contains(shape))
      throw new IllegalArgumentException("零件形态属性必须是采购件或制造件");
    if (manufactureRate == null || manufactureRate.signum() < 0 || manufactureRate.compareTo(BigDecimal.ONE) >= 0)
      throw new IllegalArgumentException("零件制造费用率必须大于等于0且小于100%");
    if(quotationShape!=null && !List.of("采购件","制造件","委外加工件").contains(quotationShape))
      throw new IllegalArgumentException("本次报价形态无效");
  }

  public ProductPartCostRule withQuotationShape(String selected) {
    return new ProductPartCostRule(id,revision,businessUnitType,productCode,productName,productSpec,
        productModel,partCode,partName,partModel,shape,manufactureRate,selected);
  }
  public String pricingShape() { return quotationShape==null ? shape : quotationShape; }

  public static ProductPartCostRule match(List<ProductPartCostRule> rules, String code, String model) {
    var matches = rules.stream().filter(rule -> same(code, rule.partCode())).toList();
    if (matches.isEmpty()) matches = rules.stream().filter(rule -> same(model, rule.partModel())).toList();
    if (matches.size() > 1) throw new IllegalArgumentException("零件成本配置匹配不唯一：" + code + " / " + model);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static boolean same(String value, String configured) {
    return value != null && !value.isBlank() && Objects.equals(value.trim(), configured);
  }
}
