package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
class SettlementPriceReaderIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired SettlementPriceReader reader;
  @Autowired SettlementPriceQuery query;
  @Autowired SettlementPriceImportService imports;
  @Autowired SettlementPriceRepository prices;
  @Autowired SettlementWeightService weights;
  @Autowired SettlementBasePriceService bases;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  private static final Path ARCHIVE;
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-reader-tests-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry properties) {
    properties.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }
  private final SettlementScope october = new SettlementScope("COMMERCIAL", "2026-10");

  @BeforeEach void importedOriginal() throws Exception {
    jdbc.update("DELETE FROM lp_settlement_price");
    jdbc.update("DELETE FROM lp_settlement_base_price");
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { bytes = input.readAllBytes(); }
    var preview = imports.preview(bytes, "original.xls", october);
    imports.confirm(bytes, "original.xls", october, preview.fingerprint(), "finance");
  }

  @Test void sixOriginalGivenPricesRemainAvailableWithoutAnyBaseAndBothOrganizationsShareSourceIds() throws Exception {
    jdbc.update("DELETE FROM lp_settlement_base_price");
    String[] codes = {"203240646", "203259534", "203240247", "203240446", "201200143", "721250136"};
    String[] values = {"4.841", "4.5114", "0.0266", "0.1099", "0.0461", "0.0827"};
    for (int i = 0; i < codes.length; i++) {
      var commercial = reader.resolve(request(codes[i], "2026-11", "210", SettlementPriceType.SETTLE_LINKED));
      var board = reader.resolve(request(codes[i], "2026-11", "220", SettlementPriceType.SETTLE_LINKED));
      assertThat(commercial.unitPrice()).isEqualByComparingTo(values[i]);
      assertThat(board.unitPrice()).isEqualByComparingTo(values[i]);
      assertThat(board.resultRefId()).isEqualTo(commercial.resultRefId());
      var snapshot = json.readTree(commercial.evidence().inputSnapshotJson());
      assertThat(snapshot.path("sourceTable").asText()).isEqualTo("lp_settlement_price");
      assertThat(snapshot.path("mode").asText()).isEqualTo("LINKED_GIVEN");
      assertThat(snapshot.path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
      assertThat(snapshot.path("base").isNull()).isTrue();
      assertThat(commercial.hasBlockingFailure()).isFalse();
    }
    var fixed = reader.resolve(request("809633", "2026-11", "220", SettlementPriceType.SETTLE_FIXED));
    assertThat(fixed.unitPrice()).isEqualByComparingTo("4.1658");
    assertThat(json.readTree(fixed.evidence().inputSnapshotJson()).path("mode").asText()).isEqualTo("FIXED_GIVEN");
  }

  @Test void novemberUsesOctoberRuleWithItsOwnLatestBaseAndNeitherPriceNorBaseLeaksBackFromDecember() throws Exception {
    confirmSampleWeights();
    var request = request("203240646", "2026-11", "220", SettlementPriceType.SETTLE_LINKED);
    var carried = reader.resolve(request);
    assertThat(carried.unitPrice()).isEqualByComparingTo("4.841024707965");
    assertThat(json.readTree(carried.evidence().inputSnapshotJson()).path("base").path("priceMonth").asText()).isEqualTo("2026-10");
    var november = new SettlementScope("COMMERCIAL", "2026-11");
    bases.save(november, new SettlementBasePriceService.Change(new BigDecimal("110"), new BigDecimal("26"), "CNY/KG", 0, "十一月"), "finance");
    var current = reader.resolve(request);
    assertThat(current.unitPrice()).isEqualByComparingTo("4.888708672566");
    var proof = json.readTree(current.evidence().inputSnapshotJson());
    assertThat(proof.path("rule").path("priceMonth").asText()).isEqualTo("2026-10");
    assertThat(proof.path("rule").path("revisionNo").asInt()).isEqualTo(2);
    assertThat(proof.path("rule").path("budgetCu").decimalValue()).isEqualByComparingTo("78");
    assertThat(proof.path("base").path("priceMonth").asText()).isEqualTo("2026-11");
    assertThat(proof.path("mode").asText()).isEqualTo("LINKED_FORMULA");
    var december = new SettlementScope("COMMERCIAL", "2026-12");
    bases.save(december, new SettlementBasePriceService.Change(new BigDecimal("999"), new BigDecimal("999"), "CNY/KG", 0, "十二月"), "finance");
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { bytes = input.readAllBytes(); }
    var preview = imports.preview(bytes, "december.xls", december);
    imports.confirm(bytes, "december.xls", december, preview.fingerprint(), "finance");
    assertThat(reader.resolve(request)).isEqualTo(current);
    assertThat(reader.resolve(request("203240646", "2026-10", "210", SettlementPriceType.SETTLE_LINKED)).unitPrice())
        .isEqualByComparingTo("4.841024707965");
    assertThat(reader.resolve(request("203240646", "2026-09", "210", SettlementPriceType.SETTLE_LINKED)).unitPrice()).isNull();
  }

  @Test void absenceIsNonBlockingButMissingBaseAndBadFormulaAreExplicitErrors() {
    var absent = reader.resolve(request("ABSENT", "2026-11", "210", SettlementPriceType.SETTLE_FIXED));
    assertThat(absent.failureCode()).isEqualTo("MATERIAL_MONTH_PRICE_MISSING");
    assertThat(absent.hasBlockingFailure()).isFalse();
    assertThat(reader.resolve(new SettlementPriceRequest("HOUSEHOLD", "203240646", "2026-11", "210", SettlementPriceType.SETTLE_LINKED)).unitPrice()).isNull();
    confirmSampleWeights();
    jdbc.update("DELETE FROM lp_settlement_base_price");
    var request = request("203240646", "2026-11", "210", SettlementPriceType.SETTLE_LINKED);
    var missingBase = reader.resolve(request);
    assertThat(missingBase.failureCode()).isEqualTo("SETTLEMENT_BASE_MISSING");
    assertThat(missingBase.hasBlockingFailure()).isTrue();
    assertThat(missingBase.unitPrice()).isNull();
    jdbc.update("UPDATE lp_settlement_price SET expression='bad formula' WHERE material_code='203240646' AND revision_no=2");
    var malformed = reader.resolve(request);
    assertThat(malformed.failureCode()).isEqualTo("SETTLEMENT_FORMULA_INVALID");
    assertThat(malformed.hasBlockingFailure()).isTrue();
    assertThat(malformed.unitPrice()).isNull();
  }

  @Test void batchAndSingleAgreeWithOneRuleReadAndAtMostOneBaseReadRegardlessOfRequestCount() {
    var data = new CountingDataSource(jdbc.getDataSource());
    var countedJdbc = new JdbcTemplate(data);
    var counted = new SettlementPriceReaderImpl(new SettlementPriceRepository(countedJdbc),
        new SettlementBasePriceRepository(countedJdbc), new SettlementPriceCalculator(), json);
    List<SettlementPriceRequest> requests = new ArrayList<>();
    for (int i = 0; i < 100; i++) for (String code : List.of("203240646", "203259534", "203240247", "203240446", "201200143", "721250136"))
      requests.add(request(code, "2026-11", i % 2 == 0 ? "210" : "220", SettlementPriceType.SETTLE_LINKED));
    var given = counted.resolveBatch(requests);
    assertThat(given).hasSize(12);
    assertThat(data.selects).hasSize(1);
    assertThat(data.selects.getFirst()).contains("lp_settlement_price").doesNotContain("lp_settlement_base_price");
    confirmSampleWeights();
    data.selects.clear();
    var calculated = counted.resolveBatch(requests);
    assertThat(data.selects).hasSize(2);
    assertThat(data.selects.stream().filter(sql -> sql.contains("lp_settlement_base_price"))).hasSize(1);
    calculated.forEach((request, result) -> assertThat(reader.resolve(request)).isEqualTo(result));
    assertThat(calculated.get(request("203240646", "2026-11", "210", SettlementPriceType.SETTLE_LINKED)).unitPrice())
        .isEqualByComparingTo("4.841024707965");
  }

  @Test void maintenanceViewUsesTheSameBatchPricesAndExplainsCarryAndBlockingFailures() {
    var initial = query.list(new SettlementScope("COMMERCIAL", "2026-11"), null, null);
    assertThat(initial).hasSize(37);
    assertThat(initial).filteredOn(row -> row.mode().equals("LINKED_GIVEN")).hasSize(6);
    assertThat(initial).allMatch(SettlementPriceQuery.Entry::ruleCarriedForward);
    assertThat(initial).allMatch(row -> row.base() == null);
    assertThat(query.list(new SettlementScope("HOUSEHOLD", "2026-11"), null, null)).isEmpty();
    confirmSampleWeights();
    var calculated = query.list(new SettlementScope("COMMERCIAL", "2026-11"), "203240646", null).getFirst();
    assertThat(calculated.unitPrice()).isEqualTo("4.841024707965");
    assertThat(calculated.mode()).isEqualTo("LINKED_FORMULA");
    assertThat(calculated.base().path("priceMonth").asText()).isEqualTo("2026-10");
    assertThat(calculated.baseCarriedForward()).isTrue();
    jdbc.update("DELETE FROM lp_settlement_base_price");
    var missing = query.list(october, "203240646", null).getFirst();
    assertThat(missing.unitPrice()).isNull();
    assertThat(missing.failureCode()).isEqualTo("SETTLEMENT_BASE_MISSING");
    assertThat(missing.message()).isNotBlank();
  }

  @Test void maintenanceDecimalsRoundTripWithoutJavascriptPrecisionLoss() {
    confirmSampleWeights();
    jdbc.update("UPDATE lp_settlement_price SET copper_weight_g=123456789.123456789012 WHERE material_code='203240646' AND revision_no=2");
    var result = query.list(october, "203240646", null).getFirst();
    assertThat(result.rule().path("copperWeightG").isTextual()).isTrue();
    assertThat(result.rule().path("copperWeightG").asText()).isEqualTo("123456789.123456789012");
    assertThat(result.base().path("copperPrice").isTextual()).isTrue();
  }

  @Test void representativeLargeBomUsesTwoReadsForOneThousandDistinctMaterialsAcrossBothOrganizations() throws Exception {
    confirmSampleWeights();
    var linked = prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    var fixed = prices.effective(october, "809633", SettlementPriceType.SETTLE_FIXED).getFirst();
    List<SettlementPriceRequest> requests = new ArrayList<>();
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      var row = (i % 2 == 0 ? linked : fixed).copy();
      row.setId(null); row.setMaterialCode("PERF-" + i); row.setRevisionNo(1);
      prices.insert(row); codes.add(row.getMaterialCode());
      for (String org : List.of("210", "220")) requests.add(request(row.getMaterialCode(), "2026-11", org, row.getPriceType()));
    }
    var data = new CountingDataSource(jdbc.getDataSource());
    var counted = new SettlementPriceReaderImpl(new SettlementPriceRepository(new JdbcTemplate(data)),
        new SettlementBasePriceRepository(new JdbcTemplate(data)), new SettlementPriceCalculator(), json);
    jdbc.queryForList("ANALYZE TABLE lp_settlement_price");
    long start = System.nanoTime();
    var result = counted.resolveBatch(requests);
    long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(result).hasSize(2000); assertThat(data.selects).hasSize(2);
    result.forEach((request, price) -> assertThat(price.unitPrice()).isEqualByComparingTo(
        request.priceType() == SettlementPriceType.SETTLE_LINKED ? "4.841024707965" : "4.1658"));
    // EXPLAIN the exact captured batched SQL. IN ordering is immaterial to its plan.
    var parameters = new ArrayList<Object>(); parameters.add("COMMERCIAL"); parameters.add("2026-11"); parameters.addAll(codes);
    var plan = jdbc.queryForList("EXPLAIN " + data.selects.getFirst(), parameters.toArray());
    var indexes = jdbc.queryForList("SHOW INDEX FROM lp_settlement_price");
    assertThat(indexes).anySatisfy(index -> assertThat(index.get("Column_name")).isEqualTo("material_code"));
    System.out.println("STP_PERFORMANCE " + json.writeValueAsString(java.util.Map.of(
        "distinctMaterials", 1000, "requests", 2000, "ruleQueries", 1, "baseQueries", 1,
        "elapsedMs", elapsedMs, "plan", plan, "indexes", indexes)));
  }

  private void confirmSampleWeights() {
    var row = prices.effective(october, "203240646", SettlementPriceType.SETTLE_LINKED).getFirst();
    weights.confirm("COMMERCIAL", row.getId(), new SettlementWeightService.Change(new BigDecimal("7.8"), new BigDecimal("1.48"),
        row.getRevisionNo(), "隔离测试中明确确认样例，用于独立公式算术验证"), "test-finance");
  }

  private static class CountingDataSource extends DelegatingDataSource {
    final List<String> selects = new ArrayList<>();
    CountingDataSource(javax.sql.DataSource source) { super(source); }
    @Override public Connection getConnection() throws SQLException { return counted(super.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return counted(super.getConnection(user, password)); }
    private Connection counted(Connection connection) {
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT")) selects.add(sql);
        try { return method.invoke(connection, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
      });
    }
  }

  private SettlementPriceRequest request(String code, String month, String org, SettlementPriceType type) {
    return new SettlementPriceRequest("COMMERCIAL", code, month, org, type);
  }
}
