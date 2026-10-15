package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;

/** 客户SUS字段明确为含税元/吨；最终招标条件只决定未填写材质的固定基价。 */
public record BoardPriceInputs(
    BigDecimal sus304PricePerTon, BigDecimal sus316lPricePerTon, boolean finalTender) {
  public BoardPriceInputs {
    requirePositive(sus304PricePerTon, "SUS304");
    requirePositive(sus316lPricePerTon, "SUS316");
  }

  public BigDecimal customerPricePerTon(String canonicalKey) {
    var grade = BoardStainlessGrade.fromFactorKey(canonicalKey);
    return grade == null
        ? null
        : grade == BoardStainlessGrade.SUS304 ? sus304PricePerTon : sus316lPricePerTon;
  }

  private static void requirePositive(BigDecimal value, String label) {
    if (value != null && value.signum() <= 0)
      throw new IllegalArgumentException("客户" + label + "基价必须大于0；未指定请留空");
  }
}
