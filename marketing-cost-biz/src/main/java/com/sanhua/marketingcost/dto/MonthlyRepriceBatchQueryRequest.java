package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceBatchQueryRequest {
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String status;
  private String createdBy;
  private String confirmedBy;
  private Integer page;
  private Integer pageSize;
  private String sortBy;
  private String sortDirection;
}
