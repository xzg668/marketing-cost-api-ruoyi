package com.sanhua.marketingcost.service.pricing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Prefer accounting-date prices across suppliers before selecting each supplier's latest version. */
public final class SupplyPriceDatePolicy {
  private SupplyPriceDatePolicy() {}

  /** Rows must belong to one applicable material/source/organization and requested supplier scope. */
  public static <T> List<T> preferCurrent(Collection<T> rows, LocalDate pricingDate,
      Function<T, LocalDate> effectiveFrom, Function<T, LocalDate> effectiveTo) {
    var current = current(rows, pricingDate, effectiveFrom, effectiveTo);
    // Fall back only when this material/source has no current supplier price.
    // A supplier with only expired prices must not compete with current suppliers.
    return current.isEmpty() ? List.copyOf(rows) : current;
  }

  /** 固定采购价无当期有效价时，只沿用该范围最新执行期间；同期间候选仍按既有选供规则处理。 */
  public static <T> List<T> preferCurrentOrLatestPeriod(Collection<T> rows, LocalDate pricingDate,
      Function<T, LocalDate> effectiveFrom, Function<T, LocalDate> effectiveTo) {
    var current = current(rows, pricingDate, effectiveFrom, effectiveTo);
    if (!current.isEmpty() || rows.isEmpty()) return current;
    Comparator<T> period = Comparator.comparing(effectiveFrom, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(effectiveTo, Comparator.nullsFirst(Comparator.naturalOrder()));
    T latest = rows.stream().max(period).orElseThrow();
    return rows.stream().filter(row -> period.compare(row, latest) == 0).toList();
  }

  private static <T> List<T> current(Collection<T> rows, LocalDate pricingDate,
      Function<T, LocalDate> effectiveFrom, Function<T, LocalDate> effectiveTo) {
    Objects.requireNonNull(pricingDate, "核算日期不能为空");
    return rows.stream().filter(row -> {
      LocalDate from = effectiveFrom.apply(row);
      LocalDate to = effectiveTo.apply(row);
      return (from == null || !from.isAfter(pricingDate))
          && (to == null || !to.isBefore(pricingDate));
    }).toList();
  }
}
