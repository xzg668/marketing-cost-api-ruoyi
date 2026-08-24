package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuotePriceTypeRecognitionSummaryResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String periodMonth;
  private String bomBuildBatchId;
  private String status;
  private Integer totalCount;
  private Integer confirmedCount;
  private Integer gapCount;
  private Integer referencePriceCount;
  private String message;
}
