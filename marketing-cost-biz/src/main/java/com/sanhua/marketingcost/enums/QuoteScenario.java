package com.sanhua.marketingcost.enums;

/** 报价场景，对齐 oa_form.quote_scenario / lp_quote_ingest_type_rule.target_quote_scenario。 */
public enum QuoteScenario {
  DIRECT_SALE("DIRECT_SALE", "板换直销"),
  STANDARD_BATCH("STANDARD_BATCH", "标准品/批量品"),
  NEW_PRODUCT("NEW_PRODUCT", "新品"),
  MASS_PRODUCT("MASS_PRODUCT", "批量品"),
  DERIVED_PRODUCT("DERIVED_PRODUCT", "衍生品"),
  TECH_SUPPLEMENT("TECH_SUPPLEMENT", "技术补充单"),
  UNKNOWN("UNKNOWN", "待人工确认");

  private final String code;
  private final String label;

  QuoteScenario(String code, String label) {
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
