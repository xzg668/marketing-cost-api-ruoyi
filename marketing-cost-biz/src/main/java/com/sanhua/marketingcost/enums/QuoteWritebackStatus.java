package com.sanhua.marketingcost.enums;

/** 回写任务状态，对齐 lp_quote_writeback_task.writeback_status。 */
public enum QuoteWritebackStatus {
  PENDING("PENDING", "待回写"),
  SUCCESS("SUCCESS", "已回写"),
  FAILED("FAILED", "回写失败"),
  SKIPPED("SKIPPED", "已跳过");

  private final String code;
  private final String label;

  QuoteWritebackStatus(String code, String label) {
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
