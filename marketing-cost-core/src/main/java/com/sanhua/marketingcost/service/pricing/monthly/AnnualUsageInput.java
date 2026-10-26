package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;

/** Optional current finished-product demand. Invalid/missing inputs are explained only by tier pricing. */
public record AnnualUsageInput(BigDecimal value, String unit) {
  public BigDecimal piecesIfKnown() {
    if (value == null || value.signum() <= 0 || unit == null) return null;
    return switch (unit.strip()) {
      case "TEN_THOUSAND_PIECES" -> value.multiply(BigDecimal.valueOf(10_000)).stripTrailingZeros();
      case "PIECES" -> value.stripTrailingZeros();
      default -> null;
    };
  }
}
