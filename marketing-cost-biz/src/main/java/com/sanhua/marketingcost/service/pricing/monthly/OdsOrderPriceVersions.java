package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Latest effective/expiry dates win; equal periods retain the highest price. */
final class OdsOrderPriceVersions {
  record Price(String supplierCode, LocalDate effectiveDate, LocalDate expiryDate, BigDecimal price) {
    Price {
      if (supplierCode == null || supplierCode.isBlank())
        throw new IllegalArgumentException("订单价格缺少供应商代码");
      supplierCode = supplierCode.strip();
      if (price == null) throw new IllegalArgumentException("订单价格缺少单价");
      if (price.signum() < 0) throw new IllegalArgumentException("订单价格不能为负数");
      price = price.stripTrailingZeros();
    }
  }

  private OdsOrderPriceVersions() {}

  /** Caller supplies one applicable company/material/specification/unit, without filtering null dates. */
  static Map<String, Price> latestPerSupplier(Collection<Price> rows) {
    var order = Comparator.comparing(Price::effectiveDate,
        Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
        .thenComparing(Price::expiryDate,Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
        .thenComparing(Price::price);
    var selected = new TreeMap<String, Price>();
    for (var row : rows) selected.merge(row.supplierCode(), row,
        (previous, incoming) -> order.compare(incoming, previous) > 0 ? incoming : previous);
    return Collections.unmodifiableMap(selected);
  }
}
