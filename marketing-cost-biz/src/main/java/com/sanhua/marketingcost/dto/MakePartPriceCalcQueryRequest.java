package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartPriceCalcQueryRequest {
  private String calcBatchId;
  private String pricingMonth;
  private String oaNo;
  private String businessUnitType;
  private String parentMaterialNo;
  private String childMaterialNo;
  private String scrapCode;
  private String itemProcessType;
  private String status;
  private String missingPriceRole;
  private String missingMaterialNo;
  private Boolean onlyError;
  private Integer page = 1;
  private Integer pageSize = 20;
}
