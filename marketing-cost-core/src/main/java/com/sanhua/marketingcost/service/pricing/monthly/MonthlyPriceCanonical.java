package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Internal, versioned field encoding; lengths are UTF-8 bytes and null has its own marker. */
final class MonthlyPriceCanonical {
  private static final int MAX_NUMBER_CHARACTERS = 4096;

  private MonthlyPriceCanonical() {}

  static String fields(String version, String... values) {
    var result = new StringBuilder(version).append('|');
    for (String value : values) {
      if (value == null) {
        result.append("-1:|");
      } else {
        result.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':').append(value).append('|');
      }
    }
    return result.toString();
  }

  static String number(BigDecimal value) {
    if (value == null) return null;
    BigDecimal normalized = value.stripTrailingZeros();
    long precision = normalized.precision();
    long scale = normalized.scale();
    long length = scale <= 0 ? precision - scale
        : precision > scale ? precision + 1 : scale + 2;
    if (normalized.signum() < 0) length++;
    if (length > MAX_NUMBER_CHARACTERS) {
      throw new IllegalArgumentException("Canonical number exceeds 4096 plain characters");
    }
    return normalized.toPlainString();
  }
}
