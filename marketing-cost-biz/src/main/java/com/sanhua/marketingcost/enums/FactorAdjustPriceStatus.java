package com.sanhua.marketingcost.enums;

/** 月度调价明细状态，对齐 lp_factor_adjust_price.status。 */
public enum FactorAdjustPriceStatus {
  CHANGED("CHANGED", "价格已变化"),
  NO_CHANGE("NO_CHANGE", "价格未变化"),
  FAILED("FAILED", "导入失败"),
  SKIPPED("SKIPPED", "已跳过");

  private final String code;
  private final String label;

  FactorAdjustPriceStatus(String code, String label) {
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
