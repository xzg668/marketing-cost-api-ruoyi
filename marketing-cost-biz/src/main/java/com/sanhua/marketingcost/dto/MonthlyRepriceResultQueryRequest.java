package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceResultQueryRequest {
  private String oaNo;
  private String productCode;
  private String customerName;
  private String calcStatus;
  private String keyword;
  private Integer page;
  private Integer pageSize;
  private String sortBy;
  private String sortDirection;
}
