package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareOaSummaryQueryRequest {
  private String oaNo;
  private String periodMonth;
  private String status;
  private Integer page;
  private Integer pageSize;
}
