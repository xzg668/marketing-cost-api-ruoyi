package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Read-only preparation uses captured financial evidence before any monthly adoption. */
@Tag("integration")
class MonthlyCandidateScenariosIntegrationTest {
  @BeforeAll
  static void start() throws Exception {
    MonthlyCostVersionIntegrationTest.database();
  }

  @AfterAll
  static void stop() {
    MonthlyCostVersionIntegrationTest.stop();
  }

  @Test
  void quotePreviewProjectsCapturedFinancialPriceWithoutAdoptingOrFollowingSourceUpdates() {
    var db = MonthlyCostVersionIntegrationTest.jdbc;
    String code = MonthlyCostVersionIntegrationTest.code();
    var context = MonthlyCostVersionIntegrationTest.context("COMMERCIAL", "2039-01", "210");
    MonthlyCostVersionIntegrationTest.publication("COMMERCIAL", "2039-01", "210");
    var rule = MonthlyCostVersionIntegrationTest.rule(code, "COMMERCIAL", "2039-01", "210", "S1", "cu*2+3", false);
    MonthlyCostVersionIntegrationTest.publish("COMMERCIAL", "210");
    var request = MonthlyCostVersionIntegrationTest.request(code, "COMMERCIAL", "2039-01", "210", NonCuConditions.empty());
    var module = MonthlyCostVersionIntegrationTest.operation;
    var selection = module.resolveCandidates(context, Set.of(request)).values().get(request).selection();
    assertThat(selection.monthlyPriceId()).isNull();
    assertThat(selection.fact().price()).isEqualByComparingTo("203");
    var scenarios = MonthlyScenarioTestSupport.create(null);
    var preview = scenarios.preview(Map.of(request, selection), new BigDecimal("80000"), Map.of(request, context.priceAsOfTime().toLocalDate()));
    assertThat(preview.get(request).financialPrice()).isEqualByComparingTo("203");
    assertThat(preview.get(request).customerPrice()).isEqualByComparingTo("163");
    db.update("UPDATE lp_price_linked_item SET formula_expr='cu*9+99' WHERE id=?", rule.getId());
    var repeated = scenarios.preview(Map.of(request, selection), new BigDecimal("80000"), Map.of(request, context.priceAsOfTime().toLocalDate()));
    assertThat(repeated).isEqualTo(preview);
    assertThat(module.lookup(Set.of(request)).missing()).containsExactly(request);
    assertThat(selection.monthlyPriceId()).isNull();
    db.update("UPDATE lp_price_linked_item SET formula_expr='cu*2+3' WHERE id=?", rule.getId());
  }

  @Test
  void oneQuotationCapturesBothScenariosAndAdoptsOnlyItsConsumedOccurrences() {
    String code = MonthlyCostVersionIntegrationTest.code();
    String unused = MonthlyCostVersionIntegrationTest.code();
    String month = "2039-02";
    var context = MonthlyCostVersionIntegrationTest.context("COMMERCIAL", month, "210");
    MonthlyCostVersionIntegrationTest.publication("COMMERCIAL", month, "210");
    MonthlyCostVersionIntegrationTest.rule(code, "COMMERCIAL", month, "210", "S1", "cu*2+3", false);
    MonthlyCostVersionIntegrationTest.rule(unused, "COMMERCIAL", month, "210", "S1", "cu+7", false);
    MonthlyCostVersionIntegrationTest.publish("COMMERCIAL", "210");
    var module = MonthlyCostVersionIntegrationTest.operation;
    var reader = new MonthlyQuotationPrices(module, MonthlyCostVersionIntegrationTest.household);
    var session = new MonthlyQuotationSession(context, new BigDecimal("80000"), reader,
        MonthlyScenarioTestSupport.create(null), MonthlyCostVersionIntegrationTest.priceStorage,
        MonthlyCostVersionIntegrationTest.codec);
    var input = new QuotationPriceSession.Input(code, "210", "件", "SPEC", TaxBasis.EXCLUDED,
        null, NonCuConditions.empty(), BigDecimal.ONE, QuotationPriceSession.Purpose.MATERIAL);
    var excluded = new QuotationPriceSession.Input(unused, "210", "件", "SPEC", TaxBasis.EXCLUDED,
        null, NonCuConditions.empty(), BigDecimal.ONE, QuotationPriceSession.Purpose.MATERIAL);
    var first = session.read(List.of(input, excluded));
    assertThat(first.problems()).isEmpty();
    assertThat(first.values().get(input).financialPrice()).isEqualByComparingTo("203");
    assertThat(first.values().get(input).customerPrice()).isEqualByComparingTo("163");
    MonthlyCostVersionIntegrationTest.jdbc.update("UPDATE lp_price_linked_item SET formula_expr='cu*9+99' WHERE material_code=?", code);
    try {
    assertThat(session.read(List.of(input)).values().get(input)).isEqualTo(first.values().get(input));
    var anotherQuantity = new QuotationPriceSession.Input(code, "210", "件", "SPEC", TaxBasis.EXCLUDED,
        null, NonCuConditions.empty(), new BigDecimal("3"), QuotationPriceSession.Purpose.MATERIAL);
    var repeated = session.read(List.of(anotherQuantity));
    assertThat(repeated.problems()).isEmpty();
    assertThat(repeated.values().get(anotherQuantity).financialPrice()).isEqualByComparingTo("203");
    assertThat(repeated.values().get(anotherQuantity).customerPrice()).isEqualByComparingTo("163");
    session.consume("root/b", anotherQuantity, new BigDecimal("3"));
    session.consume("root/a", input, new BigDecimal("2.5"));
    var plan = session.plan();
    assertThat(plan.uses()).hasSize(2);
    assertThat(plan.uses().stream().filter(use -> use.path().endsWith("/a")).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("2.5");
    assertThat(plan.selections()).containsOnlyKeys(first.values().get(input).request(), repeated.values().get(anotherQuantity).request());
    assertThat(module.lookup(Set.of(first.values().get(input).request())).missing()).hasSize(1);
    var adopted = module.adopt(context, plan).plan();
    assertThat(adopted.selections().values().iterator().next().monthlyPriceId()).isPositive();
    assertThat(adopted.selections().values().stream().map(MonthlyAdoptionPlan.Selection::monthlyPriceId).distinct()).hasSize(1);
    assertThat(module.lookup(Set.of(first.values().get(excluded).request())).missing()).hasSize(1);
    } finally {
      MonthlyCostVersionIntegrationTest.jdbc.update("UPDATE lp_price_linked_item SET formula_expr='cu*2+3' WHERE material_code=?", code);
    }
  }
}
