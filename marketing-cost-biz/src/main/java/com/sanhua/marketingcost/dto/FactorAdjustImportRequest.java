package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustImportRequest {
  private String pricingMonth;
  private String businessUnitType;
  /** NORMAL 普通维护；MONTHLY 月度调价。为空时兼容历史入口，默认 NORMAL。 */
  private String adjustType;
  /** REPRICE_ONLY / REPRICE_AND_DAILY。 */
  private String usageScope;
  private String remark;
}
