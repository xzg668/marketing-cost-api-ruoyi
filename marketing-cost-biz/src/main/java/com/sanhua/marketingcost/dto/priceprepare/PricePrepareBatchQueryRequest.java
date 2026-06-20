package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareBatchQueryRequest {
  private String prepareNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String priceTypeConfirmNo;
  private String periodMonth;
  private String status;
  private Integer page;
  private Integer pageSize;
}
