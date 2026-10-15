package com.sanhua.marketingcost.enums;

import java.util.List;

/** 财务调整的是价格来源顺序；结算固定价与采购固定价共用固定价计算器。 */
public enum MaterialPriceSourceType {
  LINKED("联动价", PriceTypeEnum.LINKED),
  SETTLE_LINKED("结算联动价", null),
  RANGE("区间价", PriceTypeEnum.RANGE),
  SETTLE_FIXED("结算固定价", PriceTypeEnum.FIXED),
  FIXED("固定价", PriceTypeEnum.FIXED);

  public static final List<MaterialPriceSourceType> DEFAULT_ORDER = List.of(values());
  private final String label;
  private final PriceTypeEnum calculator;

  MaterialPriceSourceType(String label, PriceTypeEnum calculator) {
    this.label = label;
    this.calculator = calculator;
  }

  public String label() { return label; }
  public PriceTypeEnum calculator() { return calculator; }
  public int getDefaultPriority() { return ordinal() + 1; }

  public static MaterialPriceSourceType fromStored(String value) {
    return DEFAULT_ORDER.stream()
        .filter(type -> type.name().equals(value) || type.label.equals(value))
        .findFirst().orElseThrow(() -> new IllegalStateException("无法识别取价类型：" + value));
  }
}
