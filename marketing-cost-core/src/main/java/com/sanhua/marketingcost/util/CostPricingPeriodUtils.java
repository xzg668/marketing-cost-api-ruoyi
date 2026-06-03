package com.sanhua.marketingcost.util;

import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.util.StringUtils;

/** 当前核算月统一口径。 */
public final class CostPricingPeriodUtils {

  private CostPricingPeriodUtils() {}

  public static LocalDate currentPricingDate() {
    return LocalDate.now();
  }

  public static String currentPricingMonth() {
    return YearMonth.from(currentPricingDate()).toString();
  }

  public static String normalizePricingMonth(String pricingMonth) {
    if (!StringUtils.hasText(pricingMonth)) {
      return currentPricingMonth();
    }
    try {
      return YearMonth.parse(pricingMonth.trim()).toString();
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("periodMonth 格式必须是 YYYY-MM", ex);
    }
  }

  public static String requireCurrentPricingMonth(String pricingMonth) {
    String normalized = normalizePricingMonth(pricingMonth);
    String current = currentPricingMonth();
    if (!current.equals(normalized)) {
      throw new IllegalArgumentException(
          "当前重算核算月为 " + current + "，请按当前月执行价格准备");
    }
    return normalized;
  }
}
