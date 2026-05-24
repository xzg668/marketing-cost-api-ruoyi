package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGenerateResult {
  private String prepareNo;
  private String oaNo;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
  private String status;
  private int totalCount;
  private int successCount;
  private int warningCount;
  private int gapCount;
  private String message;
}
