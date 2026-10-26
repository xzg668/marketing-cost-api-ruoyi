package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;

/** Declared customer SUS terms; null means not provided, never source-independent. */
public record BoardTerms(
    BigDecimal sus304PricePerTon, BigDecimal sus316lPricePerTon, boolean finalTender) {

  public BoardTerms {
    sus304PricePerTon = normalizePrice(sus304PricePerTon);
    sus316lPricePerTon = normalizePrice(sus316lPricePerTon);
  }

  private static BigDecimal normalizePrice(BigDecimal price) {
    if (price == null) return null;
    if (price.signum() <= 0) {
      throw new IllegalArgumentException("A supplied customer SUS price must be positive");
    }
    return price.stripTrailingZeros();
  }
}
