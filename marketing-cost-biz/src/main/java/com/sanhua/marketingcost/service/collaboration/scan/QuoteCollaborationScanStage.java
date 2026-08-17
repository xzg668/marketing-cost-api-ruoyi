package com.sanhua.marketingcost.service.collaboration.scan;

/** 可观测的固定扫描阶段，顺序就是业务规定的判断顺序。 */
public enum QuoteCollaborationScanStage {
  U9_CURRENT_BOM,
  PRODUCT_FORM,
  SAME_MONTH_ACTIVE_TASK,
  SIX_MONTH_APPROVED_RESULT,
  APPROVED_SOURCE,
  PRICE_PREPARATION
}
