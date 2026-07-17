package com.sanhua.marketingcost.enums;

public enum MetalBasePricePolicy {
  OA_PRIORITY,
  FACTOR_MONTHLY;

  public static MetalBasePricePolicy from(String value) {
    if (value == null || value.isBlank()) {
      return OA_PRIORITY;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("不支持的金属基价取值方式：" + value);
    }
  }
}
