package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import org.springframework.util.StringUtils;

/**
 * Ordinary quotations use one effective month for BOM, prices and cost attribution.
 * Repricing workers and historical reads use their explicitly saved month instead.
 */
@FunctionalInterface
public interface QuotePricingPeriod {
  String currentMonth(String businessUnitType);

  default String currentMonth() {
    return currentMonth(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  default String requireActiveMonth(String requestedMonth) {
    return requireActiveMonth(requestedMonth, BusinessUnitContext.getCurrentBusinessUnitType());
  }

  default String requireActiveMonth(String requestedMonth, String businessUnitType) {
    String active = currentMonth(businessUnitType);
    if (StringUtils.hasText(requestedMonth)
        && !active.equals(CostPricingPeriodUtils.normalizePricingMonth(requestedMonth))) {
      throw new IllegalArgumentException(
          "当前生效核算月份为 " + active + "，请刷新后按生效月份核算；调价确认后才切换月份");
    }
    return active;
  }
}
