package com.sanhua.marketingcost.enums;

/** 月度调价影响因素匹配方式，对齐 lp_factor_adjust_price.match_method。 */
public enum FactorAdjustMatchMethod {
  SYSTEM_ID("SYSTEM_ID", "隐藏系统 ID"),
  IDENTITY_FIELDS("IDENTITY_FIELDS", "业务身份字段");

  private final String code;
  private final String label;

  FactorAdjustMatchMethod(String code, String label) {
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
