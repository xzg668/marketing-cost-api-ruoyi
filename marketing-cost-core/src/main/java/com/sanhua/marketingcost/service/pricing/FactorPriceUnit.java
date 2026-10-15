package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.util.Objects;

/** 因素单价的实际单位；只转换质量分母，不推断税率或接受错误表头的放大倍数。 */
public enum FactorPriceUnit {
  CNY_G(-3),
  CNY_KG(0),
  CNY_TON(3);

  private final int kilogramsPower;

  FactorPriceUnit(int kilogramsPower) {
    this.kilogramsPower = kilogramsPower;
  }

  public BigDecimal convert(BigDecimal price, FactorPriceUnit target) {
    Objects.requireNonNull(price, "因素价格缺失");
    Objects.requireNonNull(target, "目标单位缺失");
    return price.scaleByPowerOfTen(target.kilogramsPower - kilogramsPower);
  }

  public static FactorPriceUnit require(String unit) {
    if (unit == null) throw new IllegalArgumentException("因素单位缺失");
    return switch (unit.trim()) {
      case "CNY/G", "元/克" -> CNY_G;
      case "CNY/KG", "元/公斤", "元/千克" -> CNY_KG;
      case "CNY/TON", "元/吨" -> CNY_TON;
      default -> throw new IllegalArgumentException("不支持的因素实际单位：" + unit);
    };
  }
}
