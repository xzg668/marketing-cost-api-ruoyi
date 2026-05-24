package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareBulkGenerateResult {
  private String oaNo;
  private String topProductCode;
  private String periodMonth;
  private int topProductCount;
  private int readyTopProductCount;
  private int totalCount;
  private int readyCount;
  private int gapCount;
  private String status;
  private String message;
}
