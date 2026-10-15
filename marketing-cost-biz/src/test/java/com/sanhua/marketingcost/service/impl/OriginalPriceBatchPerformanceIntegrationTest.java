package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.pricing.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real SQL / formal native rules. Cold means no application result rows, not a cold disk cache. */
@Tag("integration")
@EnabledIfSystemProperty(named = "prc.performance", matches = "true")
@TestPropertySource(
    properties = {
      "srm.fixed-price.enabled=false",
      "oa.person-directory.enabled=false",
      "oa.person-directory.run-at-startup=false",
      "monthly-reprice.enabled=false",
      "cms.sync.publish.enabled=false"
    })
class OriginalPriceBatchPerformanceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired LinkedPriceEnsureService linked;
  @Autowired RangePriceResolver range;
  @Autowired OriginalLinkedImportService linkedImports;
  @Autowired OriginalRangeImportService rangeImports;
  @Autowired FinanceFactorPublicationService publications;
  @Autowired JdbcTemplate jdbc;
  @Autowired SqlSessionFactory sessions;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ObjectMapper json;
  private final SqlCounter counter = new SqlCounter();
  private final List<Map<String, Object>> measurements = new ArrayList<>();
  private final Map<String, Double> setupMillis = new LinkedHashMap<>();
  private String originalHash;
  private double concurrentMillis;
  private final String oa = "PRC10-PERFORMANCE";
  private final String month = "2026-10";
  private final LocalDateTime asOf = LocalDateTime.of(2026, 10, 1, 12, 0);

  @Test
  void realBatchQueriesColdReuseConcurrencyAndRevisionChanges() throws Exception {
    identity();
    sessions.getConfiguration().addInterceptor(counter);
    publishFinance("CU_90000");
    jdbc.update(
        "INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',100000)",
        oa);
    long importStart = System.nanoTime();
    importLinked(workbook(false, 5000));
    setupMillis.put("linked5000GeneratePreviewPublish", elapsedMillis(importStart));
    importStart = System.nanoTime();
    importRange(workbook(true, 5000));
    setupMillis.put("range5000GeneratePreviewPublish", elapsedMillis(importStart));
    originalHash = originalsHash();
    // Prime classes/JIT without retaining price results for the measured keys.
    linkedBoth(10, 90, 100, false);
    rangeBoth(10, 100, 140);
    for (int size : List.of(100, 1000, 5000)) {
      int repeats = size == 1000 ? 20 : 5;
      for (int i = 0; i < repeats; i++) {
        jdbc.update("DELETE FROM lp_price_linked_calc_item WHERE oa_no=?", oa);
        measure("linked", "cold-result", size, () -> linkedBoth(size, 90, 100, false));
        measure("linked", "valid-reuse", size, () -> linkedBoth(size, 90, 100, true));
        measure(
            "range",
            i == 0 ? "first-read" : "repeat-read-recompute",
            size,
            () -> rangeBoth(size, 100, 140));
        if (i % 5 == 0)
          System.out.printf("PRC-10 verified %d independent keys, sample %d%n", size, i + 1);
      }
    }
    // Concurrent readers share real persisted results but must retain each side's identity/price.
    long concurrentStart = System.nanoTime();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            pool.submit(
                () -> {
                  identity();
                  try {
                    linkedBoth(1000, 90, 100, true);
                    rangeBoth(1000, 100, 140);
                    return 2000;
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      for (var job : jobs) assertThat(job.get(120, TimeUnit.SECONDS)).isEqualTo(2000);
    }
    concurrentMillis = elapsedMillis(concurrentStart);
    publishFinance("CU_100000");
    measure("linked", "finance-revision", 1000, () -> linkedBoth(1000, 100, 100, false));
    measure("range", "finance-revision", 1000, () -> rangeBoth(1000, 140, 140));
    jdbc.update("UPDATE oa_form SET copper_price=110000 WHERE oa_no=?", oa);
    measure("linked", "customer-revision", 1000, () -> linkedBoth(1000, 100, 110, false));
    measure("range", "customer-revision", 1000, () -> rangeBoth(1000, 140, 180));
    assertThat(originalsHash()).isEqualTo(originalHash);
    var explain = new LinkedHashMap<String, Object>();
    String monthSql =
        sessions
            .getConfiguration()
            .getMappedStatement(
                com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper.class.getName()
                    + ".findLatestMonth")
            .getBoundSql(
                Map.of("businessUnit", "COMMERCIAL", "orgCode", "210", "requestedMonth", month))
            .getSql();
    var monthPlan =
        jdbc.queryForList(
            "EXPLAIN " + monthSql, "COMMERCIAL", "210", month, "COMMERCIAL", "210", month);
    for (String table : List.of("lp_price_linked_item", "lp_price_range_factor_rule")) {
      assertThat(monthPlan.stream().filter(row -> table.equals(row.get("table"))).toList())
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.get("key"))
                    .isEqualTo(
                        table.equals("lp_price_linked_item")
                            ? "idx_linked_scope_month"
                            : "idx_range_scope_month");
                assertThat(row.get("Extra").toString()).contains("Using index");
              });
    }
    explain.put("actualMonthSelection", monthPlan);
    var candidatePlan =
        jdbc.queryForList("EXPLAIN " + counter.ruleSql, counter.ruleParameters.toArray());
    assertThat(candidatePlan)
        .singleElement()
        .satisfies(row -> assertThat(row.get("key")).isEqualTo("idx_linked_import_version_lookup"));
    explain.put("actualLinkedCandidates1000", candidatePlan);
    explain.put(
        "linkedCandidatesWithoutIndexHint1000",
        jdbc.queryForList(
            "EXPLAIN "
                + counter.ruleSql.replace("FORCE INDEX (idx_linked_import_version_lookup)", ""),
            counter.ruleParameters.toArray()));
    explain.put(
        "linked",
        jdbc.queryForList(
            "EXPLAIN SELECT * FROM lp_price_linked_item WHERE business_unit_type='COMMERCIAL' AND"
                + " org_code='210' AND source_kind='PUBLIC' AND rule_status='PUBLISHED' AND"
                + " pricing_month<='2026-10' AND material_code IN ('PERF-L-00000','PERF-L-00999')"
                + " ORDER BY pricing_month DESC,id DESC"));
    explain.put(
        "range",
        jdbc.queryForList(
            "EXPLAIN SELECT * FROM lp_price_range_factor_rule WHERE business_unit_type='COMMERCIAL'"
                + " AND org_code='210' AND pricing_month<='2026-10' AND material_code IN"
                + " ('PERF-R-00000','PERF-R-00999')"));
    writeReport(true, originalHash, explain);
    SecurityContextHolder.clearContext();
  }

  private void identity() {
    var auth = new UsernamePasswordAuthenticationToken("prc10", "unused", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private void publishFinance(String scheme) throws Exception {
    byte[] bytes;
    try (var in = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      bytes = in.readAllBytes();
    }
    var scope = new FinanceFactorImportContext("COMMERCIAL", "210", month, null, scheme);
    var preview = publications.preview(bytes, "performance-finance.xlsx", scope);
    publications.publish(
        bytes, "performance-finance.xlsx", scope, preview.previewFingerprint(), "prc10");
  }

  private void importLinked(byte[] bytes) {
    var command =
        new PriceLinkedImportCommand(
            bytes, "performance-linked.xlsx", month, "COMMERCIAL", false, null, null, null, null);
    var preview = linkedImports.preview(command);
    assertThat(preview.getErrors()).isEmpty();
    assertThat(preview.getBusinessRowCount()).isEqualTo(5000);
    var accepted =
        new PriceLinkedImportCommand(
                bytes,
                "performance-linked.xlsx",
                month,
                "COMMERCIAL",
                false,
                null,
                null,
                null,
                preview.getFileSha256())
            .withPreviewFingerprint(preview.getPreviewFingerprint());
    linkedImports.confirm(accepted);
  }

  private void importRange(byte[] bytes) {
    var command =
        new OriginalRangeImportService.Command(
            bytes, "performance-range.xlsx", "COMMERCIAL", month, null, null);
    var preview = rangeImports.preview(command);
    assertThat(preview.errors()).isEmpty();
    assertThat(preview.businessRowCount()).isEqualTo(5000);
    rangeImports.confirm(
        new OriginalRangeImportService.Command(
            bytes,
            command.fileName(),
            "COMMERCIAL",
            month,
            preview.fileSha256(),
            preview.previewFingerprint()));
  }

  private int linkedBoth(int size, int financial, int customer, boolean expectAllReused) {
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              int calculated = 0;
              var codes = new LinkedHashSet<String>();
              for (int i = 0; i < size; i++) codes.add(code(false, i));
              for (var side :
                  List.of(
                      QuotePriceScenarioType.FINANCE_QUOTE_BASE,
                      QuotePriceScenarioType.OA_LOCKED)) {
                var request = LinkedPriceEnsureRequest.quote(oa, "COMMERCIAL", month, codes);
                request.setPriceOrgCode("210");
                request.setPriceAsOfTime(asOf);
                request.setPriceScenarioType(side);
                if (side == QuotePriceScenarioType.FINANCE_QUOTE_BASE)
                  request.setVariableOverrides(Map.of("Cu", BigDecimal.valueOf(financial)));
                var result = linked.ensure(request);
                assertThat(result.getFailedItems()).isEmpty();
                assertThat(result.getItems()).hasSize(size);
                if (expectAllReused) assertThat(result.getSkippedCount()).isEqualTo(size);
                calculated += result.getCreatedCount() + result.getUpdatedCount();
                int copper =
                    side == QuotePriceScenarioType.FINANCE_QUOTE_BASE ? financial : customer;
                for (int i = 0; i < size; i++) {
                  var actual = result.getItems().get(code(false, i));
                  var expected =
                      BigDecimal.valueOf(copper + 5 + i % 7)
                          .divide(new BigDecimal("1.13"), 6, RoundingMode.HALF_UP);
                  assertThat(actual.getCalcStatus()).isEqualTo("OK");
                  assertThat(actual.getId()).isPositive();
                  assertThat(actual.getPartUnitPrice())
                      .as("%s %s", side, actual.getItemCode())
                      .isEqualByComparingTo(expected);
                  assertThat(actual.getInputSnapshotJson()).contains(code(false, i));
                }
              }
              return calculated;
            });
  }

  private int rangeBoth(int size, int financial, int customer) {
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              for (var side :
                  List.of(
                      QuotePriceScenarioType.FINANCE_QUOTE_BASE,
                      QuotePriceScenarioType.OA_LOCKED)) {
                var run = new CostRunContext();
                run.setBusinessUnitType("COMMERCIAL");
                run.setPriceOrgCode("210");
                run.setPricingMonth(month);
                run.setPriceAsOfTime(asOf);
                run.setPriceScenarioType(side.name());
                var requests = new ArrayList<PriceResolveRequest>();
                for (int i = 0; i < size; i++) {
                  var item = new CostRunPartItemDto();
                  item.setPartCode(code(true, i));
                  item.setPartQty(BigDecimal.ONE);
                  requests.add(new PriceResolveRequest(item, null, run));
                }
                var results = range.resolveBatch(oa, requests);
                assertThat(results).hasSize(size);
                int base = side == QuotePriceScenarioType.FINANCE_QUOTE_BASE ? financial : customer;
                for (int i = 0; i < size; i++) {
                  var result = results.get(requests.get(i));
                  assertThat(result.unitPrice())
                      .as(result.remark())
                      .isEqualByComparingTo(BigDecimal.valueOf(base + i % 7));
                  assertThat(result.evidence().inputSnapshotJson()).contains(code(true, i));
                }
              }
              return size * 2;
            });
  }

  private void measure(String kind, String mode, int size, Supplier<Integer> call)
      throws Exception {
    var stats = new Statistics();
    counter.active.set(stats);
    long start = System.nanoTime();
    int calculations;
    try {
      calculations = call.get();
    } finally {
      counter.active.remove();
    }
    var row = new LinkedHashMap<String, Object>();
    row.put("kind", kind);
    row.put("mode", mode);
    row.put("independentKeys", size);
    row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000.0);
    row.put("selectStatements", stats.reads);
    row.put("writeStatements", stats.writes);
    row.put("bothSideCalculations", calculations);
    row.put("sqlPatterns", stats.patterns);
    row.put("selectAndMappingMs", stats.queryNanos / 1_000_000.0);
    row.put("selectAndMappingByTableMs", stats.queryMillis);
    measurements.add(row);
    writeReport(false, originalHash, Map.of());
    if (mode.equals("valid-reuse")) {
      assertThat(calculations).isZero();
      assertThat(stats.writes).isZero();
    }
    // Database reads should be bounded by batches, never one financial/rule lookup per key.
    assertThat(stats.reads).as("%s/%s/%s", kind, mode, size).isLessThan(50);
  }

  private double elapsedMillis(long start) {
    return (System.nanoTime() - start) / 1_000_000.0;
  }

  private String originalsHash() throws Exception {
    var rows =
        Map.of(
            "linked",
            jdbc.queryForList(
                "SELECT * FROM lp_price_linked_item WHERE material_code LIKE 'PERF-L-%' ORDER BY"
                    + " id"),
            "range",
            jdbc.queryForList(
                "SELECT * FROM lp_price_range_factor_rule WHERE material_code LIKE 'PERF-R-%' ORDER"
                    + " BY id"),
            "bands",
            jdbc.queryForList(
                "SELECT * FROM lp_price_range_item WHERE material_code LIKE 'PERF-R-%' ORDER BY"
                    + " id"));
    return PriceLinkedImportFileDigest.sha256(json.writeValueAsBytes(rows));
  }

  private void writeReport(boolean passed, String sourceHash, Map<String, Object> explain)
      throws Exception {
    var output = Path.of(System.getProperty("prc.report"));
    Files.createDirectories(output.getParent());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            output.toFile(),
            Map.of(
                "passed",
                passed,
                "measurements",
                measurements,
                "originalRulesSha256",
                sourceHash,
                "explain",
                explain,
                "environment",
                Map.of(
                    "java",
                    System.getProperty("java.version"),
                    "os",
                    System.getProperty("os.name"),
                    "architecture",
                    System.getProperty("os.arch"),
                    "mysql",
                    MYSQL.getDockerImageName()),
                "measurementBoundary",
                "Two formal sides including transaction, SQL, result validation and six-place money"
                    + " assertions; cold means absent linked result rows. Range repeat reads still"
                    + " calculate and are not described as result-cache reuse.",
                "calculationCounting",
                "Native linked successful cache misses (created + updated); range successful native"
                    + " results on both sides.",
                "concurrentReaderThreads",
                passed ? 4 : 0,
                "concurrentElapsedMs",
                concurrentMillis,
                "setupMillis",
                setupMillis));
  }

  private String code(boolean range, int number) {
    return String.format(Locale.ROOT, "PERF-%s-%05d", range ? "R" : "L", number);
  }

  private byte[] workbook(boolean range, int size) throws Exception {
    try (var book = new XSSFWorkbook();
        var bytes = new ByteArrayOutputStream()) {
      var main = book.createSheet("ImportData");
      String[] headers = {"组织", "物料代码", "物料名称", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      var header = main.createRow(0);
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      var source = book.createSheet(range ? "铜价矩阵" : "因素");
      if (range) {
        source.createRow(0).createCell(0).setCellValue("铜价区间原稿");
        var bands = source.createRow(1);
        bands.createCell(0).setCellValue("U9代码");
        bands.createCell(1).setCellValue("图号");
        bands.createCell(2).setCellValue("供应商名称");
        var tax = source.createRow(2);
        for (int i = 0; i < 4; i++) {
          bands.createCell(3 + 2 * i).setCellValue((90000 + 5000 * i) + "-" + (94999 + 5000 * i));
          tax.createCell(3 + 2 * i).setCellValue("未税");
          tax.createCell(4 + 2 * i).setCellValue("含税");
        }
      } else {
        var h = source.createRow(0);
        String[] names = {"序号", "名称", "简称", "取价来源", "价格", "单位"};
        for (int i = 0; i < names.length; i++) h.createCell(i).setCellValue(names[i]);
        var row = source.createRow(1);
        row.createCell(0).setCellValue(1);
        row.createCell(1).setCellValue("电解铜");
        row.createCell(2).setCellValue("1#Cu");
        row.createCell(3).setCellValue("平均价");
        row.createCell(4).setCellValue(109.114);
        row.createCell(5).setCellValue("公斤");
      }
      for (int i = 0; i < size; i++) {
        var row = main.createRow(i + 1);
        String[] values = {"210", code(range, i), "压力验收", "验收供应商", "PERF-SUP", "PERF-SPEC", "公斤"};
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
        row.createCell(8).setCellValue(false);
        if (range) {
          row.createCell(7).setCellValue(100 + i % 7);
          var matrix = source.createRow(i + 3);
          matrix.createCell(0).setCellValue(code(true, i));
          matrix.createCell(1).setCellValue("PERF-SPEC");
          matrix.createCell(2).setCellValue("验收供应商");
          for (int c = 0; c < 4; c++) {
            double price = 100 + i % 7 + 20 * c;
            matrix.createCell(3 + c * 2).setCellValue(price);
            matrix.createCell(4 + c * 2).setCellValue(price * 1.13);
          }
        } else row.createCell(7).setCellFormula("ROUND(因素!$E$2+5+" + (i % 7) + ",4)/1.13");
      }
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(bytes);
      return bytes.toByteArray();
    }
  }

  private static class Statistics {
    int reads;
    int writes;
    long queryNanos;
    final Map<String, Double> queryMillis = new LinkedHashMap<>();
    final Map<String, Integer> patterns = new LinkedHashMap<>();
  }

  @Intercepts({
    @Signature(
        type = StatementHandler.class,
        method = "prepare",
        args = {Connection.class, Integer.class}),
    @Signature(
        type = StatementHandler.class,
        method = "query",
        args = {java.sql.Statement.class, org.apache.ibatis.session.ResultHandler.class})
  })
  static class SqlCounter implements Interceptor {
    final ThreadLocal<Statistics> active = new ThreadLocal<>();
    String ruleSql;
    List<Object> ruleParameters;

    public Object intercept(Invocation invocation) throws Throwable {
      var stats = active.get();
      if (stats != null && invocation.getMethod().getName().equals("query")) {
        var bound = ((StatementHandler) invocation.getTarget()).getBoundSql();
        String sql = bound.getSql();
        if (ruleSql == null
            && sql.contains("FORCE INDEX (idx_linked_import_version_lookup)")
            && bound.getParameterMappings().size() > 900) {
          ruleSql = sql;
          var parameters =
              org.apache.ibatis.reflection.SystemMetaObject.forObject(bound.getParameterObject());
          ruleParameters =
              bound.getParameterMappings().stream()
                  .map(
                      mapping -> {
                        String property = mapping.getProperty();
                        return bound.hasAdditionalParameter(property)
                            ? bound.getAdditionalParameter(property)
                            : parameters.getValue(property);
                      })
                  .toList();
        }
        var from = java.util.regex.Pattern.compile("(?i)\\bFROM\\s+([a-z_]+)").matcher(sql);
        String table = from.find() ? from.group(1) : "unknown";
        if (sql.stripLeading().startsWith("SELECT MAX(pricing_month)")) table += "/latest-month";
        long start = System.nanoTime();
        try {
          return invocation.proceed();
        } finally {
          long elapsed = System.nanoTime() - start;
          stats.queryNanos += elapsed;
          stats.queryMillis.merge(table, elapsed / 1_000_000.0, Double::sum);
        }
      }
      if (stats != null) {
        String sql =
            ((StatementHandler) invocation.getTarget())
                .getBoundSql()
                .getSql()
                .strip()
                .replaceAll("\\s+", " ");
        if (sql.toUpperCase(Locale.ROOT).startsWith("SELECT")) stats.reads++;
        else stats.writes++;
        stats.patterns.merge(sql.replaceAll("[?, ]{10,}", " ? ... "), 1, Integer::sum);
      }
      return invocation.proceed();
    }
  }
}
