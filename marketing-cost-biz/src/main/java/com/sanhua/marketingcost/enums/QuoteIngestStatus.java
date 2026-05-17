package com.sanhua.marketingcost.enums;

/** 接入流水状态，对齐 lp_quote_ingest_log.ingest_status。 */
public enum QuoteIngestStatus {
  RECEIVED("RECEIVED", "已收到"),
  VALIDATING("VALIDATING", "校验中"),
  REJECTED("REJECTED", "校验失败"),
  CLASSIFY_PENDING("CLASSIFY_PENDING", "分类待确认"),
  IMPORTED("IMPORTED", "已导入"),
  FAILED("FAILED", "系统异常");

  private final String code;
  private final String label;

  QuoteIngestStatus(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }
}
