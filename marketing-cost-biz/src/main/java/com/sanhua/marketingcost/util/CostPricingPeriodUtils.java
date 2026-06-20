package com.sanhua.marketingcost.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

public final class CostPricingPeriodUtils {

  private CostPricingPeriodUtils() {}

  public static LocalDate currentPricingDate() {
    return LocalDate.now();
  }

  public static String currentPricingMonth() {
    return YearMonth.from(currentPricingDate()).toString();
  }

  public static String normalizePricingMonth(String periodMonth) {
    if (!StringUtils.hasText(periodMonth)) {
      return currentPricingMonth();
    }
    String normalized = periodMonth.trim();
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("核算月份格式必须为 YYYY-MM: " + normalized, ex);
    }
  }

  public static String requireCurrentPricingMonth(String periodMonth) {
    String normalized = normalizePricingMonth(periodMonth);
    String current = currentPricingMonth();
    if (!current.equals(normalized)) {
      throw new IllegalArgumentException(
          "当前重算核算月为 " + current + "，请按当前月执行价格准备");
    }
    return normalized;
  }
}
