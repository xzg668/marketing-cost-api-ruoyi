package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyCopperExposureTest {
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  final MonthlyCopperExposure exposure = new MonthlyCopperExposure(codec);

  @Test void actualCapturedFactorDependenciesRemainAffectedWhenThePricesAreEqual() {
    var a = request("A"); var b = request("B"); var c = request("C");
    var original = Map.of("calculationJson", json(Map.of("inputs", Map.of("cu", 100),
        "factorBindings", Map.of("cu", Map.of("canonicalKey", PriceFactorContext.COPPER)))));
    var range = Map.of("calculationJson", json(Map.of("factorKey", PriceFactorContext.COPPER)));
    var legacy = Map.of("calculatorVersion", "MONTHLY_LINKED_FORMULA_V1", "factorKeys", Map.of(),
        "context", Map.of("financial", Map.of("factors", Map.of(PriceFactorContext.COPPER, Map.of("price", 100)))));
    assertThat(exposure.read(Map.of(a, selection(1, "LINKED", original, Set.of()),
        b, selection(2, "RANGE_FACTOR", range, Set.of()),
        c, selection(3, "LINKED_LEGACY", Map.of("calculation", legacy), Set.of()))))
        .containsExactlyInAnyOrderEntriesOf(Map.of(1L, true, 2L, true, 3L, true));
  }

  @Test void unusedPublicationCopperAndOtherMetalsDoNotMarkAConstantPrice() {
    var calculation = Map.of("inputs", Map.of("zn", 10), "factorBindings", Map.of(
        "cu", Map.of("canonicalKey", PriceFactorContext.COPPER), "zn", Map.of("canonicalKey", "AVG|ZN")));
    var original = selection(1, "LINKED", Map.of("calculationJson", json(calculation)), Set.of());
    var legacy = selection(2, "LINKED_LEGACY", Map.of("calculation", Map.of(
        "calculatorVersion", "MONTHLY_LINKED_FORMULA_V1", "factorKeys", Map.of(),
        "context", Map.of("financial", Map.of("factors", Map.of())))), Set.of());
    assertThat(exposure.read(Map.of(request("A"), original, request("B"), legacy)))
        .containsExactlyInAnyOrderEntriesOf(Map.of(1L, false, 2L, false));
  }

  @Test void scrapDependenciesFollowOnlyTheSelectedRuleAndFixedAndHouseholdStayUnaffected() {
    var scrapRequest = request("SCRAP"); var formulaRequest = request("FORMULA");
    var candidates = List.of(Map.of("original", Map.of("id", 1), "input", Map.of("rule", Map.of("factorBindings", Map.of()))),
        Map.of("original", Map.of("id", 2), "input", Map.of("rule", Map.of("factorBindings", Map.of("cu", PriceFactorContext.COPPER)))));
    var scrap = selection(1, "SCRAP_PRICE", Map.of("selected", Map.of("id", 2), "candidates", candidates), Set.of());
    var formula = selection(2, "LINKED_LEGACY", Map.of("calculation", Map.of(
        "calculatorVersion", "MONTHLY_LINKED_FORMULA_V1")), Set.of(scrapRequest));
    assertThat(exposure.read(Map.of(scrapRequest, scrap, formulaRequest, formula)))
        .containsExactlyInAnyOrderEntriesOf(Map.of(1L, true, 2L, true));
    assertThat(exposure.read(Map.of(scrapRequest, selection(3, "SCRAP_PRICE",
        Map.of("selected", Map.of("id", 1), "candidates", candidates), Set.of())))).containsEntry(3L, false);
    for (String type : List.of("HOUSEHOLD_INQUIRY", "PURCHASE_FIXED", "TECH_FIXED", "RANGE_QTY", "SETTLE_FIXED"))
      assertThat(exposure.read(Map.of(request("A"), selection(4, type, Map.of(), Set.of())))).containsEntry(4L, false);
    assertThatThrownBy(() -> exposure.read(Map.of(formulaRequest, formula))).hasMessageContaining("dependency");
  }

  @Test void settlementNeedsNonzeroCopperWeightAndLegacyRangeNeedsTheCanonicalCopperKey() {
    assertThat(exposure.read(Map.of(request("A"), selection(1, "SETTLE_LINKED", Map.of("rule", Map.of("copperWeightG", 0)), Set.of()),
        request("B"), selection(2, "SETTLE_LINKED", Map.of("rule", Map.of("copperWeightG", 20)), Set.of()),
        request("C"), selection(3, "RANGE_LEGACY_FACTOR", Map.of("factorBinding", Map.of("canonicalKey", PriceFactorContext.COPPER)), Set.of()))))
        .containsExactlyInAnyOrderEntriesOf(Map.of(1L, false, 2L, true, 3L, true));
  }

  MonthlyAdoptionPlan.Selection selection(long id, String kind, Object source, Set<MonthlyPriceRequest> dependencies) {
    return new MonthlyAdoptionPlan.Selection(id, new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()),
        MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN, MonthlyAdoptionPlan.SourceEvidence.ofJson(json(
            Map.of("snapshotSchema", 1, "sourceKind", kind, "source", source))), dependencies));
  }
  static MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", code,
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }
  String json(Object value) { return codec.unwrap(codec.encode(value)); }
}
