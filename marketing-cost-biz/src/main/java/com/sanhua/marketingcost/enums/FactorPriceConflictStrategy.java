package com.sanhua.marketingcost.enums;

/** 影响因素月度价格冲突处理策略。 */
public enum FactorPriceConflictStrategy {
  KEEP_EXISTING("KEEP_EXISTING", "保留已有价格，冲突行跳过"),
  OVERWRITE("OVERWRITE", "仅覆盖冲突价格行");

  private final String code;
  private final String label;

  FactorPriceConflictStrategy(String code, String label) {
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
