package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.ProductPropertyRule;
import java.math.BigDecimal;

/** Shared validation and conversion of the existing annual product-property rule. */
public final class ProductPropertyCoefficients {
  private ProductPropertyCoefficients() {}

  public static BigDecimal from(ProductPropertyRule rule, String attribute, Integer year) {
    if (rule == null) return null;
    var rate = rule.getUpliftRate();
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("产品属性上浮规则异常：" + attribute + "／" + year);
    }
    return BigDecimal.ONE.add(rate);
  }
}
