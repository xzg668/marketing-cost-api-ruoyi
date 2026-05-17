package com.sanhua.marketingcost.enums;

/** 报价单接入来源类型，对齐 oa_form.source_type / lp_quote_ingest_log.source_type。 */
public enum QuoteSourceType {
  OA("OA", "真实 OA 推送"),
  MOCK_OA("MOCK_OA", "模拟 OA 推送"),
  MANUAL("MANUAL", "平台手工录入"),
  EXCEL("EXCEL", "Excel 导入"),
  TECH("TECH", "技术补充格式"),
  LEGACY("LEGACY", "历史存量数据");

  private final String code;
  private final String label;

  QuoteSourceType(String code, String label) {
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
