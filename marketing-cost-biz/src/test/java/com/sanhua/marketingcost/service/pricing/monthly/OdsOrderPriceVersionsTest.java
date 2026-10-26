package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class OdsOrderPriceVersionsTest {
  private static OdsOrderPriceVersions.Price row(String supplier, String date, String price) {
    return new OdsOrderPriceVersions.Price(supplier, LocalDate.parse(date), new BigDecimal(price));
  }

  @Test void actualMisumiRowsChoose232921RegardlessOfTheirInputOrder() {
    var older = row("S002247", "2025-06-10", "23.29210000");
    var line9 = row("S002247", "2025-09-04", "23.29200000");
    var line7 = row("S002247", "2025-09-04", "23.29210000");
    for (var input : List.of(List.of(older, line9, line7), List.of(line7, line9, older),
        List.of(line9, older, line7))) {
      var selected = OdsOrderPriceVersions.latestPerSupplier(input).get("S002247");
      assertThat(selected.effectiveDate()).isEqualTo(LocalDate.of(2025, 9, 4));
      assertThat(selected.price()).isEqualByComparingTo("23.2921");
    }
  }

  @Test void actualMouldRowsChoose12389381WithinTheSameSupplier() {
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(
        row("S002293", "2026-09-24", "1194.69030000"),
        row("S002293", "2026-09-24", "1238.93810000")));
    assertThat(selected.get("S002293").price()).isEqualByComparingTo("1238.9381");
  }

  @Test void newerLowerPriceWinsOverAnOlderHigherPrice() {
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(
        row("S1", "2026-09-01", "999"), row("S1", "2026-10-20", "11"),
        row("S1", "2026-10-20", "12")));
    assertThat(selected.get("S1").price()).isEqualByComparingTo("12");
  }

  @Test void futureEffectiveDateRemainsEligible() {
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(
        row("S1", "2026-10-24", "11"), row("S1", "2026-11-01", "12")));
    assertThat(selected.get("S1").effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(selected.get("S1").price()).isEqualByComparingTo("12");
  }

  @Test void keepsOtherSuppliersForTheExistingSupplyRatioSelection() {
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(
        row("S002293", "2026-09-24", "1194.6903"),
        row("S002293", "2026-09-24", "1238.9381"),
        row("S002003", "2026-04-03", "2920.354")));
    assertThat(selected).hasSize(2);
    assertThat(selected.get("S002293").price()).isEqualByComparingTo("1238.9381");
    assertThat(selected.get("S002003").price()).isEqualByComparingTo("2920.354");
  }

  @Test void duplicatesAndDifferentDecimalScalesHaveTheSameBusinessResult() {
    var a = row("S1", "2026-10-24", "12.00000000");
    var b = row("S1", "2026-10-24", "12");
    assertThat(OdsOrderPriceVersions.latestPerSupplier(List.of(a, b, a)))
        .isEqualTo(OdsOrderPriceVersions.latestPerSupplier(List.of(b)));
  }

  @Test void requiresASupplierAndValidPriceWithoutInventingADate() {
    assertThatThrownBy(() -> row(" ", "2026-10-24", "12")).isInstanceOf(IllegalArgumentException.class);
    assertThat(new OdsOrderPriceVersions.Price("S1", null, BigDecimal.ONE).effectiveDate()).isNull();
    assertThatThrownBy(() -> row("S1", "2026-10-24", "-1")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void undatedSupplierStillHasAnOrderPriceCandidate() {
    var candidate = new OdsOrderPriceVersions.Price("S001044", null, new BigDecimal("30.05000000"));
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(candidate)).get("S001044");
    assertThat(selected.effectiveDate()).isNull();
    assertThat(selected.price()).isEqualByComparingTo("30.05");
  }

  @Test void datedRowsWinWhenTheSameSupplierAlsoHasUndatedRows() {
    var undated = new OdsOrderPriceVersions.Price("S002309", null, new BigDecimal("318.4690"));
    var dated = row("S002309", "2026-10-12", "636.9381");
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(undated, dated)).get("S002309");
    assertThat(selected).isEqualTo(dated);
  }

  @Test void equalNullDatesKeepTheHighestPriceWithoutLosingTheSupplier() {
    var a = new OdsOrderPriceVersions.Price("S1", null, new BigDecimal("10"));
    var b = new OdsOrderPriceVersions.Price("S1", null, new BigDecimal("12"));
    var selected = OdsOrderPriceVersions.latestPerSupplier(List.of(a, b, a)).get("S1");
    assertThat(selected.price()).isEqualByComparingTo("12");
  }
  @Test void evenAHigherUndatedPriceDoesNotOverrideTheLatestDatedPrice() {
    var undated = new OdsOrderPriceVersions.Price("S1", null, new BigDecimal("999"));
    var dated = row("S1", "2026-10-12", "10");
    for (var input : List.of(List.of(undated, dated), List.of(dated, undated)))
      assertThat(OdsOrderPriceVersions.latestPerSupplier(input).get("S1")).isEqualTo(dated);
  }
}
