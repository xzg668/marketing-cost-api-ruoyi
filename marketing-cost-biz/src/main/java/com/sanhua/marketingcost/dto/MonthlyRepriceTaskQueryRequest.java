package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceTaskQueryRequest {
  private String status;
  private String keyword;
  private Integer page;
  private Integer pageSize;
  private String sortBy;
  private String sortDirection;
}
