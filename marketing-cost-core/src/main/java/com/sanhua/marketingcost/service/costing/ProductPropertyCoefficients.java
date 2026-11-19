package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import java.math.BigDecimal;

/** Annual product-property coefficients used by master data and costing. */
public final class ProductPropertyCoefficients {
  private static final BigDecimal STANDARD_USAGE_THRESHOLD_WAN = BigDecimal.TEN;

  private ProductPropertyCoefficients() {}

  /** 属性表按U9裸品码维护；未维护裸品关系的料品保留自身料号查询。 */
  public static String lookupCode(String productCode, String bareCode) {
    return bareCode == null || bareCode.isBlank() ? productCode.trim() : bareCode.trim();
  }

  public static boolean qualifiesAsStandardByUsage(ProductProperty property) {
    return property != null && "非标品".equals(property.getProductAttr())
        && property.getEstimatedAnnualUsageWan() != null
        && property.getEstimatedAnnualUsageWan().compareTo(STANDARD_USAGE_THRESHOLD_WAN) >= 0;
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
