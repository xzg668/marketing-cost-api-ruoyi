package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.ProductPropertyRule;
import java.math.BigDecimal;

/** Shared validation and conversion of the existing annual product-property rule. */
public final class ProductPropertyCoefficients {
  private ProductPropertyCoefficients() {}

  /** 属性表按U9裸品码维护；未维护裸品关系的料品保留自身料号查询。 */
  public static String lookupCode(String productCode, String bareCode) {
    return bareCode == null || bareCode.isBlank() ? productCode.trim() : bareCode.trim();
  }

  public static BigDecimal from(ProductPropertyRule rule, String attribute, Integer year) {
    if (rule == null) return null;
    var rate = rule.getUpliftRate();
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("产品属性上浮规则异常：" + attribute + "／" + year);
    }
    return BigDecimal.ONE.add(rate);
  }
}
