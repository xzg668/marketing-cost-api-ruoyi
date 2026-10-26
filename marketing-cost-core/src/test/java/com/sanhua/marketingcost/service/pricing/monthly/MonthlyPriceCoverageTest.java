package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlyPriceCoverageTest {

  @ParameterizedTest
  @ValueSource(strings = {"1E+4096", "1E-4095"})
  void canonicalBoardConditionsRejectOversizedPlainNumberExpansion(String value) {
    var conditions = ConditionDomain.exact(new NonCuConditions(
        new BoardTerms(new BigDecimal(value), null, false)));

    assertThatThrownBy(conditions::canonical).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1E+4096", "1E-4095"})
  void canonicalQuantityCoverageRejectsOversizedPlainNumberExpansion(String value) {
    var coverage = new MonthlyPriceCoverage(Set.of("210"),
        new QuantityInterval(new BigDecimal(value), true, null, false), ConditionDomain.any());

    assertThatThrownBy(coverage::canonical).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceIndependentTermsAndUnprovidedBoardTermsHaveDifferentCoverage() {
    var absentBoard = new NonCuConditions(new BoardTerms(null, null, false));
    var specifiedBoard = new NonCuConditions(new BoardTerms(new BigDecimal("100"), null, false));
    var exact = new MonthlyPriceCoverage(
        Set.of("220"), QuantityInterval.all(), ConditionDomain.exact(absentBoard));
    var any = new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any());

    assertThat(exact.matches(request("220", absentBoard, "1"))).isTrue();
    assertThat(exact.matches(request("220", specifiedBoard, "1"))).isFalse();
    assertThat(exact.matches(request("220", NonCuConditions.empty(), "1"))).isFalse();
    assertThat(any.matches(request("220", absentBoard, "1"))).isTrue();
    assertThat(any.matches(request("220", specifiedBoard, "1"))).isTrue();
  }

  @Test
  void allQuantityDomainReusesOneApplicabilityAcrossQuantitiesWithoutCrossingOrganizations() {
    var normal = new MonthlyPriceCoverage(Set.of(" 210 "), QuantityInterval.all(), ConditionDomain.any());
    var shared = new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any());

    assertThat(normal.matches(request("210", NonCuConditions.empty(), "1"))).isTrue();
    assertThat(normal.matches(request("210", NonCuConditions.empty(), "2000.125"))).isTrue();
    assertThat(normal.matches(request("220", NonCuConditions.empty(), "1"))).isFalse();
    assertThat(shared.matches(request("210", NonCuConditions.empty(), "1"))).isTrue();
    assertThat(shared.matches(request("220", NonCuConditions.empty(), "1"))).isTrue();
  }

  @Test
  void frozenQuantityBoundsUseExactDecimalAndEndpointSemantics() {
    var interval = new QuantityInterval(new BigDecimal("1.00"), true, new BigDecimal("100.0"), false);
    var coverage = new MonthlyPriceCoverage(Set.of("210"), interval, ConditionDomain.any());

    assertThat(interval).isEqualTo(new QuantityInterval(BigDecimal.ONE, true, new BigDecimal("100"), false));
    assertThat(coverage.matches(request("210", NonCuConditions.empty(), "1"))).isTrue();
    assertThat(coverage.matches(request("210", NonCuConditions.empty(), "99.999999999999999999"))).isTrue();
    assertThat(coverage.matches(request("210", NonCuConditions.empty(), "100"))).isFalse();
    assertThat(coverage.matches(request("210", NonCuConditions.empty(), "0.999999999999999999"))).isFalse();
    assertThat(new QuantityInterval(null, true, null, true)).isEqualTo(QuantityInterval.all());
    assertThatThrownBy(() -> new QuantityInterval(BigDecimal.TEN, true, BigDecimal.ONE, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuantityInterval(BigDecimal.ONE, false, BigDecimal.ONE, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void finiteConditionDomainsAreDefensiveAndKeepExplicitExclusions() {
    var absentBoard = new NonCuConditions(new BoardTerms(null, null, false));
    var terms = new HashSet<>(Set.of(absentBoard));
    var excluded = new ConditionDomain(ConditionDomain.Mode.EXCLUDING, terms);
    terms.clear();

    assertThat(excluded.matches(absentBoard)).isFalse();
    assertThat(excluded.matches(NonCuConditions.empty())).isTrue();
    assertThat(ConditionDomain.exact(new NonCuConditions(
        new BoardTerms(new BigDecimal("100.00"), null, false))).matches(new NonCuConditions(
        new BoardTerms(new BigDecimal("100"), null, false)))).isTrue();
    assertThat(new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of())).isEqualTo(ConditionDomain.any());
    assertThatThrownBy(() -> new ConditionDomain(ConditionDomain.Mode.EXACT, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConditionDomain(ConditionDomain.Mode.ANY, Set.of(absentBoard)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unusableOrganizationCoverageCannotBeAdopted() {
    assertThatThrownBy(() -> new MonthlyPriceCoverage(Set.of(), QuantityInterval.all(), ConditionDomain.any()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MonthlyPriceCoverage(Set.of("999"), QuantityInterval.all(), ConditionDomain.any()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void newQuantityBoundariesOnlyFillTheComplementOfTheFrozenBand() {
    var adopted = interval("1", true, "100", true);
    var changedSource = interval("50", true, "200", true);

    assertThat(changedSource.intersection(adopted)).contains(interval("50", true, "100", true));
    assertThat(changedSource.subtract(adopted)).containsExactly(interval("100", false, "200", true));
    assertThat(interval("1", true, "50", true).subtract(adopted)).isEmpty();
    assertThat(adopted).isEqualTo(interval("1", true, "100", true));
  }

  @Test
  void quantityDifferenceKeepsSingletonsAndUnboundedOpenEdges() {
    var closed = interval("1", true, "100", true);
    var interior = interval("1", false, "100", false);
    assertThat(closed.subtract(interior))
        .containsExactly(interval("1", true, "1", true), interval("100", true, "100", true));
    assertThat(QuantityInterval.all().subtract(interval("5", true, "5", true)))
        .containsExactly(interval(null, false, "5", false), interval("5", false, null, false));
    assertThat(interval("1", true, "2", false).intersection(interval("2", true, "3", true))).isEmpty();
    assertThat(interval("1", true, "2", true).intersection(interval("2", true, "3", true)))
        .contains(interval("2", true, "2", true));
    assertThat(closed.subtract(QuantityInterval.all())).isEmpty();
    assertThat(closed.subtract(interval("101", true, "200", true))).containsExactly(closed);
  }

  @Test
  void finiteAndComplementConditionDomainsHaveExactIntersectionAndDifference() {
    var absent = new NonCuConditions(new BoardTerms(null, null, false));
    var hundred = new NonCuConditions(new BoardTerms(new BigDecimal("100"), null, false));
    var tender = new NonCuConditions(new BoardTerms(null, null, true));
    var two = new ConditionDomain(ConditionDomain.Mode.EXACT, Set.of(absent, hundred));
    var notAbsent = new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of(absent));
    var notHundred = new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of(hundred));

    assertThat(two.intersection(notAbsent)).contains(ConditionDomain.exact(hundred));
    assertThat(two.subtract(notAbsent)).contains(ConditionDomain.exact(absent));
    assertThat(ConditionDomain.any().subtract(notAbsent)).contains(ConditionDomain.exact(absent));
    assertThat(notAbsent.subtract(notHundred)).contains(ConditionDomain.exact(hundred));
    assertThat(notAbsent.intersection(notHundred))
        .contains(new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of(absent, hundred)));
    assertThat(notAbsent.subtract(ConditionDomain.exact(hundred)))
        .contains(new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of(absent, hundred)));
    assertThat(ConditionDomain.exact(tender).intersection(two)).isEmpty();
    assertThat(two.subtract(ConditionDomain.any())).isEmpty();
  }

  @Test
  void sharedScrapApplicabilityFillsOnlyTheUnadoptedOrganization() {
    // Declared scope fixture, based on the existing scrap reader's shared applicability.
    // This exercises coverage contracts, not the future source adapter or a database transaction.
    var sharedScrap = new MonthlyPriceCoverage(
        Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any());
    var adoptedPurchase = new MonthlyPriceCoverage(
        Set.of("210"), QuantityInterval.all(), ConditionDomain.any());
    var remaining = sharedScrap.subtract(adoptedPurchase);

    assertThat(remaining).containsExactly(new MonthlyPriceCoverage(
        Set.of("220"), QuantityInterval.all(), ConditionDomain.any()));
    assertThat(remaining.getFirst().matches(request("220", new NonCuConditions(
        new BoardTerms(new BigDecimal("100"), null, true)), "7"))).isTrue();
    assertThat(remaining.getFirst().matches(request("210", NonCuConditions.empty(), "7"))).isFalse();
    assertThat(sharedScrap.intersection(adoptedPurchase)).contains(adoptedPurchase);
  }

  @Test
  void sharedSettlementApplicabilityPreservesAlreadyAdoptedBoardAndQuantityConditions() {
    // Settlement can share both organizations; a prior specific price keeps its occupied conditions.
    var absentBoard = new NonCuConditions(new BoardTerms(null, null, false));
    var providedBoard = new NonCuConditions(new BoardTerms(new BigDecimal("100"), null, false));
    var candidate = new MonthlyPriceCoverage(
        Set.of("210", "220"), interval("1", true, "200", true), ConditionDomain.any());
    var adopted = new MonthlyPriceCoverage(
        Set.of("220"), interval("50", true, "100", true), ConditionDomain.exact(absentBoard));
    var remaining = candidate.subtract(adopted);

    assertThat(remaining).containsExactlyInAnyOrder(
        new MonthlyPriceCoverage(Set.of("210"), candidate.quantities(), ConditionDomain.any()),
        new MonthlyPriceCoverage(Set.of("220"), interval("1", true, "50", false), ConditionDomain.any()),
        new MonthlyPriceCoverage(Set.of("220"), interval("100", false, "200", true), ConditionDomain.any()),
        new MonthlyPriceCoverage(Set.of("220"), adopted.quantities(),
            new ConditionDomain(ConditionDomain.Mode.EXCLUDING, Set.of(absentBoard))));
    assertThat(remaining.stream().filter(c -> c.matches(request("220", absentBoard, "75")))).isEmpty();
    assertThat(remaining.stream().filter(c -> c.matches(request("220", providedBoard, "75")))).hasSize(1);
    assertThat(remaining.stream().filter(c -> c.matches(request("210", NonCuConditions.empty(), "75")))).hasSize(1);

    for (int i = 0; i < remaining.size(); i++) {
      assertThat(remaining.get(i).intersection(adopted)).isEmpty();
      for (int j = i + 1; j < remaining.size(); j++) {
        assertThat(remaining.get(i).intersection(remaining.get(j))).isEmpty();
      }
    }
    for (String quantity : List.of("0", "1", "49.999999999999", "50", "75", "100", "100.000000000001", "200", "201")) {
      for (var terms : List.of(NonCuConditions.empty(), absentBoard, providedBoard)) {
        var point = request("220", terms, quantity);
        long remainingMatches = remaining.stream().filter(c -> c.matches(point)).count();
        assertThat(remainingMatches).isEqualTo(candidate.matches(point) && !adopted.matches(point) ? 1 : 0);
      }
    }
  }

  @Test
  void disjointCoverageLeavesTheCandidateIntactAndFullCoverageLeavesNothing() {
    var first = new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any());
    var otherOrganization = new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any());
    assertThat(first.intersection(otherOrganization)).isEmpty();
    assertThat(first.subtract(otherOrganization)).containsExactly(first);
    assertThat(first.subtract(first)).isEmpty();
  }

  @Test
  void canonicalCoverageExplicitlyOrdersSetsAndPreservesMissingTermMeaning() {
    var absent = new NonCuConditions(new BoardTerms(null, null, false));
    var supplied = new NonCuConditions(new BoardTerms(new BigDecimal("100.00"), null, true));
    var firstTerms = new ConditionDomain(ConditionDomain.Mode.EXACT,
        new LinkedHashSet<>(List.of(absent, supplied)));
    var reversedTerms = new ConditionDomain(ConditionDomain.Mode.EXACT,
        new LinkedHashSet<>(List.of(supplied, absent)));
    var first = new MonthlyPriceCoverage(new LinkedHashSet<>(List.of("210", "220")),
        interval("1.00", true, "100.00", false), firstTerms);
    var reversed = new MonthlyPriceCoverage(new LinkedHashSet<>(List.of("220", "210")),
        interval("1", true, "100", false), reversedTerms);

    assertThat(firstTerms.canonical()).startsWith("CONDITION_DOMAIN_V1|")
        .isEqualTo(reversedTerms.canonical());
    assertThat(first.canonical()).startsWith("MONTHLY_PRICE_COVERAGE_V1|")
        .isEqualTo(reversed.canonical());
    assertThat(ConditionDomain.exact(absent).canonical())
        .isNotEqualTo(ConditionDomain.exact(NonCuConditions.empty()).canonical());
    assertThat(firstTerms.canonical()).isNotEqualTo(ConditionDomain.any().canonical());
    assertThat(firstTerms.canonical()).isNotEqualTo(
        new ConditionDomain(ConditionDomain.Mode.EXCLUDING, firstTerms.values()).canonical());
  }

  private static QuantityInterval interval(String low, boolean lowInclusive, String high, boolean highInclusive) {
    return new QuantityInterval(low == null ? null : new BigDecimal(low), lowInclusive,
        high == null ? null : new BigDecimal(high), highInclusive);
  }

  private static MonthlyPriceRequest request(String org, NonCuConditions conditions, String quantity) {
    var family = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "00123",
        null, "kg", "CNY", TaxBasis.EXCLUDED, null);
    return new MonthlyPriceRequest(family, org, conditions, new BigDecimal(quantity));
  }
}
