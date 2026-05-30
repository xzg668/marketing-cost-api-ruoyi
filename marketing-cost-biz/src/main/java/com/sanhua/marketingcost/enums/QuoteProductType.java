package com.sanhua.marketingcost.enums;

/** 报价产品形态，按报价产品料号本身在 U9 料品主档中的主分类判断。 */
public enum QuoteProductType {
  BARE("BARE", "裸品"),
  NON_BARE("NON_BARE", "非裸品"),
  DATA_MISSING("DATA_MISSING", "料品主档缺失"),
  UNKNOWN("UNKNOWN", "无法判断");

  private final String code;
  private final String label;

  QuoteProductType(String code, String label) {
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
