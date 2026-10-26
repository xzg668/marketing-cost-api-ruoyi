package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MonthlyPriceIdentityTest {

  @Test
  void familyNormalizesDeclaredConditionsWithoutLosingMaterialIdentity() {
    var normalized = new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), " commercial ", " 00123 ", " S-01 ",
        " kg ", " cny ", TaxBasis.EXCLUDED, "  ");
    var expected = new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), "COMMERCIAL", "00123", "S-01",
        "kg", "CNY", TaxBasis.EXCLUDED, null);

    assertThat(normalized).isEqualTo(expected);
    assertThat(normalized.materialCode()).isEqualTo("00123");
    assertThat(normalized).isNotEqualTo(new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), "COMMERCIAL", "123", "S-01",
        "kg", "CNY", TaxBasis.EXCLUDED, null));
    assertThat(normalized).isNotEqualTo(new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), "COMMERCIAL", "00123", "S-01",
        "kg", "CNY", TaxBasis.EXCLUDED, "S001"));
  }

  @Test
  void requestNormalizesDecimalTermsButPreservesUnprovidedAndTenderConditions() {
    var family = family();
    var request = new MonthlyPriceRequest(family, " 220 ",
        new NonCuConditions(new BoardTerms(new BigDecimal("100.00"), null, false)),
        new BigDecimal("1.00"));
    var same = new MonthlyPriceRequest(family, "220",
        new NonCuConditions(new BoardTerms(new BigDecimal("100"), null, false)),
        BigDecimal.ONE);

    assertThat(request).isEqualTo(same);
    assertThat(new NonCuConditions(new BoardTerms(null, null, false)))
        .isNotEqualTo(NonCuConditions.empty());
    assertThat(request.conditions()).isNotEqualTo(
        new NonCuConditions(new BoardTerms(new BigDecimal("100"), null, true)));
    assertThat(request.conditions()).isNotEqualTo(
        new NonCuConditions(new BoardTerms(new BigDecimal("101"), null, false)));
  }

  @Test
  void invalidOrganizationQuantityAndBoardPricesCannotBecomeRequests() {
    assertThatThrownBy(() -> new MonthlyPriceRequest(
        family(), "999", NonCuConditions.empty(), BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceRequest(
        family(), "210", NonCuConditions.empty(), new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoardTerms(BigDecimal.ZERO, null, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoardTerms(null, new BigDecimal("-1"), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceRequest(family(), "210",
        new NonCuConditions(new BoardTerms(null, null, false)), BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new MonthlyPriceRequest(
        family(), "210", NonCuConditions.empty(), new BigDecimal("0.00"))
        .priceBasisQuantity()).isEqualTo(BigDecimal.ZERO);
  }

  @Test
  void familyRequiresKnownBusinessScopeAndExplicitCurrencyAndTax() {
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
        "UNKNOWN", "00123", null, "kg", "CNY", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
        "COMMERCIAL", "00123", null, "kg", "人民币", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
        "COMMERCIAL", "00123", null, "kg", "CNY", null, null))
        .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(0, 10),
        "COMMERCIAL", "00123", null, "kg", "CNY", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void distinctDeclaredUnitsSpecificationsCurrencyAndTaxStayDistinct() {
    var key = family();
    assertThat(key).isNotEqualTo(new MonthlyPriceFamilyKey(key.month(), key.businessUnitType(),
        key.materialCode(), "S-02", key.unit(), key.currency(), key.taxBasis(), null));
    assertThat(key).isNotEqualTo(new MonthlyPriceFamilyKey(key.month(), key.businessUnitType(),
        key.materialCode(), key.specification(), "g", key.currency(), key.taxBasis(), null));
    assertThat(key).isNotEqualTo(new MonthlyPriceFamilyKey(key.month(), key.businessUnitType(),
        key.materialCode(), key.specification(), key.unit(), "USD", key.taxBasis(), null));
    assertThat(key).isNotEqualTo(new MonthlyPriceFamilyKey(key.month(), key.businessUnitType(),
        key.materialCode(), key.specification(), key.unit(), key.currency(), TaxBasis.INCLUDED, null));
  }

  @Test
  void canonicalFamilyEncodingIsExplicitUnambiguousAndStable() {
    assertThat(family().canonical()).isEqualTo(
        "MONTHLY_PRICE_FAMILY_V1|7:2026-10|10:COMMERCIAL|5:00123|4:S-01|2:kg|3:CNY|8:EXCLUDED|-1:|");
    var first = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "A|B",
        "C", "kg", "CNY", TaxBasis.EXCLUDED, null);
    var second = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "A",
        "B|C", "kg", "CNY", TaxBasis.EXCLUDED, null);
    assertThat(first.canonical()).isNotEqualTo(second.canonical());
    var absent = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "00123",
        null, "kg", "CNY", TaxBasis.EXCLUDED, null);
    var literalNull = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "00123",
        "null", "kg", "CNY", TaxBasis.EXCLUDED, null);
    assertThat(absent.canonical()).isNotEqualTo(literalNull.canonical());
  }

  @Test
  void familyLockOrderDoesNotDependOnCallerOrderAndAgreesWithIdentityEquality() {
    var previousMonth = new MonthlyPriceFamilyKey(YearMonth.of(2026, 9), "COMMERCIAL", "00123",
        "S-01", "kg", "CNY", TaxBasis.EXCLUDED, null);
    var current = family();
    var nextMaterial = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "00124",
        "S-01", "kg", "CNY", TaxBasis.EXCLUDED, null);
    var same = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "commercial", " 00123 ",
        " S-01 ", "kg", "cny", TaxBasis.EXCLUDED, " ");

    assertThat(new TreeSet<>(List.of(nextMaterial, current, previousMonth, same)))
        .containsExactly(previousMonth, current, nextMaterial);
    assertThat(current.compareTo(same)).isZero();
    assertThat(current.compareTo(nextMaterial)).isNegative();
    assertThat(nextMaterial.compareTo(current)).isPositive();
  }

  @Test
  void familyRejectsOversizedStorageKeysRatherThanTruncating() {
    var longest = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "M".repeat(64), "S-01", "kg", "CNY", TaxBasis.EXCLUDED, "V".repeat(64));
    assertThat(longest.materialCode()).hasSize(64);
    assertThat(longest.requestedSupplierCode()).hasSize(64);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "M".repeat(65), null, "kg", "CNY", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "M", null, "kg", "CNY", TaxBasis.EXCLUDED, "V".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void familyPreservesTheExistingFinanceAndScrapSpecificationBoundary() {
    // MPP-00 actual DDL: finance_effective_price and scrap use VARCHAR(512).
    String specification = "S".repeat(512);
    var family = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "00123", specification, "kg", "CNY", TaxBasis.EXCLUDED, null);

    assertThat(family.specification()).isEqualTo(specification);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "00123", "S".repeat(513), "kg", "CNY", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void familyPreservesTheExistingMaterialMasterPurchaseUnitBoundary() {
    // MPP-00 actual DDL: material_master_raw purchase units use VARCHAR(255).
    String unit = "U".repeat(255);
    var family = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "00123", "S-01", unit, "CNY", TaxBasis.EXCLUDED, null);

    assertThat(family.unit()).isEqualTo(unit);
    assertThatThrownBy(() -> new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "00123", "S-01", "U".repeat(256), "CNY", TaxBasis.EXCLUDED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MonthlyPriceFamilyKey family() {
    return new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "00123",
        "S-01", "kg", "CNY", TaxBasis.EXCLUDED, null);
  }
}
