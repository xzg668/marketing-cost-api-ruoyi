package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

/** 单品首次核算、重新核算和失败重试共用的提交参数。 */
@Data
public class QuoteProductCostRunRequest {
  private String periodMonth;
  private String reason;
}
