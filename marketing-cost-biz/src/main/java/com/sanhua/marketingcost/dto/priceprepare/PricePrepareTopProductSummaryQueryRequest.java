package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareTopProductSummaryQueryRequest {
  private String oaNo;
  private String topProductCode;
  private String status;
  private Integer page;
  private Integer pageSize;
}
