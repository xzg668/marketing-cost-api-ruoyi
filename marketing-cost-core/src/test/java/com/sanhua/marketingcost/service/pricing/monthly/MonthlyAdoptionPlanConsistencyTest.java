package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PricingType;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Use;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlanConsistency.Outcome;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlanConsistency.Reason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlyAdoptionPlanConsistencyTest {

  @Test
  void householdWinnerRequiresReplanningEvenWhenThePriceIsUnchanged() {
    var family = new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), "COMMERCIAL", "0001", null, "EA", "CNY",
        TaxBasis.EXCLUDED, null);
    var request = new MonthlyPriceRequest(
        family, "210", NonCuConditions.empty(), BigDecimal.ONE);
    var coverage = new MonthlyPriceCoverage(
        Set.of("210"), QuantityInterval.all(), ConditionDomain.any());
    var evidence = SourceEvidence.ofJson(
        "{\"sourceType\":\"TEST_FIXTURE\",\"sourceRevision\":\"v1\"}");
    var planned = new MonthlyAdoptionPlan(
        Map.of(request, new Selection(null, coverage,
            PriceFact.normal(new BigDecimal("20"), evidence, Set.of()))),
        List.of(new Use("root/A", request, BigDecimal.ONE)), Set.of());
    var resolved = new MonthlyAdoptionPlan(
        Map.of(request, new Selection(17L, coverage,
            PriceFact.household(new BigDecimal("20"), new BigDecimal("0.085"), evidence))),
        List.of(new Use("root/A", request, BigDecimal.ONE)), Set.of());

    var result = MonthlyAdoptionPlanConsistency.verify(planned, resolved);

    assertThat(result.outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
    assertThat(result.conflicts().get(request)).contains(Reason.TYPE_CHANGED);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("differentEconomicFacts")
  void changedEconomicFactRequiresReplanning(
      String description, PriceFact plannedFact, PriceFact savedFact, String reason) {
    var request = request("A");

    var result = MonthlyAdoptionPlanConsistency.verify(
        single(request, null, plannedFact), single(request, 17L, savedFact));

    assertThat(result.outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
    assertThat(result.conflicts().get(request)).extracting(Reason::name).contains(reason);
  }

  static Stream<Arguments> differentEconomicFacts() {
    var source = SourceEvidence.ofJson("{\"sourceRevision\":\"v1\",\"supplier\":\"S1\"}");
    return Stream.of(
        Arguments.of("ordinary price", normal("20", source), normal("21", source), "VALUE_CHANGED"),
        Arguments.of("household P", household("20", ".085", source),
            household("21", ".085", source), "VALUE_CHANGED"),
        Arguments.of("household H", household("20", ".085", source),
            household("20", ".09", source), "VALUE_CHANGED"),
        Arguments.of("same price with a new source revision", normal("20", source),
            normal("20", SourceEvidence.ofJson(
                "{\"sourceRevision\":\"v2\",\"supplier\":\"S1\"}")), "EVIDENCE_CHANGED"),
        Arguments.of("same price with another supplier", normal("20", source),
            normal("20", SourceEvidence.ofJson(
                "{\"sourceRevision\":\"v1\",\"supplier\":\"S2\"}")), "EVIDENCE_CHANGED"));
  }

  @Test
  void equalDecimalsDoNotConflictBecauseTheirScalesDiffer() {
    var request = request("A");
    var source = SourceEvidence.ofJson("{\"sourceRevision\":\"v1\"}");

    var result = MonthlyAdoptionPlanConsistency.verify(
        single(request, null, household("20.0", ".0850", source)),
        single(request, 17L, household("20.00", ".08500", source)));

    assertThat(result.outcome()).isEqualTo(Outcome.CONSISTENT);
    assertThat(result.conflicts()).isEmpty();
  }

  @Test
  void equivalentNestedEvidenceHasOneCanonicalRepresentation() {
    var first = SourceEvidence.ofJson(
        "{\"supplier\":\"S1\",\"factors\":{\"Cu\":90.0,\"revision\":1},\"weights\":[1.0,2]}");
    var second = SourceEvidence.ofJson(
        "{\"weights\":[1,2.00],\"factors\":{\"revision\":1.0,\"Cu\":90},\"supplier\":\"S1\"}");

    assertThat(first).isEqualTo(second);
    assertThat(first.json()).isEqualTo(
        "{\"factors\":{\"Cu\":90,\"revision\":1},\"supplier\":\"S1\",\"weights\":[1,2]}");
    var request = request("A");
    assertThat(MonthlyAdoptionPlanConsistency.verify(
        single(request, null, normal("20", first)),
        single(request, 17L, normal("20", second))).outcome())
        .isEqualTo(Outcome.CONSISTENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "{}", "null", "[]", "{broken",
      "{\"source\":\"first\",\"source\":\"second\"}"})
  void missingOrAmbiguousEvidenceCannotRepresentAnAdoptableFact(String json) {
    assertThatThrownBy(() -> SourceEvidence.ofJson(json))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedDependencyEvidenceInvalidatesItsDerivedParentEvenAtTheSamePrice() {
    var a = request("A");
    var b = request("B");
    var parent = PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(b));
    var before = plan(Map.of(a, selection(null, parent), b, selection(null, normal("10", source("v1")))), a);
    var after = plan(Map.of(a, selection(17L, parent), b, selection(18L, normal("10", source("v2")))), a);

    var result = result(before, after);

    assertThat(result.outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
    assertThat(result.conflicts().get(b)).contains(Reason.EVIDENCE_CHANGED);
    assertThat(result.conflicts().get(a)).contains(Reason.DEPENDENCY_CHANGED);
  }

  @Test
  void changedDependencySetRequiresReplanningEvenIfTheParentAmountIsUnchanged() {
    var a = request("A");
    var b = request("B");
    var c = request("C");
    var before = plan(Map.of(a, selection(null, PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(b))),
        b, selection(null, normal("10", source("v1")))), a);
    var after = plan(Map.of(a, selection(17L, PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(c))),
        b, selection(18L, normal("10", source("v1"))), c, selection(19L, normal("10", source("v1")))), a);

    var result = result(before, after);

    assertThat(result.outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
    assertThat(result.conflicts().get(a)).contains(Reason.DEPENDENCY_SET_CHANGED);
  }

  @Test
  void changedDerivedParentAmountIsReportedAsADerivationConflict() {
    var a = request("A");
    var b = request("B");
    var before = plan(Map.of(a, selection(null, PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(b))),
        b, selection(null, normal("10", source("v1")))), a);
    var after = plan(Map.of(a, selection(17L, PriceFact.normal(new BigDecimal("21"), source("v1"), Set.of(b))),
        b, selection(18L, normal("10", source("v1")))), a);

    assertThat(result(before, after).conflicts().get(a)).contains(Reason.DERIVED_PARENT_CHANGED);
  }

  @Test
  void candidateMustNotAdoptAnUnreachablePrice() {
    var a = request("A");
    var orphan = request("UNUSED");
    var candidate = plan(Map.of(a, selection(null, normal("20", source("v1"))),
        orphan, selection(null, normal("10", source("v1")))), a);

    var result = result(candidate, candidate);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().get(orphan)).contains(Reason.ORPHAN_DEPENDENCY);
  }

  @Test
  void candidateMustContainEveryRequiredDependency() {
    var a = request("A");
    var b = request("B");
    var candidate = plan(Map.of(a, selection(null,
        PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(b)))), a);

    var result = result(candidate, candidate);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().get(a)).contains(Reason.MISSING_DEPENDENCY);
  }

  @Test
  void candidateDependencyCycleCannotBeAdopted() {
    var a = request("A");
    var b = request("B");
    var candidate = plan(Map.of(
        a, selection(null, PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(b))),
        b, selection(null, PriceFact.normal(new BigDecimal("10"), source("v1"), Set.of(a)))), a);

    var result = result(candidate, candidate);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().values()).anySatisfy(reasons -> assertThat(reasons).contains(Reason.CYCLE));
  }

  @Test
  void householdCandidateCannotKeepAnEffectiveUseUnderItsPath() {
    var a = request("A");
    var b = request("B");
    var candidate = new MonthlyAdoptionPlan(Map.of(a, selection(null, household("20", ".085", source("v1"))),
        b, selection(null, normal("10", source("v1")))),
        List.of(new Use("root/A/", a, BigDecimal.ONE),
            new Use("root/A/B/", b, BigDecimal.ONE)), Set.of("root/A/B/"));

    var result = result(candidate, candidate);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().get(b)).contains(Reason.SUPPRESSED_USE);
  }

  @Test
  void independentUseOfAnIncludedMaterialRemainsAdoptable() {
    var a = request("A");
    var b = request("B");
    var uses = List.of(new Use("root/A/", a, BigDecimal.ONE),
        new Use("root/AB/", b, new BigDecimal("3")));
    var candidate = new MonthlyAdoptionPlan(Map.of(a, selection(null, household("20", ".085", source("v1"))),
        b, selection(null, normal("10", source("v1")))), uses, Set.of("root/A/B/"));
    var saved = new MonthlyAdoptionPlan(Map.of(a, selection(17L, household("20", ".085", source("v1"))),
        b, selection(18L, normal("10", source("v1")))), uses, Set.of("root/A/B/"));

    assertThat(result(candidate, saved).outcome()).isEqualTo(Outcome.CONSISTENT);
  }

  @Test
  void householdWinnerIsAReplanBeforeTheOldChildUseIsJudgedInvalid() {
    var a = request("A");
    var b = request("B");
    var uses = List.of(new Use("root/A", a, BigDecimal.ONE), new Use("root/A/B", b, BigDecimal.ONE));
    var candidate = new MonthlyAdoptionPlan(Map.of(a, selection(null, normal("20", source("v1"))),
        b, selection(null, normal("10", source("v1")))), uses, Set.of());
    var winner = new MonthlyAdoptionPlan(Map.of(a, selection(17L, household("20", ".085", source("v1"))),
        b, selection(18L, normal("10", source("v1")))), uses, Set.of());

    assertThat(result(candidate, winner).outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void finalFactsRequireRealSavedIdentities(long id) {
    var a = request("A");
    var fact = normal("20", source("v1"));

    var result = result(single(a, null, fact), single(a, id, fact));

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().get(a)).contains(Reason.PERSISTENCE_INCOMPLETE);
  }

  @Test
  void finalFactsCannotLeaveCandidateIdsUnresolved() {
    var a = request("A");
    var candidate = single(a, null, normal("20", source("v1")));

    assertThat(result(candidate, candidate).conflicts().get(a)).contains(Reason.PERSISTENCE_INCOMPLETE);
  }

  @Test
  void missingFinalFactIsAnExplicitPersistenceFailure() {
    var a = request("A");
    var candidate = single(a, null, normal("20", source("v1")));
    var incomplete = new MonthlyAdoptionPlan(Map.of(), candidate.uses(), Set.of());

    var result = result(candidate, incomplete);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().get(a)).contains(Reason.PERSISTENCE_INCOMPLETE);
  }

  @Test
  void aChangedUseQuantityCannotReuseTheOriginalPlan() {
    var a = request("A");
    var candidate = single(a, null, normal("20", source("v1")));
    var resolved = new MonthlyAdoptionPlan(Map.of(a, selection(17L, normal("20", source("v1")))),
        List.of(new Use("root/A", a, new BigDecimal("2"))), Set.of());

    assertThat(result(candidate, resolved).conflicts().get(a)).contains(Reason.USES_CHANGED);
  }

  @Test
  void sharedScrapUsesTheSameSavedFactAcrossOrganizations() {
    var a210 = request("A");
    var a220 = new MonthlyPriceRequest(a210.family(), "220", a210.conditions(), new BigDecimal("3"));
    var shared = new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any());
    var scrap = normal("20", SourceEvidence.ofJson(
        "{\"sourceType\":\"SCRAP\",\"financeOrg\":\"210\",\"sourceRevision\":\"v1\"}"));
    var uses = List.of(new Use("root/A210", a210, BigDecimal.ONE),
        new Use("root/A220", a220, new BigDecimal("3")));
    var candidate = new MonthlyAdoptionPlan(Map.of(a210, new Selection(null, shared, scrap),
        a220, new Selection(null, shared, scrap)), uses, Set.of());
    var saved = new MonthlyAdoptionPlan(Map.of(a210, new Selection(17L, shared, scrap),
        a220, new Selection(17L, shared, scrap)), uses, Set.of());

    assertThat(result(candidate, saved).outcome()).isEqualTo(Outcome.CONSISTENT);
  }

  @Test
  void dedicatedWinnerNarrowingProposedSharedScrapCoverageRequiresReplanning() {
    var a220 = new MonthlyPriceRequest(request("A").family(), "220", NonCuConditions.empty(), BigDecimal.ONE);
    var scrap = normal("20", SourceEvidence.ofJson("{\"sourceType\":\"SCRAP\",\"financeOrg\":\"210\"}"));
    var candidate = new MonthlyAdoptionPlan(Map.of(a220, new Selection(null,
        new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()), scrap)),
        List.of(new Use("root/A", a220, BigDecimal.ONE)), Set.of());
    var saved = new MonthlyAdoptionPlan(Map.of(a220, new Selection(17L,
        new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any()), scrap)),
        candidate.uses(), Set.of());

    assertThat(result(candidate, saved).conflicts().get(a220)).contains(Reason.COVERAGE_CHANGED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.000000000001", "0.1234567890123", "1000000000000"})
  void householdPriceMustFitTheNonnegativeTwentyFourByTwelveStorageDomain(String price) {
    assertThatThrownBy(() -> household(price, ".085", source("v1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"99999999999999999999.999999999999999999",
      "0.000000000000000001", "12.123456789012345678000"})
  void ordinaryPricePreservesTheActualThirtyEightByEighteenSourceDomain(String price) {
    var fact = new AtomicReference<PriceFact>();

    assertThatCode(() -> fact.set(normal(price, source("v1")))).doesNotThrowAnyException();

    assertThat(fact.get().price()).isEqualByComparingTo(new BigDecimal(price));
    var request = request("A");
    assertThat(result(single(request, null, fact.get()), single(request, 17L, fact.get())).outcome())
        .isEqualTo(Outcome.CONSISTENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.000000000000000001", "0.1234567890123456789", "100000000000000000000"})
  void ordinaryPriceRejectsActualThirtyEightByEighteenOverflowWithoutRounding(String price) {
    assertThatThrownBy(() -> normal(price, source("v1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.000000000001", "0.1234567890123", "1000000"})
  void householdRateMustFitTheNonnegativeEighteenByTwelveDomain(String rate) {
    assertThatThrownBy(() -> household("20", rate, source("v1"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourcePrecisionIsPreservedAndOnlyInsignificantTrailingZerosAreNormalized() {
    var price = normal("999999999999.123456789012000", source("v1"));
    var household = household("0.000000000001", "999999.1234567890120", source("v1"));

    assertThat(price.price()).isEqualByComparingTo("999999999999.123456789012");
    assertThat(household.price()).isEqualByComparingTo("0.000000000001");
    assertThat(household.householdRate()).isEqualByComparingTo("999999.123456789012");
    assertThat(normal("0", source("v1")).price()).isZero();
  }

  @Test
  void sourceEvidenceCanReadItsOwnCanonicalNumberAtTheTransportBoundary() {
    var original = SourceEvidence.ofJson("{\"factor\":1e4095}");
    var restored = new AtomicReference<SourceEvidence>();

    assertThatCode(() -> restored.set(SourceEvidence.ofJson(original.json())))
        .doesNotThrowAnyException();

    assertThat(restored.get()).isEqualTo(original);
    assertThat(restored.get().sha256()).isEqualTo(original.sha256());
  }

  @Test
  void aHouseholdFactRequiresItsRateAndCannotDeclareChildPriceDependencies() {
    assertThatThrownBy(() -> PriceFact.household(BigDecimal.ONE, null, source("v1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PriceFact(PricingType.HOUSEHOLD_INQUIRY,
        BigDecimal.ONE, new BigDecimal(".085"), source("v1"), Set.of(request("B"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PriceFact(PricingType.NORMAL,
        BigDecimal.ONE, new BigDecimal(".085"), source("v1"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allApplicabilityFragmentsAreComparedEvenWhenTheCurrentMatchIsUnchanged() {
    var a = request("A");
    var first = intervalCoverage("0", "10");
    var fact = normal("20", source("v1"));
    var candidate = plan(Map.of(a, new Selection(null,
        new MonthlyPriceApplicability(List.of(first, intervalCoverage("20", "30"))), fact)), a);
    var saved = plan(Map.of(a, new Selection(17L,
        new MonthlyPriceApplicability(List.of(first, intervalCoverage("20", "40"))), fact)), a);

    var result = result(candidate, saved);

    assertThat(result.outcome()).isEqualTo(Outcome.REPLAN_REQUIRED);
    assertThat(result.conflicts().get(a)).contains(Reason.COVERAGE_CHANGED);
  }

  @Test
  void oneSavedIdCannotBelongToTwoMaterialFamilies() {
    var a = request("A");
    var b = request("B");
    var fact = normal("20", source("v1"));
    var candidate = paired(a, selection(null, fact), b, selection(null, fact));
    var saved = paired(a, selection(17L, fact), b, selection(17L, fact));

    assertPersistenceFailure(result(candidate, saved));
  }

  @ParameterizedTest(name = "saved row conflict: {0}")
  @MethodSource("differentSavedRowFacts")
  void oneSavedIdCannotContainConflictingEconomicFacts(String name, PriceFact changed) {
    var first = request("A");
    var second = withQuantity(first, "3");
    var initial = normal("20", source("v1"));
    var candidate = paired(first, selection(null, initial), second, selection(null, initial));
    var saved = paired(first, selection(17L, initial), second, selection(17L, changed));
    if (!changed.dependencies().isEmpty()) {
      var rows = new java.util.HashMap<>(saved.selections());
      rows.put(request("B"), selection(18L, normal("10", source("v1"))));
      saved = new MonthlyAdoptionPlan(rows, saved.uses(), Set.of());
    }

    assertPersistenceFailure(result(candidate, saved));
  }

  static Stream<Arguments> differentSavedRowFacts() {
    return Stream.of(
        Arguments.of("price", normal("21", source("v1"))),
        Arguments.of("evidence", normal("20", source("v2"))),
        Arguments.of("dependencies", PriceFact.normal(new BigDecimal("20"), source("v1"), Set.of(request("B")))));
  }

  @Test
  void anExistingMonthlyIdentityCannotBeReplacedByAnEquivalentNewRow() {
    var a = request("A");
    var fact = normal("20", source("v1"));

    assertPersistenceFailure(result(single(a, 17L, fact), single(a, 18L, fact)));
  }

  @Test
  void compatibleSharedRequestsCannotResolveToTwoOverlappingSavedRows() {
    var first = request("A");
    var second = withQuantity(first, "3");
    var fact = normal("20", source("v1"));

    assertPersistenceFailure(result(
        paired(first, selection(null, fact), second, selection(null, fact)),
        paired(first, selection(17L, fact), second, selection(18L, fact))));
  }

  @Test
  void dependencyRequestAliasesAreComparedThroughTheirSavedChildIdentities() {
    var a1 = request("A");
    var a2 = withQuantity(a1, "3");
    var b1 = request("B");
    var b2 = withQuantity(b1, "3");
    var a1Fact = PriceFact.normal(new BigDecimal("20"), source("parent"), Set.of(b1));
    var a2Fact = PriceFact.normal(new BigDecimal("20"), source("parent"), Set.of(b2));
    var child = normal("10", source("child"));
    var uses = List.of(new Use("root/one", a1, BigDecimal.ONE),
        new Use("root/two", a2, new BigDecimal("3")));
    var candidate = new MonthlyAdoptionPlan(Map.of(
        a1, selection(null, a1Fact), a2, selection(null, a2Fact),
        b1, selection(null, child), b2, selection(null, child)), uses, Set.of());
    var saved = new MonthlyAdoptionPlan(Map.of(
        a1, selection(17L, a1Fact), a2, selection(17L, a2Fact),
        b1, selection(18L, child), b2, selection(18L, child)), uses, Set.of());

    assertThat(result(candidate, saved).outcome()).isEqualTo(Outcome.CONSISTENT);
  }

  @Test
  void incompatibleCandidatesCannotOccupyOverlappingConditionsInOneFamily() {
    var first = request("A");
    var second = withQuantity(first, "3");
    var candidate = paired(first, selection(null, normal("20", source("v1"))),
        second, selection(null, normal("21", source("v2"))));
    var saved = paired(first, selection(17L, normal("20", source("v1"))),
        second, selection(18L, normal("21", source("v2"))));

    var result = result(candidate, saved);

    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().values())
        .anySatisfy(reasons -> assertThat(reasons).contains(Reason.INVALID_INPUT));
  }

  @Test
  void resolvedDuplicateUsesCannotDisappearDuringMapComparison() {
    var a = request("A");
    var fact = normal("20", source("v1"));
    var candidate = single(a, null, fact);
    var saved = new MonthlyAdoptionPlan(Map.of(a, selection(17L, fact)),
        List.of(candidate.uses().get(0), candidate.uses().get(0)), Set.of());

    assertThat(result(candidate, saved).outcome()).isEqualTo(Outcome.INVALID_PLAN);
  }

  @Test
  void anEmptyMaterialPlanHasAnEmptyConsistentResult() {
    var empty = new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of());

    assertThat(result(empty, empty).outcome()).isEqualTo(Outcome.CONSISTENT);
    assertThat(result(empty, empty).conflicts()).isEmpty();
  }

  @Test
  void anEffectiveUseMustHavePositiveConsumptionQuantity() {
    assertThatThrownBy(() -> new Use("root/A", request("A"), BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultReasonsAreDefensivelyCopied() {
    var a = request("A");
    var reasons = new HashSet<>(Set.of(Reason.TYPE_CHANGED));
    var result = new MonthlyAdoptionPlanConsistency.Result(Outcome.REPLAN_REQUIRED, Map.of(a, reasons));
    reasons.clear();

    assertThat(result.conflicts().get(a)).containsExactly(Reason.TYPE_CHANGED);
    assertThatThrownBy(() -> result.conflicts().get(a).clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void evidenceArrayOrderRemainsMeaningful() {
    var a = request("A");
    var ordered = SourceEvidence.ofJson("{\"selectedSuppliers\":[\"S1\",\"S2\"]}");
    var reversed = SourceEvidence.ofJson("{\"selectedSuppliers\":[\"S2\",\"S1\"]}");

    assertThat(result(single(a, null, normal("20", ordered)),
        single(a, 17L, normal("20", reversed))).conflicts().get(a))
        .contains(Reason.EVIDENCE_CHANGED);
  }

  @Test
  void canonicalEvidenceHasAnIndependentlyKnownSha256() {
    var evidence = SourceEvidence.ofJson(
        "{\"supplier\":\"S1\",\"factors\":{\"Cu\":90.0,\"revision\":1},\"weights\":[1.0,2]}");
    var hash = new AtomicReference<String>();

    assertThatCode(() -> hash.set(evidence.sha256())).doesNotThrowAnyException();
    assertThat(hash.get()).isEqualTo("b06276720915b9a79062b3e9c6282f7d97219bc872e575989cb7d4faa7b2e64d");
  }

  @ParameterizedTest(name = "reject evidence limit: {0}")
  @MethodSource("excessiveEvidence")
  void sourceEvidenceRejectsExcessiveResourcesWithoutTruncating(String name, String json) {
    assertThatThrownBy(() -> SourceEvidence.ofJson(json)).isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> excessiveEvidence() {
    return Stream.of(
        Arguments.of("ASCII bytes", "{\"source\":\"" + "x".repeat(1024 * 1024) + "\"}"),
        Arguments.of("UTF8 bytes", "{\"source\":\"" + "中".repeat(350000) + "\"}"),
        Arguments.of("65 object containers", nestedEvidence(65)),
        Arguments.of("4097 digit positive number", "{\"n\":1e4096}"),
        Arguments.of("4097 character fractional number", "{\"n\":1e-4095}"),
        Arguments.of("large exponent", "{\"n\":1e10000}"),
        Arguments.of("aggregate canonical expansion", "{\"n\":[" + "1e4095,".repeat(256) + "1e4095]}"),
        Arguments.of("trailing second JSON", "{\"n\":1} {\"n\":2}"));
  }

  @Test
  void sourceEvidenceAcceptsTheDocumentedResourceBoundariesExactly() {
    String prefix = "{\"source\":\"";
    String suffix = "\"}";
    String exactBytes = prefix + "x".repeat(1024 * 1024 - prefix.length() - suffix.length()) + suffix;

    assertThat(SourceEvidence.ofJson(exactBytes).json().getBytes(StandardCharsets.UTF_8))
        .hasSize(1024 * 1024);
    assertThat(SourceEvidence.ofJson(nestedEvidence(64)).json()).isEqualTo(nestedEvidence(64));
    assertThat(SourceEvidence.ofJson("{\"n\":1e4095}").json())
        .isEqualTo("{\"n\":1" + "0".repeat(4095) + "}");
    assertThat(SourceEvidence.ofJson("{\"n\":1e-4094}").json())
        .isEqualTo("{\"n\":0." + "0".repeat(4093) + "1}");
  }

  @ParameterizedTest(name = "canonicalize long numeric token: {0}")
  @MethodSource("longEquivalentNumberTokens")
  void sourceEvidenceLimitsCanonicalNumbersRatherThanTheirRedundantTail(
      String name, String token, String expected) {
    var evidence = SourceEvidence.ofJson("{\"n\":" + token + "}");

    assertThat(evidence.json()).isEqualTo("{\"n\":" + expected + "}");
    assertThat(SourceEvidence.ofJson(evidence.json())).isEqualTo(evidence);
    assertThat(evidence.sha256())
        .isEqualTo(SourceEvidence.ofJson("{\"n\":" + expected + "}").sha256());
  }

  static Stream<Arguments> longEquivalentNumberTokens() {
    return Stream.of(
        Arguments.of("positive integer", "1." + "0".repeat(5000), "1"),
        Arguments.of("negative integer", "-1." + "0".repeat(5000), "-1"),
        Arguments.of("fraction", "1.23" + "0".repeat(5000), "1.23"),
        Arguments.of("zero", "0." + "0".repeat(5000), "0"));
  }

  private static String nestedEvidence(int containers) {
    return "{\"x\":".repeat(containers) + "1" + "}".repeat(containers);
  }

  private static MonthlyPriceCoverage intervalCoverage(String lower, String upper) {
    return new MonthlyPriceCoverage(Set.of("210"),
        new QuantityInterval(new BigDecimal(lower), true, new BigDecimal(upper), false), ConditionDomain.any());
  }

  private static MonthlyPriceRequest withQuantity(MonthlyPriceRequest request, String quantity) {
    return new MonthlyPriceRequest(request.family(), request.priceOrgCode(), request.conditions(), new BigDecimal(quantity));
  }

  private static MonthlyAdoptionPlan paired(MonthlyPriceRequest first, Selection firstSelection,
      MonthlyPriceRequest second, Selection secondSelection) {
    return new MonthlyAdoptionPlan(Map.of(first, firstSelection, second, secondSelection),
        List.of(new Use("root/one", first, BigDecimal.ONE), new Use("root/two", second, new BigDecimal("3"))), Set.of());
  }

  private static void assertPersistenceFailure(MonthlyAdoptionPlanConsistency.Result result) {
    assertThat(result.outcome()).isEqualTo(Outcome.INVALID_PLAN);
    assertThat(result.conflicts().values())
        .anySatisfy(reasons -> assertThat(reasons).contains(Reason.PERSISTENCE_INCOMPLETE));
  }

  private static MonthlyAdoptionPlanConsistency.Result result(
      MonthlyAdoptionPlan planned, MonthlyAdoptionPlan resolved) {
    var result = new AtomicReference<MonthlyAdoptionPlanConsistency.Result>();
    assertThatCode(() -> result.set(MonthlyAdoptionPlanConsistency.verify(planned, resolved)))
        .doesNotThrowAnyException();
    return result.get();
  }

  private static SourceEvidence source(String revision) {
    return SourceEvidence.ofJson("{\"sourceRevision\":\"" + revision + "\"}");
  }

  private static Selection selection(Long id, PriceFact fact) {
    return new Selection(id,
        new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()), fact);
  }

  private static MonthlyAdoptionPlan plan(Map<MonthlyPriceRequest, Selection> selections, MonthlyPriceRequest root) {
    return new MonthlyAdoptionPlan(selections, List.of(new Use("root/A", root, BigDecimal.ONE)), Set.of());
  }

  private static PriceFact normal(String price, SourceEvidence source) {
    return PriceFact.normal(new BigDecimal(price), source, Set.of());
  }

  private static PriceFact household(String price, String rate, SourceEvidence source) {
    return PriceFact.household(new BigDecimal(price), new BigDecimal(rate), source);
  }

  private static MonthlyPriceRequest request(String material) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(
        YearMonth.of(2026, 10), "COMMERCIAL", material, null, "EA", "CNY",
        TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }

  private static MonthlyAdoptionPlan single(
      MonthlyPriceRequest request, Long savedId, PriceFact fact) {
    return new MonthlyAdoptionPlan(Map.of(request, new Selection(savedId,
        new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()), fact)),
        List.of(new Use("root/A", request, BigDecimal.ONE)), Set.of());
  }
}
