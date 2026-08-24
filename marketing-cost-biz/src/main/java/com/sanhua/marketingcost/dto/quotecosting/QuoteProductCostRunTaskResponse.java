package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

/** 单个产品在当前整单批次中的任务状态。 */
@Data
public class QuoteProductCostRunTaskResponse {
  private String oaNo;
  private Long oaFormItemId;
  private String periodMonth;
  private String batchNo;
  private String status;
  private Integer progress;
  private String message;
}
