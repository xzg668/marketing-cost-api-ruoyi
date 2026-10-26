package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OdsTierAnnualUsageTest {
  private static boolean matches(OdsTierAnnualUsage.Quantity demand, QuantityInterval monthlyBand) {
    return OdsTierAnnualUsage.annualBand(monthlyBand).contains(demand.annualPieces());
  }
  private static QuantityInterval band(String low, boolean lowerIncluded, String high, boolean upperIncluded) {
    return new QuantityInterval(low == null ? null : new BigDecimal(low), lowerIncluded,
        high == null ? null : new BigDecimal(high), upperIncluded);
  }

  @Test void threeTenThousandPiecesMeans2500MonthlyRatherThanAQuarterPiece() {
    var demand = OdsTierAnnualUsage.require(new BigDecimal("3"), "TEN_THOUSAND_PIECES");
    assertThat(demand.annualPieces()).isEqualByComparingTo("30000");
    assertThat(matches(demand,band("2500", true, "2500", true))).isTrue();
    assertThat(matches(demand,band("0", true, "1", false))).isFalse();
  }

  @Test void annual360000MatchesTheRealMaterialMiddleTier() {
    var demand = OdsTierAnnualUsage.require(new BigDecimal("360000"), "PIECES");
    assertThat(matches(demand,band("1", true, "20000", false))).isFalse();
    assertThat(matches(demand,band("20000", true, "200000", false))).isTrue();
    assertThat(matches(demand,band("200000", true, null, false))).isFalse();
  }

  @Test void annual100IsNeitherRoundedDownToEightNorRoundedUpToNine() {
    var demand = OdsTierAnnualUsage.require(new BigDecimal("100"), "PIECES");
    assertThat(matches(demand,band("8", true, "9", false))).isTrue();
    assertThat(matches(demand,band("8", true, "8", true))).isFalse();
    assertThat(matches(demand,band("9", true, "9", true))).isFalse();
  }

  @Test void exactComparisonAvoidsRoundingIntoAnAdjacentExtremelyNarrowTier() {
    var demand = OdsTierAnnualUsage.require(BigDecimal.ONE, "PIECES");
    assertThat(matches(demand,band("0.08333333333333333333333333333333333333", true,
        "0.08333333333333333333333333333333333334", false))).isTrue();
    assertThat(matches(demand,band("0", true, "0.08333333333333333333333333333333333333", true)))
        .isFalse();
  }

  @Test void respectsTheSuppliedBoundaryPolicyWithoutChangingIt() {
    var demand = OdsTierAnnualUsage.require(new BigDecimal("240000"), "PIECES");
    assertThat(matches(demand,band("1", true, "20000", false))).isFalse();
    assertThat(matches(demand,band("1", true, "20000", true))).isTrue();
    assertThat(matches(demand,band("20000", true, null, false))).isTrue();
    assertThat(matches(demand,band("20000", false, null, false))).isFalse();
  }

  @Test void explicitPieceValuesAreNotMultipliedByTenThousand() {
    var demand = OdsTierAnnualUsage.require(new BigDecimal("3"), "PIECES");
    assertThat(demand.annualPieces()).isEqualByComparingTo("3");
    assertThat(matches(demand,band("0.25", true, "0.25", true))).isTrue();
  }

  @Test void whenTierMatchingActuallyNeedsUsageMissingAndNonpositiveValuesAreExplained() {
    assertThatThrownBy(() -> OdsTierAnnualUsage.require(null, "PIECES"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺少当前报价产品的年用量");
    for (String value : new String[] {"0", "-1"})
      assertThatThrownBy(() -> OdsTierAnnualUsage.require(new BigDecimal(value), "PIECES"))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("须大于零");
  }

  @Test void doesNotGuessAnAbsentOrUnknownUsageUnit() {
    for (String unit : new String[] {null, "", "KG", "UNKNOWN"})
      assertThatThrownBy(() -> OdsTierAnnualUsage.require(BigDecimal.ONE, unit))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单位");
  }
}
