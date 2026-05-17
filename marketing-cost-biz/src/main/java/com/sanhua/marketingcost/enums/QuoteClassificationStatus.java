package com.sanhua.marketingcost.enums;

/** 单据分类状态，对齐 oa_form / oa_form_item / lp_quote_ingest_log.classification_status。 */
public enum QuoteClassificationStatus {
  CONFIRMED("CONFIRMED", "已确认"),
  PENDING("PENDING", "待确认"),
  REJECTED("REJECTED", "已驳回");

  private final String code;
  private final String label;

  QuoteClassificationStatus(String code, String label) {
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
