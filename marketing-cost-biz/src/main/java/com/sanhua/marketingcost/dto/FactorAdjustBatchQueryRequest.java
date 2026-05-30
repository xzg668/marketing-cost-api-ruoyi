package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustBatchQueryRequest {
  private String pricingMonth;
  private String businessUnitType;
  private String adjustBatchNo;
  private String adjustType;
  private String usageScope;
  private String status;
  private String uploadedBy;
  private Boolean includeAllUploaders;
  private Integer limit;
  private Integer page;
  private Integer pageSize;
}
