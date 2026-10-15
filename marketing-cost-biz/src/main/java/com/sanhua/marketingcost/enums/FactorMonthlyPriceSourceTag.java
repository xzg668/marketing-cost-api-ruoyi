package com.sanhua.marketingcost.enums;

/** 影响因素日常报价生效价来源标签，对齐 lp_factor_monthly_price.source_tag。 */
public enum FactorMonthlyPriceSourceTag {
  EXCEL_IMPORT("EXCEL_IMPORT", "联动价与影响因素 Excel 导入");

  private final String code;
  private final String label;

  FactorMonthlyPriceSourceTag(String code, String label) {
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
