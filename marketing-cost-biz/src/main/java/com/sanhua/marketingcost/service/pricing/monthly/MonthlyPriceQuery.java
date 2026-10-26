package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/** Read-only ledger contract. Decimal values remain strings, including the saved evidence. */
public final class MonthlyPriceQuery {
  private MonthlyPriceQuery() {}

  public record Filter(
      String month,
      String materialCode,
      String organization,
      String pricingType,
      String specification,
      String unit,
      String currency,
      String taxBasis,
      String supplier,
      int page,
      int size) {
    public Filter {
      if (month == null || !month.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])"))
        throw new IllegalArgumentException("请选择核算月份（YYYY-MM）");
      YearMonth.parse(month);
      materialCode = text(materialCode);
      organization = text(organization);
      pricingType = text(pricingType);
      specification = text(specification);
      unit = text(unit);
      currency = text(currency);
      taxBasis = text(taxBasis);
      supplier = text(supplier);
      option(organization, Set.of("210", "220"), "组织");
      option(pricingType, Set.of("NORMAL", "HOUSEHOLD_INQUIRY"), "计价类型");
      option(taxBasis, Set.of("INCLUDED", "EXCLUDED"), "税口径");
      if (currency != null && !currency.matches("[A-Z]{3}"))
        throw new IllegalArgumentException("币种应为三位大写代码");
      pagination(page, size);
    }

    public long offset() {
      return (long) (page - 1) * size;
    }

    public Integer organizationMask() {
      return organization == null ? null : "210".equals(organization) ? 1 : 2;
    }
  }

  public record Page<T>(List<T> rows, long total, int page, int size) {
    public Page {
      rows = List.copyOf(rows);
    }
  }

  public record Source(
      String type,
      Long recordId,
      String version,
      String batch,
      String description,
      Long householdSourceId) {}

  public record Entry(
      long id,
      String month,
      String materialCode,
      String materialName,
      String specification,
      String unit,
      String currency,
      String taxBasis,
      String requestedSupplierCode,
      String supplierCode,
      String supplierName,
      String pricingType,
      String price,
      String householdRate,
      JsonNode applicability,
      Source source,
      String evidenceHash,
      String createdBy,
      LocalDateTime createdAt) {}

  public record Detail(Entry price, JsonNode firstUse, JsonNode savedEvidence) {}

  public record Reference(
      long versionId,
      String oaNo,
      long oaFormItemId,
      String productCode,
      String versionNo,
      String costRunNo,
      String status,
      String pricingMonth,
      String totalCost,
      String finalQuoteAmount,
      String consumerOrganizations) {}

  public static void pagination(int page, int size) {
    if (page < 1 || page > 1000000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页范围无效");
  }

  private static String text(String value) {
    if (value == null || value.isBlank()) return null;
    value = value.strip();
    if (value.length() > 255) throw new IllegalArgumentException("查询条件过长");
    return value;
  }

  private static void option(String value, Set<String> options, String label) {
    if (value != null && !options.contains(value)) throw new IllegalArgumentException(label + "无效");
  }
}
