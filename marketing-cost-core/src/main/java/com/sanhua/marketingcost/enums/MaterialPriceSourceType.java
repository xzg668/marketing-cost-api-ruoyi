package com.sanhua.marketingcost.enums;

import java.util.List;

/** 财务维护五类来源顺序；固定/联动入口按来源分派普通价或独立结算读取器。 */
public enum MaterialPriceSourceType {
  LINKED("联动价", PriceTypeEnum.LINKED),
  SETTLE_LINKED("结算联动价", PriceTypeEnum.LINKED),
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
