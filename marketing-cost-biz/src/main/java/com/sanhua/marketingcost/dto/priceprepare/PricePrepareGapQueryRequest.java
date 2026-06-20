package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGapQueryRequest {
  private String prepareNo;
  private String periodMonth;
  private String priceTypeConfirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String materialCode;
  private String gapMaterialCode;
  private String gapType;
  private String itemType;
  private String oaPushStatus;
  private Integer page;
  private Integer pageSize;
}
