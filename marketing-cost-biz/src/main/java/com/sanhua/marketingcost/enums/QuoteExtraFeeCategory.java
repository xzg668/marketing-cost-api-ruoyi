package com.sanhua.marketingcost.enums;

/** 报价额外费用分类，对齐 lp_oa_form_extra_fee.fee_category。 */
public enum QuoteExtraFeeCategory {
  TOOLING("TOOLING", "工装夹具"),
  MOLD("MOLD", "模具"),
  CERTIFICATION("CERTIFICATION", "认证"),
  EQUIPMENT("EQUIPMENT", "设备"),
  CUTTER("CUTTER", "刀具"),
  LABOR("LABOR", "人工"),
  SCRAP("SCRAP", "报废"),
  OTHER("OTHER", "其他");

  private final String code;
  private final String label;

  QuoteExtraFeeCategory(String code, String label) {
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
