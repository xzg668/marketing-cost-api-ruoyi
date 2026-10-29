package com.sanhua.marketingcost.service.pricing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Prefer accounting-date prices across suppliers before selecting each supplier's latest version. */
public final class SupplyPriceDatePolicy {
  private SupplyPriceDatePolicy() {}

  /** Rows must belong to one applicable material/source/organization and requested supplier scope. */
  public static <T> List<T> preferCurrent(Collection<T> rows, LocalDate pricingDate,
      Function<T, LocalDate> effectiveFrom, Function<T, LocalDate> effectiveTo) {
    Objects.requireNonNull(pricingDate, "核算日期不能为空");
    var current = rows.stream().filter(row -> {
      LocalDate from = effectiveFrom.apply(row);
      LocalDate to = effectiveTo.apply(row);
      return (from == null || !from.isAfter(pricingDate))
          && (to == null || !to.isBefore(pricingDate));
    }).toList();
    // Fall back only when this material/source has no current supplier price.
    // A supplier with only expired prices must not compete with current suppliers.
    return current.isEmpty() ? List.copyOf(rows) : current;
  }
}
