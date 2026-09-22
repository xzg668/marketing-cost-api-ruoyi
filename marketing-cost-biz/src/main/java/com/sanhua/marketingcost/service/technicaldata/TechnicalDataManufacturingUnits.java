package com.sanhua.marketingcost.service.technicaldata;

import java.math.BigDecimal;
import java.util.Locale;

/** 补录保存单件原值，接树和既有克制制造件输入时才换算；净长不参与推算重量。 */
public final class TechnicalDataManufacturingUnits {
  private TechnicalDataManufacturingUnits() {}

  public static BigDecimal sourceWeightG(BigDecimal value, String unit) {
    if (value == null || value.signum() < 0) throw new IllegalArgumentException("图库未提供有效单件净重");
    return switch (weightUnit(unit)) {
      case "kg" -> value.movePointRight(3);
      case "g" -> value;
      default -> throw new IllegalArgumentException("图库重量单位不明确，不能推算净重");
    };
  }

  public static BigDecimal grossWeightG(BigDecimal kilograms) {
    if (kilograms == null || kilograms.signum() <= 0) throw new IllegalArgumentException("单件毛重必须大于 0 kg");
    return kilograms.movePointRight(3);
  }

  public static BigDecimal purchasingQuantity(BigDecimal grossWeightKg, String purchasingUnit) {
    BigDecimal grams = grossWeightG(grossWeightKg);
    return switch (weightUnit(purchasingUnit)) {
      case "kg" -> grossWeightKg;
      case "g" -> grams;
      default -> throw new IllegalArgumentException("原材料采购单位没有明确的重量换算，请核实料品档案；不能按净长猜重量");
    };
  }

  /** 既有制造件计算器的原料/废料价格契约为元/kg，公共取价仍按真实采购单位返回。 */
  public static BigDecimal pricePerKg(BigDecimal purchasingPrice, String purchasingUnit) {
    if (purchasingPrice == null) return null;
    return switch (weightUnit(purchasingUnit)) {
      case "kg" -> purchasingPrice;
      case "g" -> purchasingPrice.movePointRight(3);
      default -> throw new IllegalArgumentException("制造件采购价格单位不能换算为元/kg：" + purchasingUnit);
    };
  }

  private static String weightUnit(String value) {
    if (value == null) return "";
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "kg", "公斤", "千克" -> "kg";
      case "g", "克" -> "g";
      default -> "";
    };
  }
}
