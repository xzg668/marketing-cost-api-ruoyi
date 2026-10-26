package com.sanhua.marketingcost.service.pricing.monthly;

import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;

/** Stable competition scope, independent of the selected source or adopted price. */
public record MonthlyPriceFamilyKey(
    YearMonth month,
    String businessUnitType,
    String materialCode,
    String specification,
    String unit,
    String currency,
    TaxBasis taxBasis,
    String requestedSupplierCode) implements Comparable<MonthlyPriceFamilyKey> {

  public MonthlyPriceFamilyKey {
    Objects.requireNonNull(month, "Accounting month is required");
    if (month.getYear() < 1 || month.getYear() > 9999) {
      throw new IllegalArgumentException("Accounting month must use a four-digit positive year");
    }
    businessUnitType = required(businessUnitType, "Business unit").toUpperCase(Locale.ROOT);
    if (!businessUnitType.equals("COMMERCIAL") && !businessUnitType.equals("HOUSEHOLD")) {
      throw new IllegalArgumentException("Business unit must be COMMERCIAL or HOUSEHOLD");
    }
    materialCode = bounded(required(materialCode, "Material code"), "Material code", 64);
    specification = bounded(optional(specification), "Specification", 512);
    // Unit conversion belongs to the existing adapter; do not guess aliases here.
    unit = bounded(required(unit, "Pricing unit"), "Pricing unit", 255);
    currency = required(currency, "Currency").toUpperCase(Locale.ROOT);
    if (!currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("Currency must be an explicit three-letter code");
    }
    Objects.requireNonNull(taxBasis, "Tax basis is required");
    requestedSupplierCode = bounded(optional(requestedSupplierCode), "Requested supplier code", 64);
  }

  public String canonical() {
    return MonthlyPriceCanonical.fields("MONTHLY_PRICE_FAMILY_V1", month.toString(),
        businessUnitType, materialCode, specification, unit, currency, taxBasis.name(),
        requestedSupplierCode);
  }

  @Override
  public int compareTo(MonthlyPriceFamilyKey other) {
    return canonical().compareTo(Objects.requireNonNull(other, "Other family is required").canonical());
  }

  private static String required(String value, String field) {
    String normalized = optional(value);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String bounded(String value, String field, int maximum) {
    if (value != null && value.length() > maximum) {
      throw new IllegalArgumentException(field + " cannot exceed " + maximum + " characters");
    }
    return value;
  }
}
