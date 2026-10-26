package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Frozen quantity coverage; null endpoints are unbounded. */
public record QuantityInterval(
    BigDecimal lower, boolean lowerInclusive, BigDecimal upper, boolean upperInclusive) {

  public QuantityInterval {
    lower = lower == null ? null : lower.stripTrailingZeros();
    upper = upper == null ? null : upper.stripTrailingZeros();
    lowerInclusive = lower != null && lowerInclusive;
    upperInclusive = upper != null && upperInclusive;
    if (!nonempty(lower, lowerInclusive, upper, upperInclusive)) {
      throw new IllegalArgumentException("Quantity interval must be nonempty and ordered");
    }
  }

  public static QuantityInterval all() {
    return new QuantityInterval(null, false, null, false);
  }

  public boolean contains(BigDecimal quantity) {
    Objects.requireNonNull(quantity, "Quantity is required");
    boolean aboveLower = lower == null || quantity.compareTo(lower) > 0
        || lowerInclusive && quantity.compareTo(lower) == 0;
    boolean belowUpper = upper == null || quantity.compareTo(upper) < 0
        || upperInclusive && quantity.compareTo(upper) == 0;
    return aboveLower && belowUpper;
  }

  public Optional<QuantityInterval> intersection(QuantityInterval other) {
    Objects.requireNonNull(other, "Other quantity interval is required");
    BigDecimal low = lower;
    boolean includeLow = lowerInclusive;
    if (low == null || other.lower != null && other.lower.compareTo(low) > 0) {
      low = other.lower;
      includeLow = other.lowerInclusive;
    } else if (other.lower != null && other.lower.compareTo(low) == 0) {
      includeLow = lowerInclusive && other.lowerInclusive;
    }

    BigDecimal high = upper;
    boolean includeHigh = upperInclusive;
    if (high == null || other.upper != null && other.upper.compareTo(high) < 0) {
      high = other.upper;
      includeHigh = other.upperInclusive;
    } else if (other.upper != null && other.upper.compareTo(high) == 0) {
      includeHigh = upperInclusive && other.upperInclusive;
    }
    return portion(low, includeLow, high, includeHigh);
  }

  public List<QuantityInterval> subtract(QuantityInterval other) {
    var overlap = intersection(other);
    if (overlap.isEmpty()) return List.of(this);
    QuantityInterval covered = overlap.get();
    var remaining = new ArrayList<QuantityInterval>(2);
    if (covered.lower != null) {
      portion(lower, lowerInclusive, covered.lower, !covered.lowerInclusive).ifPresent(remaining::add);
    }
    if (covered.upper != null) {
      portion(covered.upper, !covered.upperInclusive, upper, upperInclusive).ifPresent(remaining::add);
    }
    return List.copyOf(remaining);
  }

  private static Optional<QuantityInterval> portion(
      BigDecimal low, boolean includeLow, BigDecimal high, boolean includeHigh) {
    return nonempty(low, includeLow, high, includeHigh)
        ? Optional.of(new QuantityInterval(low, includeLow, high, includeHigh))
        : Optional.empty();
  }

  private static boolean nonempty(
      BigDecimal low, boolean includeLow, BigDecimal high, boolean includeHigh) {
    if (low == null || high == null) return true;
    int order = low.compareTo(high);
    return order < 0 || order == 0 && includeLow && includeHigh;
  }
}
