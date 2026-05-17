package com.sanhua.marketingcost.enums;

/** 联动价与影响因素 Excel 导入生效策略。 */
public enum PriceLinkedImportEffectiveStrategy {
  APPEND_ONLY("APPEND_ONLY", "仅新增"),
  OVERRIDE_EFFECTIVE("OVERRIDE_EFFECTIVE", "覆盖生效");

  private final String code;
  private final String label;

  PriceLinkedImportEffectiveStrategy(String code, String label) {
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
