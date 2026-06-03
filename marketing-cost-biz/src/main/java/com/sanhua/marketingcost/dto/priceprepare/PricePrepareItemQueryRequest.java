package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareItemQueryRequest {
  private String prepareNo;
  private String periodMonth;
  private String oaNo;
  private String topProductCode;
  private String materialCode;
  private String itemType;
  private String status;
  private Integer page;
  private Integer pageSize;
}
