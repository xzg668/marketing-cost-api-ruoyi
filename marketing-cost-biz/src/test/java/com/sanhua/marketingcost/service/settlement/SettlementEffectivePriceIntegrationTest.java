package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 发布入口、MySQL 事务与真实读取端口的月度契约回归。 */
@Tag("integration")
class SettlementEffectivePriceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired SettlementPriceImportService imports;
  @Autowired FinanceFactorPublicationService finance;
  @Autowired SettlementPriceReader reader;
  @Autowired SettlementPriceRepository rules;
  @Autowired SettlementEffectivePriceRepository saved;
  @Autowired SettlementFinanceBaseReader bases;
  @Autowired SettlementPriceQuery query;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  private byte[] original;
  private static final Path ARCHIVE;
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-monthly-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }
  private SettlementScope scope(String month) { return new SettlementScope("COMMERCIAL", month); }
  @BeforeEach void clean() throws Exception {
    jdbc.execute("DROP TRIGGER IF EXISTS reject_settlement_result");
    jdbc.update("DELETE FROM lp_settlement_effective_price");
    jdbc.update("DELETE FROM lp_settlement_price");
    SettlementFinanceTestSupport.clear(jdbc);
    try (var stream = getClass().getResourceAsStream("/settlement/final-20261021.xls")) { original = stream.readAllBytes(); }
  }
  @AfterEach void removeFailureProbe() { jdbc.execute("DROP TRIGGER IF EXISTS reject_settlement_result"); }

  @Test void eitherPublicationOrderSavesAllFortySixPricesWithExplicitPendingInputs() {
    var first = original("2026-10");
    assertThat(first.effectivePrices().generated()).isEqualTo(35);
    assertThat(first.effectivePrices().pending()).isEqualTo(11);
    assertThat(price("2026-10").failureCode()).isEqualTo("SETTLEMENT_BASE_MISSING");
    publish("2026-10", "90");
    assertThat(month("2026-10")).hasSize(46);
    assertThat(price("2026-10").unitPrice()).isEqualByComparingTo("4.7146");
    publish("2026-11", "95");
    var later = original("2026-11");
    assertThat(later.effectivePrices().generated()).isEqualTo(46);
    assertThat(later.effectivePrices().pending()).isZero();
    assertThat(month("2026-11")).hasSize(92);
  }

  @Test void novemberBaseCarriesOctoberOriginalAndNewNovemberOriginalSupersedesItWithoutRewritingOctober() throws Exception {
    original("2026-10"); publish("2026-10", "90");
    var october = month("2026-10");
    var frozen = price("2026-10");
    publish("2026-11", "95");
    assertThat(month("2026-10")).isEqualTo(october);
    assertThat(month("2026-11")).hasSize(46);
    var november = snapshot("2026-11");
    assertThat(november.path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
    assertThat(november.path("base").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(november.path("effectivePrice").path("priceMonth").asText()).isEqualTo("2026-11");
    var firstNovember = month("2026-11");
    original("2026-11");
    publish("2026-11", "100");
    assertThat(snapshot("2026-11").path("rule").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(snapshot("2026-11").path("effectivePrice").path("revisionNo").asInt()).isEqualTo(3);
    assertThat(month("2026-11")).containsAll(firstNovember).hasSize(103);
    assertThat(month("2026-10")).isEqualTo(october);
    assertThat(price("2026-10")).isEqualTo(frozen);
    publish("2026-12", "105");
    assertThat(snapshot("2026-12").path("rule").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(price("2026-10")).isEqualTo(frozen);
    var history = query.history(scope("2026-11"), price("2026-11").resultRefId());
    assertThat(history).hasSize(4).noneMatch(row -> row.priceMonth().equals("2026-12"));
    assertThat(history.getFirst().revisionNo()).isEqualTo(3);
  }

  @Test void originalOnlyMonthUsesApplicablePriorFinanceAndNoFuturePublicationLeaksBack() throws Exception {
    publish("2026-10", "90"); original("2026-10");
    var october = month("2026-10");
    original("2026-11");
    var november = month("2026-11");
    assertThat(november).hasSize(46);
    assertThat(snapshot("2026-11").path("base").path("priceMonth").asText()).isEqualTo("2026-10");
    assertThat(snapshot("2026-11").path("effectivePrice").path("priceMonth").asText()).isEqualTo("2026-11");
    publish("2026-12", "120"); original("2026-12");
    assertThat(month("2026-10")).isEqualTo(october);
    assertThat(month("2026-11")).isEqualTo(november);
    assertThat(reader.resolve(request("2026-09", "210")).unitPrice()).isNull();
  }

  @Test void repeatingSourceAndBasePublicationsReusesIdenticalResultsAndFixedPricesIgnoreCopperChanges() {
    publish("2026-10", "90"); original("2026-10");
    var before = month("2026-10");
    assertThat(original("2026-10").effectivePrices().generated()).isZero();
    assertThat(publish("2026-10", "90").unchanged()).isTrue();
    assertThat(month("2026-10")).isEqualTo(before);
    publish("2026-10", "95");
    assertThat(month("2026-10")).hasSize(57).containsAll(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price WHERE price_type='SETTLE_FIXED'", Integer.class)).isEqualTo(35);
  }

  @Test void missingSavedResultBlocksReadWithoutComputingOrWritingAndBothOrganizationsShareResult() throws Exception {
    publish("2026-10", "90"); original("2026-10");
    var calculator = spy(new SettlementPriceCalculator());
    var readOnly = new SettlementPriceReaderImpl(rules, bases, calculator, saved, new SettlementPriceEvidence(json));
    var commercial = readOnly.resolve(request("2026-10", "210"));
    var board = readOnly.resolve(request("2026-10", "220"));
    assertThat(commercial.unitPrice()).isEqualByComparingTo(board.unitPrice());
    assertThat(json.readTree(commercial.evidence().inputSnapshotJson()).path("effectivePrice"))
        .isEqualTo(json.readTree(board.evidence().inputSnapshotJson()).path("effectivePrice"));
    jdbc.update("DELETE FROM lp_settlement_effective_price WHERE material_code='203240646'");
    var before = month("2026-10");
    assertThat(readOnly.resolve(request("2026-10", "210")).failureCode()).isEqualTo("SETTLEMENT_EFFECTIVE_PRICE_MISSING");
    assertThat(month("2026-10")).isEqualTo(before);
    verify(calculator, never()).calculate(any(), any());
  }

  @Test void failingResultInsertRollsBackOriginalAndFinancePublicationTogether() {
    publish("2026-10", "90"); original("2026-10");
    var results = month("2026-10");
    var originals = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    var batches = jdbc.queryForList("SELECT * FROM lp_factor_upload_batch ORDER BY id");
    jdbc.execute("CREATE TRIGGER reject_settlement_result BEFORE INSERT ON lp_settlement_effective_price FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='monthly result rollback probe'");
    assertThatThrownBy(() -> original("2026-11")).hasStackTraceContaining("monthly result rollback probe");
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(originals);
    assertThatThrownBy(() -> publish("2026-11", "95")).hasStackTraceContaining("monthly result rollback probe");
    assertThat(jdbc.queryForList("SELECT * FROM lp_factor_upload_batch ORDER BY id")).isEqualTo(batches);
    assertThat(month("2026-10")).isEqualTo(results);
    assertThat(month("2026-11")).isEmpty();
  }

  @Test void concurrentOriginalAndBasePublicationAlwaysLeaveTheLatestCombinationSaved() throws Exception {
    original("2026-10"); publish("2026-10", "90");
    var october = month("2026-10");
    var ready = new CountDownLatch(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var source = executor.submit(() -> { ready.countDown(); assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); return original("2026-11"); });
      var base = executor.submit(() -> { ready.countDown(); assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); return publish("2026-11", "95"); });
      source.get(45, TimeUnit.SECONDS); base.get(45, TimeUnit.SECONDS);
    }
    assertThat(query.list(scope("2026-11"), null, null)).hasSize(46).allMatch(row -> row.failureCode() == null);
    var captured = snapshot("2026-11");
    assertThat(captured.path("rule").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(captured.path("base").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(captured.path("base").path("copperPrice").decimalValue()).isEqualByComparingTo("95");
    assertThat(month("2026-10")).isEqualTo(october);
  }

  @Test void partialNovemberOriginalRetainsOtherMaterialsAndUnitHistoryIsIsolated() throws Exception {
    original("2026-10"); publish("2026-10", "90");
    var before = month("2026-10");
    SettlementRuleTestSupport.importWeights(imports, scope("2026-11"), "203240646", 8, 1.5);
    assertThat(query.list(scope("2026-11"), null, null)).hasSize(46);
    assertThat(snapshot("2026-11").path("rule").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(query.list(scope("2026-11"), "201600446", null).getFirst().rule().path("priceMonth").asText()).isEqualTo("2026-10");
    assertThat(month("2026-11")).hasSize(46);
    assertThat(month("2026-10")).isEqualTo(before);
    assertThatThrownBy(() -> query.history(new SettlementScope("HOUSEHOLD", "2026-11"), price("2026-11").resultRefId()))
        .hasMessageContaining("结算价格不存在");
  }

  @Test void additiveMigrationIsIdempotentAndPreservesAllStoredInputsAndResults() {
    publish("2026-10", "90"); original("2026-10");
    var before = month("2026-10");
    var originals = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    new ResourceDatabasePopulator(new ClassPathResource("db/V341__settlement_effective_price.sql")).execute(jdbc.getDataSource());
    assertThat(month("2026-10")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(originals);
    assertThatThrownBy(() -> saved.lock("COMMERCIAL")).hasMessageContaining("事务内");
  }

  private SettlementPriceImportService.ImportResult original(String month) {
    var scope = scope(month);
    return imports.confirm(original, "最终结算价.xls", scope, imports.preview(original, "最终结算价.xls", scope).fingerprint(), "monthly-test");
  }
  private FinanceFactorPublicationService.Publication publish(String month, String copper) {
    return SettlementFinanceTestSupport.publish(finance, scope(month), copper, "21.6362912421045");
  }
  private List<Map<String, Object>> month(String month) {
    return jdbc.queryForList("SELECT * FROM lp_settlement_effective_price WHERE effective_month=? ORDER BY id", month);
  }
  private SettlementPriceRequest request(String month, String org) {
    return new SettlementPriceRequest("COMMERCIAL", "203240646", month, org, SettlementPriceType.SETTLE_LINKED);
  }
  private com.sanhua.marketingcost.service.pricing.PriceResolveResult price(String month) { return reader.resolve(request(month, "210")); }
  private com.fasterxml.jackson.databind.JsonNode snapshot(String month) throws Exception { return json.readTree(price(month).evidence().inputSnapshotJson()); }
}
