package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceListQueryRequest {
  private String pricingMonth;
  private String businessUnitType;
  private String keyword;
  private String sourceTag;
  private String latestAdjustUsageScope;
  private String latestAdjustedBy;
  private Integer page;
  private Integer pageSize;
}
