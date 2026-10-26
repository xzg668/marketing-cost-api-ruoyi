package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.OdsMaterialPriceRow;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Select one latest effective-date cohort per supplier, retaining all of its quantity bands. */
final class OdsTierPriceVersions {
  private OdsTierPriceVersions() {}

  /** Caller provides one applicable company/material/specification/unit from the latest dt. */
  static Map<String, List<OdsMaterialPriceRow>> latestPerSupplier(Collection<OdsMaterialPriceRow> rows, LocalDate asOf) {
    Objects.requireNonNull(asOf, "阶梯取价日期不能为空");
    var latestDates = new HashMap<String, LocalDate>();
    var selected = new TreeMap<String, List<OdsMaterialPriceRow>>();
    for (var row : rows) {
      if (row.getSupplierCode() == null || row.getSupplierCode().isBlank())
        throw new IllegalArgumentException("阶梯价格缺少供应商代码");
      String supplier = row.getSupplierCode().strip();
      var date = effectiveDate(row);
      LocalDate expiry;
      if (row.getExpDate() == null || row.getExpDate().isBlank()) throw new IllegalArgumentException("阶梯价格缺少失效日期");
      try { expiry = LocalDate.parse(row.getExpDate().strip()); }
      catch (DateTimeParseException invalid) {
        throw new IllegalArgumentException("阶梯价格失效日期无效：" + row.getExpDate(), invalid);
      }
      if (expiry.isBefore(date)) throw new IllegalArgumentException("阶梯价格失效日期早于生效日期");
      if (asOf.isBefore(date) || asOf.isAfter(expiry)) continue;
      var previous = latestDates.get(supplier);
      if (previous == null || date.isAfter(previous)) {
        latestDates.put(supplier, date);
        selected.put(supplier, new ArrayList<>(List.of(row)));
      } else if (date.equals(previous)) selected.get(supplier).add(row);
    }
    var result = new TreeMap<String, List<OdsMaterialPriceRow>>();
    selected.forEach((supplier, tiers) -> result.put(supplier, List.copyOf(tiers)));
    return Collections.unmodifiableMap(result);
  }

  private static LocalDate effectiveDate(OdsMaterialPriceRow row) {
    if (row.getEffDate() == null || row.getEffDate().isBlank())
      throw new IllegalArgumentException("阶梯价格缺少生效日期，无法判断最新一套阶梯");
    try { return LocalDate.parse(row.getEffDate().strip()); }
    catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("阶梯价格生效日期无效：" + row.getEffDate(), invalid);
    }
  }
}
