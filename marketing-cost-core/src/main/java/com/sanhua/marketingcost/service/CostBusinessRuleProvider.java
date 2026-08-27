package com.sanhua.marketingcost.service;

import java.math.BigDecimal;

/** Reads effective-dated business rules used by the costing engine. */
public interface CostBusinessRuleProvider {

  String CMS_AUX_UPLIFT_RATE = "CMS_AUX_UPLIFT_RATE";
  String PACKAGE_COMPONENT_COEFFICIENT = "PACKAGE_COMPONENT_COEFFICIENT";
  BigDecimal decimalValue(
      String ruleCode,
      String pricingMonth,
      String businessUnitType,
      BigDecimal fallbackValue);
}
