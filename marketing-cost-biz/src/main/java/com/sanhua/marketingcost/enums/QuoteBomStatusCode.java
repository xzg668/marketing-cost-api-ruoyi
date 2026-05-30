package com.sanhua.marketingcost.enums;

/** 报价单产品 BOM 状态，对齐 lp_quote_bom_status.bom_status。 */
public enum QuoteBomStatusCode {
  NOT_CHECKED("NOT_CHECKED", "未检查"),
  SYNCING("SYNCING", "同步中"),
  SYNCED("SYNCED", "已同步"),
  REUSED_CURRENT_MONTH("REUSED_CURRENT_MONTH", "已沿用"),
  NO_BOM("NO_BOM", "无 BOM"),
  ENTRY_PENDING("ENTRY_PENDING", "待技术补录"),
  ENTRY_IN_PROGRESS("ENTRY_IN_PROGRESS", "技术录入中"),
  MANUAL_ENTERED("MANUAL_ENTERED", "已手工录入"),
  EXPIRED("EXPIRED", "手工 BOM 已过期"),
  CHECK_FAILED("CHECK_FAILED", "检查异常");

  private final String code;
  private final String label;

  QuoteBomStatusCode(String code, String label) {
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
