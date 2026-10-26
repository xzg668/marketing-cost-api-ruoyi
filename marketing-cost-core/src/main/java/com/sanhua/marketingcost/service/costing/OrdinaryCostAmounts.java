package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The existing ordinary rounding boundaries, shared by current and monthly costing. */
public final class OrdinaryCostAmounts {
  private OrdinaryCostAmounts() {}

  public record Result(BigDecimal lossBase, BigDecimal loss, BigDecimal manufactureCost,
      BigDecimal manufactureFee, BigDecimal adjustedManufactureCost, BigDecimal management,
      BigDecimal sales, BigDecimal finance, BigDecimal total) {}

  public static BigDecimal money(BigDecimal amount) {
    return amount.setScale(6, RoundingMode.HALF_UP);
  }

  public static Result calculate(BigDecimal material, BigDecimal direct, BigDecimal indirect,
      BigDecimal lossRate, BigDecimal manufactureRate, BigDecimal coefficient,
      BigDecimal managementRate, BigDecimal salesRate, BigDecimal financeRate,
      BigDecimal independentExpenses) {
    BigDecimal lossBase = material.add(direct).add(indirect);
    BigDecimal loss = multiply(lossBase, lossRate);
    BigDecimal base = lossBase.add(loss == null ? BigDecimal.ZERO : loss);
    BigDecimal manufacture = manufactureRate == null || manufactureRate.compareTo(BigDecimal.ONE) >= 0
        ? null : base.divide(BigDecimal.ONE.subtract(manufactureRate), 6, RoundingMode.HALF_UP);
    BigDecimal manufactureFee = multiply(manufacture, manufactureRate);
    BigDecimal adjusted = multiply(manufacture, coefficient);
    BigDecimal management = multiply(adjusted, managementRate);
    BigDecimal sales = multiply(adjusted, salesRate);
    BigDecimal finance = multiply(adjusted, financeRate);
    BigDecimal total = adjusted;
    if (total != null) {
      for (var amount : new BigDecimal[]{management, sales, finance, independentExpenses}) {
        if (amount != null) total = total.add(amount);
      }
      total = money(total);
    }
    return new Result(lossBase, loss, manufacture, manufactureFee, adjusted, management, sales, finance, total);
  }

  private static BigDecimal multiply(BigDecimal amount, BigDecimal rate) {
    return amount == null || rate == null ? null : money(amount.multiply(rate));
  }
}
