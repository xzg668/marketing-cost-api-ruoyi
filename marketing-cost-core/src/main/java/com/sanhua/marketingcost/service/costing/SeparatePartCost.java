package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Uses the finished product's loss and three-expense rates, and the part's own manufacturing rate. */
public final class SeparatePartCost {
  private SeparatePartCost() {}

  public static BigDecimal amount(BigDecimal baseAmount, BigDecimal lossRate,
      BigDecimal manufactureRate, BigDecimal managementRate, BigDecimal salesRate, BigDecimal financeRate) {
    if (manufactureRate == null || manufactureRate.signum() < 0 || manufactureRate.compareTo(BigDecimal.ONE) >= 0)
      throw new IllegalArgumentException("零件制造费用率必须大于等于0且小于100%");
    return baseAmount.multiply(BigDecimal.ONE.add(lossRate))
        .multiply(BigDecimal.ONE.add(managementRate).add(salesRate).add(financeRate))
        .divide(BigDecimal.ONE.subtract(manufactureRate), 6, RoundingMode.HALF_UP);
  }

  public static BigDecimal amount(MonthlyProductCost.MaterialLine line, MonthlyProductCost.Ordinary input, boolean customer) {
    return amount(customer ? line.customerAmount() : line.financeAmount(), input.lossRate(),
        line.separateRule().manufactureRate(), input.managementRate(), input.salesRate(), input.financeRate());
  }
}
