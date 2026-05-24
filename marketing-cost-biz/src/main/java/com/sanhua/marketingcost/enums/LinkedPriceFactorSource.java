package com.sanhua.marketingcost.enums;

/** 联动价公式变量来源。 */
public enum LinkedPriceFactorSource {
  /** 正常报价：铜、锌、锡等变量取 OA 单头锁价。 */
  OA_LOCKED("OA_LOCKED", "OA单头锁价"),
  /** 月度价：变量取影响因素表指定月份价格。 */
  MONTHLY_FACTOR("MONTHLY_FACTOR", "影响因素月度价"),
  /** 月度调价：变量取指定调价批次价格。 */
  ADJUST_BATCH("ADJUST_BATCH", "月度调价批次价");

  private final String code;
  private final String label;

  LinkedPriceFactorSource(String code, String label) {
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
