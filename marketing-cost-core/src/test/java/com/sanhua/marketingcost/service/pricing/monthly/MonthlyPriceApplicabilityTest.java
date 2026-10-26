package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonthlyPriceApplicabilityTest {

  @Test
  void oneAdoptedFactRetainsBothRemainingQuantityFragmentsAndAStableIdentity() {
    var candidate = MonthlyPriceApplicability.of(coverage("1", true, "100", true));
    var adopted = MonthlyPriceApplicability.of(coverage("40", true, "60", true));
    var remaining = candidate.subtract(adopted).orElseThrow();
    var low = coverage("1", true, "40", false);
    var high = coverage("60", false, "100", true);

    assertThat(remaining.fragments()).containsExactlyInAnyOrder(low, high);
    assertThat(remaining.matches(request("10"))).isTrue();
    assertThat(remaining.matches(request("80"))).isTrue();
    assertThat(remaining.matches(request("50"))).isFalse();
    assertThat(remaining.matches(request("40"))).isFalse();
    assertThat(remaining.matches(request("60"))).isFalse();
    assertThat(remaining.matches(request("1"))).isTrue();
    assertThat(remaining.matches(request("100"))).isTrue();
    assertThat(remaining.subtract(candidate)).isEmpty();

    var input = new ArrayList<>(List.of(high, low, high));
    var reordered = new MonthlyPriceApplicability(input);
    input.clear();
    assertThat(reordered).isEqualTo(remaining);
    assertThat(reordered.canonical()).isEqualTo(remaining.canonical());
    assertThat(reordered.canonical()).isNotEqualTo(candidate.canonical());
    assertThatThrownBy(() -> reordered.fragments().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void emptyOrOverlappingFragmentsCannotDescribeOneAdoptedFact() {
    assertThatThrownBy(() -> new MonthlyPriceApplicability(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceApplicability(List.of(
        coverage("1", true, "40", true), coverage("40", true, "100", true))))
        .isInstanceOf(IllegalArgumentException.class);

    var touching = new MonthlyPriceApplicability(List.of(
        coverage("1", true, "40", false), coverage("40", true, "100", true)));
    assertThat(touching.fragments()).hasSize(2);
    assertThat(touching.matches(request("40"))).isTrue();
  }

  private static MonthlyPriceCoverage coverage(
      String lower, boolean lowerInclusive, String upper, boolean upperInclusive) {
    return new MonthlyPriceCoverage(Set.of("210", "220"),
        new QuantityInterval(new BigDecimal(lower), lowerInclusive, new BigDecimal(upper), upperInclusive),
        ConditionDomain.any());
  }

  private static MonthlyPriceRequest request(String quantity) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL",
        "00123", null, "kg", "CNY", TaxBasis.EXCLUDED, null),
        "210", NonCuConditions.empty(), new BigDecimal(quantity));
  }
}
