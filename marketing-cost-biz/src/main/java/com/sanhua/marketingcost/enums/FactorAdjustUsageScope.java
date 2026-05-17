package com.sanhua.marketingcost.enums;

/** 月度调价用途，对齐 lp_factor_adjust_batch.usage_scope。 */
public enum FactorAdjustUsageScope {
  REPRICE_ONLY("REPRICE_ONLY", "仅用于月度调价重算"),
  REPRICE_AND_DAILY("REPRICE_AND_DAILY", "同时作为日常报价生效价");

  private final String code;
  private final String label;

  FactorAdjustUsageScope(String code, String label) {
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
