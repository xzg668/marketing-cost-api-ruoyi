package com.sanhua.marketingcost.dto.priceprepare;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGenerateResult {
  private String prepareNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
  private String scenarioType;
  private String scenarioGroupNo;
  private String sourcePrepareNo;
  private String status;
  private int totalCount;
  private int successCount;
  private int warningCount;
  private int gapCount;
  private LocalDateTime priceAsOfTime;
  private String priceAsOfSource;
  private String message;
}
