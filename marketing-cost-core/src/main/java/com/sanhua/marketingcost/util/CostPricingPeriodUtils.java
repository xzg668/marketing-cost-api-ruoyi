package com.sanhua.marketingcost.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.util.StringUtils;

/** 中国业务日历及月份格式；普通报价的有效月份由 QuotePricingPeriod 决定。 */
public final class CostPricingPeriodUtils {

  /** All costing dates use the China business calendar, independent of server locale. */
  public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private CostPricingPeriodUtils() {}

  public static LocalDate currentPricingDate() {
    return LocalDate.now(BUSINESS_ZONE);
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

}
