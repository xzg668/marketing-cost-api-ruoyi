package com.sanhua.marketingcost.service.pricing.settlement;

/** 来源身份始终保留；缺重量不会把联动来源变成固定来源。 */
public enum SettlementPriceType {
  SETTLE_FIXED("结算固定价"), SETTLE_LINKED("结算联动价");

  private final String label;
  SettlementPriceType(String label) { this.label = label; }
  public String label() { return label; }
}
