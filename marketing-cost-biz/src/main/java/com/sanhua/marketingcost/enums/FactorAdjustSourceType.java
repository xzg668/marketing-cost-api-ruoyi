package com.sanhua.marketingcost.enums;

/** 月度调价来源，对齐 lp_factor_adjust_batch.source_type。 */
public enum FactorAdjustSourceType {
  ADJUST_EXCEL_IMPORT("ADJUST_EXCEL_IMPORT", "月度调价 Excel 导入"),
  MANUAL_ADJUST("MANUAL_ADJUST", "页面手工调价");

  private final String code;
  private final String label;

  FactorAdjustSourceType(String code, String label) {
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
