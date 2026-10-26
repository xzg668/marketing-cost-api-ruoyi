package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.entity.monthly.OdsMaterialPriceRow;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OdsTierPriceVersionsTest {
  private static java.util.Map<String,List<OdsMaterialPriceRow>> selected(List<OdsMaterialPriceRow> rows) {
    return OdsTierPriceVersions.latestPerSupplier(rows, LocalDate.of(2026,10,25));
  }
  private static OdsMaterialPriceRow tier(long id, String supplier, String effectiveDate,
      String lower, String upper, String price) {
    var row = new OdsMaterialPriceRow();
    row.setId(id); row.setSupplierCode(supplier); row.setEffDate(effectiveDate);
    row.setExpDate("2026-12-31"); row.setMaterialCode("201850559"); row.setUnit("只");
    row.setJietiDown(new BigDecimal(lower));
    row.setJietiUp(upper == null ? null : new BigDecimal(upper));
    row.setPrice(new BigDecimal(price));
    return row;
  }

  @Test void keepsEveryBandOfTheLatestVersionInsteadOfPickingOneRowById() {
    var old = tier(9999, "S000116", "2025-01-01", "1", null, "99");
    var first = tier(2649, "S000116", "2026-01-01", "1", "20000", "0.570");
    var second = tier(4, "S000116", "2026-01-01", "20000", "200000", "0.375");
    var last = tier(2742, "S000116", "2026-01-01", "200000", null, "0.250");
    for (var input : List.of(List.of(old, first, second, last), List.of(last, second, first, old))) {
      var result = selected(input);
      assertThat(result.get("S000116")).containsExactlyInAnyOrder(first, second, last);
      assertThat(result.get("S000116")).allSatisfy(row -> {
        assertThat(row.getEffDate()).isEqualTo("2026-01-01");
        assertThat(row.getExpDate()).isEqualTo("2026-12-31");
      });
    }
  }

  @Test void latestVersionsAreChosenSeparatelyForEachSupplier() {
    var aOld = tier(1, "A", "2026-01-01", "1", null, "99");
    var aLatest = tier(2, "A", "2026-10-01", "1", null, "10");
    var bLatest = tier(3, "B", "2026-06-01", "1", null, "20");
    var result = selected(List.of(aOld, aLatest, bLatest));
    assertThat(result.get("A")).containsExactly(aLatest);
    assertThat(result.get("B")).containsExactly(bLatest);
  }

  @Test void olderVersionIsNotResurrectedWhenQuantityDoesNotMatchTheLatestVersion() {
    var old = tier(1, "A", "2026-01-01", "1", null, "99");
    var latest = tier(2, "A", "2026-10-01", "50000", null, "10");
    var selected = selected(List.of(old, latest)).get("A");
    var demand = OdsTierAnnualUsage.require(new BigDecimal("360000"), "PIECES");
    assertThat(selected).containsExactly(latest);
    assertThat(selected.stream().filter(row -> OdsTierAnnualUsage.annualBand(new QuantityInterval(
        row.getJietiDown(), true, row.getJietiUp(), false)).contains(demand.annualPieces()))).isEmpty();
  }

  @Test void missingSourceRowsReturnNoTierCandidates() {
    assertThat(selected(List.of())).isEmpty();
  }

  @Test void malformedTierDatesAndMissingSupplierCannotSilentlyChooseAHistoricalVersion() {
    assertThatThrownBy(() -> selected(List.of(
        tier(1, "A", "2026-02-30", "1", null, "10"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("生效日期无效");
    assertThatThrownBy(() -> selected(List.of(
        tier(1, " ", "2026-01-01", "1", null, "10"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("供应商");
  }
  @Test void excludesExpiredAndFutureVersionsBeforeSelectingTheLatestValidSet() {
    var expired = tier(1, "A", "2026-01-01", "1", null, "5"); expired.setExpDate("2026-09-30");
    var active = tier(2, "A", "2026-10-01", "1", null, "10"); active.setExpDate("2026-11-30");
    var future = tier(3, "A", "2026-12-01", "1", null, "20"); future.setExpDate("2027-11-30");
    assertThat(selected(List.of(expired, future, active)).get("A")).containsExactly(active);
    assertThat(selected(List.of(expired, future))).isEmpty();
  }

  @Test void effectiveAndExpiryDaysAreBothInsideTheValidDateRange() {
    var row = tier(1, "A", "2026-10-01", "1", null, "10"); row.setExpDate("2026-10-31");
    for (var date : List.of(LocalDate.of(2026,10,1), LocalDate.of(2026,10,31)))
      assertThat(OdsTierPriceVersions.latestPerSupplier(List.of(row), date).get("A")).containsExactly(row);
    for (var date : List.of(LocalDate.of(2026,9,30), LocalDate.of(2026,11,1)))
      assertThat(OdsTierPriceVersions.latestPerSupplier(List.of(row), date)).isEmpty();
  }
}
