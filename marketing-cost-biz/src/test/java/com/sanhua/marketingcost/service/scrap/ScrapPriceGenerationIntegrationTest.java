package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 原始废料和财务工作簿、两个实际发布入口与生产月价计算器。 */
@Tag("integration")
class ScrapPriceGenerationIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("scrap_generation_test")
          .withUsername("root")
          .withPassword("test-only-password");
  static final List<String> SCRIPTS =
      List.of(
          "prc01_pre_migration_schema.sql",
          "V308__original_price_version_contract.sql",
          "V309__finance_factor_publication_note.sql",
          "V311__original_linked_expression_capacity.sql",
          "V325__finance_effective_price.sql",
          "V326__original_workbook_source.sql",
          "V330__scrap_price_monthly.sql");
  static SqlSessionTemplate sql;
  static JdbcTemplate jdbc;
  static DataSourceTransactionManager transactions;
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final ScrapPriceRuleCodec rules = new ScrapPriceRuleCodec(json);
  ScrapPriceMapper prices;
  ScrapPriceCalculator calculator;
  ScrapPriceGenerationService generation;
  ScrapPriceImportService imports;
  FinanceFactorPublicationService publication;
  FinanceFactorCuAdjustmentService copper;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    for (String script : SCRIPTS) {
      MYSQL.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/" + script);
      var migrated =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot scrap_generation_test < \"$1\"",
              "migration",
              "/tmp/" + script);
      assertThat(migrated.getExitCode()).as(migrated.getStderr()).isZero();
    }
    var data =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    transactions = new DataSourceTransactionManager(data);
    jdbc = new JdbcTemplate(data);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(
        new Environment("scrap-generation", new SpringManagedTransactionFactory(), data));
    for (var mapper :
        List.of(
            ScrapPriceMapper.class,
            FactorIdentityMapper.class,
            FactorUploadBatchMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class,
            FinanceEffectivePriceMapper.class,
            PriceLinkedItemMapper.class,
            PriceRangeFactorRuleMapper.class)) config.addMapper(mapper);
    sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
  }

  @Test
  void capturedPriceSurvivesNativeMysqlJsonWithoutFloatingPointLoss() throws Exception {
    var snapshot =
        ScrapPriceTestSupport.captured("2026-10", "95.1234567891234567", "110000.123456789123");
    String original = json.writeValueAsString(snapshot);
    var numeric = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
    numeric.put("unitPrice", snapshot.unitPrice());
    String unsafeNumeric = json.writeValueAsString(numeric);
    String stored =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(
                status -> {
                  jdbc.execute(
                      "CREATE TEMPORARY TABLE scrap_snapshot_roundtrip (payload JSON NOT NULL)");
                  jdbc.update(
                      "INSERT INTO scrap_snapshot_roundtrip(payload) VALUES (?)", unsafeNumeric);
                  String rounded =
                      jdbc.queryForObject(
                          "SELECT payload FROM scrap_snapshot_roundtrip", String.class);
                  assertThatThrownBy(() -> new ScrapPriceCalculationEvidence(json).read(rounded))
                      .hasMessageContaining("公式输入或金额不一致");
                  jdbc.update("DELETE FROM scrap_snapshot_roundtrip");
                  jdbc.update("INSERT INTO scrap_snapshot_roundtrip(payload) VALUES (?)", original);
                  return jdbc.queryForObject(
                      "SELECT payload FROM scrap_snapshot_roundtrip", String.class);
                });
    var decoded = new ScrapPriceCalculationEvidence(json).read(stored);
    assertThat(decoded.unitPrice()).isEqualByComparingTo(snapshot.unitPrice());
    assertThat(decoded.inputs()).isEqualTo(snapshot.inputs());
    assertThat(decoded.context().customerCopperPricePerTon())
        .isEqualByComparingTo("110000.123456789123");
    assertThat(decoded.context().financial().factors())
        .isEqualTo(snapshot.context().financial().factors());
    var parsed = json.readTree(stored);
    assertThat(parsed.path("unitPrice").isTextual()).isTrue();
    assertThat(parsed.path("inputs").path("Cu").isTextual()).isTrue();
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM lp_scrap_price WHERE source_rule_id IS NOT NULL");
    for (String table :
        List.of(
            "lp_scrap_price",
            "lp_finance_effective_price",
            "lp_price_linked_item",
            "lp_price_range_factor_rule",
            "lp_factor_row_ref",
            "lp_factor_monthly_price",
            "lp_factor_upload_batch",
            "lp_factor_identity")) jdbc.update("DELETE FROM " + table);
    prices = sql.getMapper(ScrapPriceMapper.class);
    var identities = sql.getMapper(FactorIdentityMapper.class);
    var batches = sql.getMapper(FactorUploadBatchMapper.class);
    var monthly = sql.getMapper(FactorMonthlyPriceMapper.class);
    var sources = sql.getMapper(FactorRowRefMapper.class);
    calculator = spy(new ScrapPriceCalculator(rules));
    generation =
        new ScrapPriceGenerationService(
            prices,
            identities,
            batches,
            new FinanceFactorPriceReader(batches, monthly, identities, sources),
            rules,
            calculator,
            transactions);
    imports =
        new ScrapPriceImportService(
            new ScrapPriceWorkbookParser(json),
            prices,
            rules,
            transactions,
            identities,
            generation,
            batches);
    var canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    publication =
        new FinanceFactorPublicationService(
            new FinanceFactorWorkbookParser(canonical),
            canonical,
            identities,
            batches,
            monthly,
            sources,
            json,
            transactions,
            FinanceEffectivePriceTestSupport.service(sql, json),
            generation);
    copper =
        new FinanceFactorCuAdjustmentService(monthly, batches, identities, sources, publication);
  }

  @Test
  void bothImportOrdersProduceAllTwelveExactPricesAndActualFinancialEvidence() throws Exception {
    importOriginal("2026-10");
    assertThat(rows("2026-10").stream().filter(r -> r.getCalcStatus().equals("WAITING_BASE")))
        .hasSize(6);
    publish("2026-10", financeFile());
    assertOriginalPrices("2026-10", "90", "52.6630228222902", "24.26");
    assertThat(rows("2026-10").stream().filter(r -> r.getPriceType().equals("LINKED")))
        .allSatisfy(
            row -> {
              assertThat(row.getFinanceOrgCode()).isEqualTo("210");
              assertThat(row.getFinanceMonth()).isEqualTo("2026-10");
              assertThat(row.getFinanceRevision()).isEqualTo(1);
              assertThat(row.getCalculationJson())
                  .contains("SCRAP_PRICE_V1", "ruleSourceMonth", "financial", "variables");
            });
    jdbc.update("DELETE FROM lp_scrap_price");
    importOriginal("2026-10");
    assertOriginalPrices("2026-10", "90", "52.6630228222902", "24.26");
  }

  @Test
  void readOnlyFutureProjectionAndPreparationAgreeWithoutPreviewWrites() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var scopes = scopeReader();
    String before = json.writeValueAsString(prices.selectList(null));
    var preview = scopes.read("COMMERCIAL", "2026-11", false);
    assertThat(preview.byMaterial()).hasSize(12);
    var linked = preview.byMaterial().get("301990315").getFirst();
    assertThat(linked.getId()).isNull();
    assertThat(linked.getSourceRuleId()).isNotNull();
    assertThat(linked.getPriceMonth()).isEqualTo("2026-11");
    assertThat(linked.getRuleSourceMonth()).isEqualTo("2026-10");
    assertThat(linked.getFinanceMonth()).isEqualTo("2026-10");
    assertThat(json.writeValueAsString(prices.selectList(null))).isEqualTo(before);
    var prepared = scopes.read("COMMERCIAL", "2026-11", true);
    for (var code : preview.byMaterial().keySet()) {
      var saved = prepared.byMaterial().get(code).getFirst();
      assertThat(saved.getId()).isPositive();
      assertThat(saved.getUnitPrice())
          .isEqualByComparingTo(preview.byMaterial().get(code).getFirst().getUnitPrice());
    }
    assertThat(scopes.read("COMMERCIAL", "2026-09", false).byMaterial()).isEmpty();
    assertThat(scopes.read("COMMERCIAL", "2026-09", false).managedMaterials()).hasSize(12);
  }

  @Test
  void scopeCacheIsTransactionBoundAndFreshPreparationEscapesOldRepeatableReadView()
      throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var scopes = spy(scopeReader());
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.executeWithoutResult(
        status -> {
          // 固定外层旧视图；补月的REQUIRES_NEW提交后仍必须返回新事务结果。
          assertThat(rows("2026-11")).isEmpty();
          var prepared = scopes.read("COMMERCIAL", "2026-11", true);
          assertThat(prepared.byMaterial()).hasSize(12);
          assertThat(scopes.read("COMMERCIAL", "2026-11", true)).isSameAs(prepared);
          assertThat(rows("2026-11")).isEmpty();
        });
    assertThat(rows("2026-11")).hasSize(12);
    assertThat(
            org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(
                scopes))
        .isFalse();
  }

  private ScrapPriceScopeReader scopeReader() {
    return new ScrapPriceScopeReader(
        prices,
        generation,
        new FinanceFactorPriceReader(
            sql.getMapper(FactorUploadBatchMapper.class),
            sql.getMapper(FactorMonthlyPriceMapper.class),
            sql.getMapper(FactorIdentityMapper.class),
            sql.getMapper(FactorRowRefMapper.class)),
        calculator);
  }

  @Test
  void nonTransactionalBatchPreparesOnceAndNextBatchSeesNewPublication() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var counted = spy(generation);
    var scopes =
        new ScrapPriceScopeReader(
            prices,
            counted,
            new FinanceFactorPriceReader(
                sql.getMapper(FactorUploadBatchMapper.class),
                sql.getMapper(FactorMonthlyPriceMapper.class),
                sql.getMapper(FactorIdentityMapper.class),
                sql.getMapper(FactorRowRefMapper.class)),
            calculator);
    try (var batch = scopes.openBatch()) {
      var first = scopes.read("COMMERCIAL", "2026-10", true);
      for (int n = 0; n < 100; n++)
        assertThat(scopes.read("COMMERCIAL", "2026-10", true)).isSameAs(first);
      try (var nested = scopes.openBatch()) {
        assertThat(scopes.read("COMMERCIAL", "2026-10", true)).isSameAs(first);
      }
      assertThat(scopes.read("COMMERCIAL", "2026-10", true)).isSameAs(first);
    }
    verify(counted, times(1)).prepareMonth("COMMERCIAL", "2026-10", "PRICE_PREPARE");
    adjustCopper("2026-10", "95000");
    try (var batch = scopes.openBatch()) {
      var current = scopes.read("COMMERCIAL", "2026-10", true);
      assertThat(current.byMaterial().get("301990315").getFirst().getUnitPrice())
          .isEqualByComparingTo(
              new BigDecimal("95")
                  .multiply(new BigDecimal(".982"))
                  .divide(new BigDecimal("1.13"), 18, RoundingMode.HALF_UP));
    }
    verify(counted, times(2)).prepareMonth("COMMERCIAL", "2026-10", "PRICE_PREPARE");
  }

  @Test
  void failedBatchReleasesItsViewAndTransactionsKeepIndependentViews() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var scopes = scopeReader();
    var captured = new java.util.concurrent.atomic.AtomicReference<ScrapPriceScopeReader.Scope>();
    assertThatThrownBy(
            () -> {
              try (var batch = scopes.openBatch()) {
                captured.set(scopes.read("COMMERCIAL", "2026-10", false));
                new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status ->
                            assertThat(scopes.read("COMMERCIAL", "2026-10", false))
                                .isNotSameAs(captured.get()));
                assertThat(scopes.read("COMMERCIAL", "2026-10", false)).isSameAs(captured.get());
                throw new IllegalArgumentException("batch failed");
              }
            })
        .hasMessage("batch failed");
    assertThat(scopes.read("COMMERCIAL", "2026-10", false)).isNotSameAs(captured.get());
  }

  @Test
  void realCopperAdjustmentChangesOnlyFourCopperAmountsAndRetainsSixFixedRows() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var before = rows("2026-10");
    String fixed = fixedSnapshot();
    adjustCopper("2026-10", "95000");
    assertOriginalPrices("2026-10", "95", "52.6630228222902", "24.26");
    assertThat(fixedSnapshot()).isEqualTo(fixed);
    assertThat(
            before.stream()
                .filter(
                    old ->
                        old.getUnitPrice()
                                .compareTo(row("2026-10", old.getMaterialCode()).getUnitPrice())
                            != 0))
        .extracting(ScrapPrice::getMaterialCode)
        .containsExactlyInAnyOrder("301990315", "301990317", "301990135", "301991027");
    assertThat(rows("2026-10").stream().filter(r -> r.getPriceType().equals("LINKED")))
        .allSatisfy(row -> assertThat(row.getFinanceRevision()).isEqualTo(2));
  }

  @Test
  void fullWorkbookPublicationRefreshesBrassAndAluminumIndependently() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    String fixed = fixedSnapshot();
    publish("2026-10", financeFile("G9", 55));
    assertOriginalPrices("2026-10", "90", "55", "24.26");
    publish("2026-10", financeFile("G9", 55, "E5", 30));
    assertOriginalPrices("2026-10", "90", "55", "30");
    assertThat(fixedSnapshot()).isEqualTo(fixed);
  }

  @Test
  void futureFinanceIsNotUsedAndMissingEarlierFinanceCanBePublishedLater() throws Exception {
    publish("2026-11", financeFile());
    importOriginal("2026-10");
    assertThat(rows("2026-10").stream().filter(r -> r.getPriceType().equals("LINKED")))
        .allSatisfy(
            row -> {
              assertThat(row.getCalcStatus()).isEqualTo("WAITING_BASE");
              assertThat(row.getUnitPrice()).isNull();
            });
    assertThat(generation.prepareMonth("COMMERCIAL", "2026-09", "test").requestedPrices())
        .isEmpty();
    publish("2026-09", financeFile());
    assertOriginalPrices("2026-10", "90", "52.6630228222902", "24.26");
    assertThat(row("2026-10", "301990315").getFinanceMonth()).isEqualTo("2026-09");
  }

  @Test
  void newMonthCarriesRulesAndPartialImportDoesNotHideTheOtherEleven() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    assertThat(rows("2026-11"))
        .hasSize(12)
        .allSatisfy(
            row -> {
              assertThat(row.getRuleSourceMonth()).isEqualTo("2026-10");
              assertThat(row.getOriginType()).isEqualTo("CARRY_FORWARD");
            });
    importFile(partialFixed(8), "2026-11");
    assertThat(rows("2026-11")).hasSize(12);
    assertThat(row("2026-11", "301990444").getOriginType()).isEqualTo("IMPORT");
    assertThat(row("2026-11", "301990444").getUnitPrice()).isEqualByComparingTo("8");
    publish("2026-11", financeFile());
    adjustCopper("2026-11", "95000");
    assertThat(row("2026-11", "301990315").getUnitPrice())
        .isEqualByComparingTo(linked("95", "0.982"));
    assertThat(row("2026-10", "301990315").getUnitPrice())
        .isEqualByComparingTo(linked("90", "0.982"));
  }

  @Test
  void oldMonthRevisionPropagatesUntilTheNextExplicitRuleForThatIdentity() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    importFile(partialFixed(8), "2026-12");
    generation.prepareMonth("COMMERCIAL", "2027-01", "test");
    var december = row("2026-12", "301990444");
    var january = row("2027-01", "301990444");
    var october = row("2026-10", "301990444");
    editFixed(october, "9");
    assertThat(row("2026-11", "301990444").getUnitPrice()).isEqualByComparingTo("9");
    assertThat(row("2026-11", "301990444").getSourceRuleRevision()).isEqualTo(2);
    assertThat(json.writeValueAsString(row("2026-12", "301990444")))
        .isEqualTo(json.writeValueAsString(december));
    assertThat(json.writeValueAsString(row("2027-01", "301990444")))
        .isEqualTo(json.writeValueAsString(january));
    importFile(
        ScrapPriceWorkbookParserTest.change(
            s -> s.getRow(6).getCell(5).setCellFormula("($M$1*0.95)/1.13")),
        "2026-10");
    for (String month : List.of("2026-10", "2026-11", "2026-12", "2027-01"))
      assertThat(row(month, "301990135").getUnitPrice()).isEqualByComparingTo(linked("90", "0.95"));
  }

  @Test
  void explicitImportOfAnUnchangedCarryForwardCreatesACutoffForOlderEdits() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    importFile(partialFixed(7.566), "2026-11");
    editFixed(row("2026-10", "301990444"), "9");
    assertThat(row("2026-11", "301990444").getUnitPrice()).isEqualByComparingTo("7.566");
    assertThat(row("2026-11", "301990444").getRuleSourceMonth()).isEqualTo("2026-11");
  }

  @Test
  void unchangedPreparationAndReadOnlyDetailDoNotRecalculateOrWrite() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    String before = snapshot();
    clearInvocations(calculator);
    var result = generation.prepareMonth("COMMERCIAL", "2026-10", "test");
    assertThat(result.generated()).isZero();
    assertThat(result.reused()).isEqualTo(12);
    verify(calculator, never()).calculate(any());
    var query = new ScrapPriceQueryService(prices);
    query.detail("COMMERCIAL", row("2026-10", "301990315").getId());
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void aNewDefaultSchemeIsReadFromThePublicationInsteadOfHardCoded() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_100000");
    byte[] file = financeFile();
    var preview = publication.preview(file, "finance.xlsx", context);
    publication.publish(file, "finance.xlsx", context, preview.previewFingerprint(), "test");
    assertThat(row("2026-10", "301990315").getUnitPrice())
        .isEqualByComparingTo(linked("100", "0.982"));
    assertThat(row("2026-10", "301990315").getSchemeCode()).isEqualTo("CU_100000");
  }

  @Test
  void calculationFailureRollsBackTheEntireFinancialPublicationAndEveryScrapResult()
      throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var target = row("2026-10", "301990315");
    var expression = "[Cu]/(95-[Cu])";
    imports.edit(
        target.getId(),
        "COMMERCIAL",
        new ScrapPriceImportService.Edit(
            target.getRowVersion(),
            new ScrapPriceRule(
                target.getMaterialCode(),
                target.getMaterialName(),
                "",
                "KG",
                null,
                null,
                "LINKED",
                null,
                expression,
                expression,
                Map.of("Cu", "AVG|1#CU"))),
        "test");
    String before = allPriceTables();
    assertThatThrownBy(() -> adjustCopper("2026-10", "95000")).hasMessageContaining("301990315");
    assertThat(allPriceTables()).isEqualTo(before);
    assertThat(publication.latest(context("2026-10")).getPublicationRevision()).isEqualTo(1);
  }

  @Test
  void invalidManualFormulaCannotReplaceAReadyRuleOrItsDependentMonths() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    var target = row("2026-10", "301990315");
    String before = snapshot();
    var rule =
        new ScrapPriceRule(
            target.getMaterialCode(),
            target.getMaterialName(),
            "",
            "KG",
            null,
            null,
            "LINKED",
            null,
            "[Cu]/0",
            "[Cu]/0",
            Map.of("Cu", "AVG|1#CU"));
    assertThatThrownBy(
            () ->
                imports.edit(
                    target.getId(),
                    "COMMERCIAL",
                    new ScrapPriceImportService.Edit(target.getRowVersion(), rule),
                    "test"))
        .hasMessageContaining("公式计算失败");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void aPublishedSchemeMissingOneRequiredFactorIsAnErrorNotAWaitingOrZeroPrice() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    String before = allPriceTables();
    byte[] missing;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(financeFile()));
        var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).removeRow(workbook.getSheetAt(0).getRow(4));
      workbook.write(output);
      missing = output.toByteArray();
    }
    assertThatThrownBy(() -> publish("2026-10", missing)).hasMessageContaining("AVG|A00");
    assertThat(allPriceTables()).isEqualTo(before);
  }

  @Test
  void olderConcurrentPreparationCannotOverwriteTheNewPublication() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              var input = call.getArgument(0, ScrapPriceCalculator.Input.class);
              if (Thread.currentThread().getName().equals("old-preparation")
                  && input.rule().priceType().equals("LINKED")) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("测试未释放旧计算");
              }
              return call.callRealMethod();
            })
        .when(calculator)
        .calculate(any());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var old =
          executor.submit(
              () -> {
                Thread.currentThread().setName("old-preparation");
                return generation.prepareMonth("COMMERCIAL", "2026-11", "old");
              });
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var published = executor.submit(() -> adjustCopper("2026-10", "95000"));
      try {
        assertThatThrownBy(() -> published.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      old.get(15, TimeUnit.SECONDS);
      published.get(15, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    for (String month : List.of("2026-10", "2026-11")) {
      assertThat(row(month, "301990315").getUnitPrice())
          .isEqualByComparingTo(linked("95", "0.982"));
      assertThat(row(month, "301990315").getFinanceRevision()).isEqualTo(2);
    }
  }

  @Test
  void supplierIdentityEditRefreshesCarriesWithoutLeavingOldCommonRows() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    var target = row("2026-10", "301990444");
    var rule =
        new ScrapPriceRule(
            target.getMaterialCode(),
            target.getMaterialName(),
            "",
            "KG",
            "S1",
            "供方",
            "FIXED",
            target.getFixedPrice(),
            null,
            null,
            Map.of());
    imports.edit(
        target.getId(),
        "COMMERCIAL",
        new ScrapPriceImportService.Edit(target.getRowVersion(), rule),
        "test");
    assertThat(rows("2026-11")).hasSize(12);
    assertThat(row("2026-11", "301990444").getSupplierCode()).isEqualTo("S1");
    assertThat(row("2026-11", "301990444").getSourceRuleId()).isEqualTo(target.getId());
  }

  private void importOriginal(String month) throws Exception {
    importFile(ScrapPriceWorkbookParserTest.original(), month);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void addingSupplierInLaterMonthDoesNotResurrectEarlierCommonIdentity(boolean explicitMonth)
      throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    if (explicitMonth) importOriginal("2026-11");
    else generation.prepareMonth("COMMERCIAL", "2026-11", "test");
    generation.prepareMonth("COMMERCIAL", "2026-12", "test");
    var original = row("2026-10", "301990444");
    var target = row("2026-11", "301990444");
    long id = target.getId();
    for (String supplier : new String[] {"S1", "S2", null}) {
      var replacement =
          new ScrapPriceRule(
              target.getMaterialCode(),
              target.getMaterialName(),
              "",
              "KG",
              supplier,
              supplier == null ? null : "供方",
              "FIXED",
              target.getFixedPrice(),
              null,
              null,
              Map.of());
      target =
          imports.edit(
              target.getId(),
              "COMMERCIAL",
              new ScrapPriceImportService.Edit(target.getRowVersion(), replacement),
              "test");
      assertThat(rows("2026-11")).hasSize(12);
      assertThat(rows("2026-12")).hasSize(12);
      assertThat(target.getId()).isEqualTo(id);
      assertThat(row("2026-12", "301990444").getSupplierCode()).isEqualTo(supplier);
      assertThat(row("2026-10", "301990444").getSupplierCode()).isNull();
      assertThat(row("2026-10", "301990444").getRowVersion()).isEqualTo(original.getRowVersion());
    }
    // 改金额、重新导入及重复准备不能丢掉替代关系，也不能复活 S1/S2。
    importOriginal("2026-11");
    generation.prepareMonth("COMMERCIAL", "2026-12", "test");
    assertThat(rows("2026-12")).hasSize(12);
  }

  @Test
  void explicitReimportOfFormerIdentityRemainsSeparateAndIsNotSilentlyMerged() throws Exception {
    publish("2026-10", financeFile());
    importOriginal("2026-10");
    importOriginal("2026-11");
    var target = row("2026-11", "301990444");
    var replacement =
        new ScrapPriceRule(
            target.getMaterialCode(),
            target.getMaterialName(),
            "",
            "KG",
            "S1",
            "供方",
            "FIXED",
            target.getFixedPrice(),
            null,
            null,
            Map.of());
    imports.edit(
        target.getId(),
        "COMMERCIAL",
        new ScrapPriceImportService.Edit(target.getRowVersion(), replacement),
        "test");
    importOriginal("2026-12");
    assertThat(rows("2026-12")).hasSize(13);
    assertThat(rows("2026-12").stream().filter(r -> r.getMaterialCode().equals("301990444")))
        .extracting(ScrapPrice::getSupplierCode)
        .containsExactlyInAnyOrder(null, "S1");
  }

  private void importFile(byte[] file, String month) {
    var preview = imports.preview(file, "original.xlsx", "COMMERCIAL", month);
    assertThat(preview.errors()).isEmpty();
    imports.confirm(
        file, "original.xlsx", "COMMERCIAL", month, preview.previewFingerprint(), "test");
  }

  private FinanceFactorImportContext context(String month) {
    return new FinanceFactorImportContext("COMMERCIAL", "210", month, null, "CU_90000");
  }

  private void publish(String month, byte[] file) {
    var preview = publication.preview(file, "finance.xlsx", context(month));
    assertThat(preview.workbook().problems()).isEmpty();
    publication.publish(
        file, "finance.xlsx", context(month), preview.previewFingerprint(), "finance");
  }

  private FinanceFactorPublicationService.Publication adjustCopper(String month, String perTon) {
    Long id =
        jdbc.queryForObject(
            """
SELECT p.id FROM lp_factor_monthly_price p JOIN lp_factor_identity i ON i.id=p.factor_identity_id
WHERE i.canonical_factor_key='AVG|1#CU' AND p.price_month=? AND p.scheme_code='CU_90000'
ORDER BY p.revision_no DESC LIMIT 1
""",
            Long.class,
            month);
    var preview = copper.preview(id, new BigDecimal(perTon), "废料测试", "COMMERCIAL");
    return copper.publish(
        id, new BigDecimal(perTon), "废料测试", "COMMERCIAL", preview.previewFingerprint(), "finance");
  }

  private byte[] financeFile(Object... changes) throws Exception {
    try (var input = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      if (input == null) throw new IllegalStateException("财务原文件缺失");
      byte[] bytes = input.readAllBytes();
      if (changes.length == 0) return bytes;
      try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
          var output = new ByteArrayOutputStream()) {
        for (int n = 0; n < changes.length; n += 2) {
          var address = new org.apache.poi.ss.util.CellReference((String) changes[n]);
          var cell = workbook.getSheetAt(0).getRow(address.getRow()).getCell(address.getCol());
          cell.setBlank();
          cell.setCellValue(((Number) changes[n + 1]).doubleValue());
        }
        workbook.write(output);
        return output.toByteArray();
      }
    }
  }

  private byte[] partialFixed(double amount) throws Exception {
    return ScrapPriceWorkbookParserTest.change(
        s -> {
          for (int i = 2; i <= 12; i++) s.removeRow(s.getRow(i));
          s.getRow(1).getCell(5).setCellValue(amount);
        });
  }

  private void editFixed(ScrapPrice target, String amount) {
    imports.edit(
        target.getId(),
        "COMMERCIAL",
        new ScrapPriceImportService.Edit(
            target.getRowVersion(),
            new ScrapPriceRule(
                target.getMaterialCode(),
                target.getMaterialName(),
                target.getSpecification(),
                "KG",
                target.getSupplierCode(),
                target.getSupplierName(),
                "FIXED",
                new BigDecimal(amount),
                null,
                null,
                Map.of())),
        "test");
  }

  private List<ScrapPrice> rows(String month) {
    return prices.selectList(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(ScrapPrice.class)
            .eq(ScrapPrice::getPriceMonth, month)
            .orderByAsc(ScrapPrice::getMaterialCode));
  }

  private ScrapPrice row(String month, String code) {
    return rows(month).stream()
        .filter(row -> row.getMaterialCode().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private BigDecimal linked(String factor, String coefficient) {
    return new BigDecimal(factor)
        .multiply(new BigDecimal(coefficient))
        .divide(new BigDecimal("1.13"), 18, RoundingMode.HALF_UP);
  }

  private void assertOriginalPrices(String month, String cu, String brass, String aluminum) {
    Map<String, String> fixed =
        Map.of(
            "301990444",
            "7.566",
            "301991071",
            "17.522",
            "301990044",
            "10.177",
            "301990230",
            "172.832",
            "301990168",
            "1.673",
            "301990752",
            "2.6283185840708");
    Map<String, String> coefficients =
        Map.of(
            "301990315",
            "0.982",
            "301990218",
            "1.039",
            "301990317",
            "0.965",
            "301990135",
            "0.9845",
            "301990114",
            "0.76",
            "301991027",
            "0.982");
    assertThat(rows(month)).hasSize(12);
    for (var row : rows(month)) {
      assertThat(row.getCalcStatus()).isEqualTo("READY");
      if (row.getPriceType().equals("FIXED"))
        assertThat(row.getUnitPrice()).isEqualByComparingTo(fixed.get(row.getMaterialCode()));
      else {
        String factor =
            row.getMaterialCode().equals("301990218")
                ? brass
                : row.getMaterialCode().equals("301990114") ? aluminum : cu;
        BigDecimal difference =
            row.getUnitPrice()
                .subtract(linked(factor, coefficients.get(row.getMaterialCode())))
                .abs();
        assertThat(difference)
            .as(row.getMaterialCode())
            .isLessThan(new BigDecimal("0.000000000001"));
      }
    }
  }

  private String snapshot() throws Exception {
    return json.writeValueAsString(jdbc.queryForList("SELECT * FROM lp_scrap_price ORDER BY id"));
  }

  private String fixedSnapshot() throws Exception {
    return json.writeValueAsString(
        jdbc.queryForList("SELECT * FROM lp_scrap_price WHERE price_type='FIXED' ORDER BY id"));
  }

  private String allPriceTables() throws Exception {
    var all = new java.util.TreeMap<String, Object>();
    for (String table :
        List.of(
            "lp_scrap_price",
            "lp_factor_identity",
            "lp_factor_upload_batch",
            "lp_factor_monthly_price",
            "lp_factor_row_ref",
            "lp_finance_effective_price"))
      all.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    return json.writeValueAsString(all);
  }
}
