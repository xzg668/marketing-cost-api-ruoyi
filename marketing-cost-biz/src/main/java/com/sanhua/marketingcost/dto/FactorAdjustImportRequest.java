package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustImportRequest {
  private String pricingMonth;
  private String businessUnitType;
  /** REPRICE_ONLY / REPRICE_AND_DAILY。 */
  private String usageScope;
  private String remark;
}
