package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceAuditLogQueryRequest {
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String operationType;
  private String operatorName;
  private Integer page;
  private Integer pageSize;
  private String sortBy;
  private String sortDirection;
}
