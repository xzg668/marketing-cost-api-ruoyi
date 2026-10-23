package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 真实MySQL、生产Mapper与计算器：有效价存储、来源选择和事务回滚。 */
@Tag("integration")
class FinanceEffectivePriceIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withCommand("--max-allowed-packet=64M")
          .withDatabaseName("effective_test")
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
          "V330__scrap_price_monthly.sql", "V332__settlement_price_versions.sql", "V341__settlement_effective_price.sql");
  static SqlSessionTemplate sql;
  static DataSourceTransactionManager manager;
  static JdbcTemplate jdbc;
  static final SqlMeter SQL_METER = new SqlMeter();
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  FinanceEffectivePriceService service;
  FinanceEffectivePriceMapper results;
  PriceLinkedItemMapper linked;
  PriceRangeFactorRuleMapper ranges;
  FinanceFactorPublicationService publications;
  FinanceFactorPriceReader finance;
  TransactionTemplate transaction;
  OriginalLinkedRuleCalculator calculator;
  OriginalRangeRuleCalculator rangeCalculator;
  OriginalLinkedRuleSnapshot snapshots;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    for (String script : SCRIPTS) {
      MYSQL.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/" + script);
      var result =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot effective_test < \"$1\"",
              "migration",
              "/tmp/" + script);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    var data =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    manager = new DataSourceTransactionManager(data);
    jdbc = new JdbcTemplate(data);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.addInterceptor(SQL_METER);
    config.setEnvironment(
        new Environment("effective", new SpringManagedTransactionFactory(), data));
    for (var mapper :
        List.of(
            com.sanhua.marketingcost.mapper.ScrapPriceMapper.class,
            FactorIdentityMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorUploadBatchMapper.class,
            FactorRowRefMapper.class,
            PriceLinkedItemMapper.class,
            PriceRangeFactorRuleMapper.class,
            PriceRangeItemMapper.class,
            FinanceEffectivePriceMapper.class)) config.addMapper(mapper);
    sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void prepare() {
    for (String table :
        List.of(
            "lp_finance_effective_price",
            "lp_price_linked_item",
            "lp_price_range_factor_rule",
            "lp_factor_row_ref",
            "lp_factor_monthly_price",
            "lp_factor_upload_batch",
            "lp_factor_identity")) jdbc.update("DELETE FROM " + table);
    var identities = sql.getMapper(FactorIdentityMapper.class);
    var batches = sql.getMapper(FactorUploadBatchMapper.class);
    var prices = sql.getMapper(FactorMonthlyPriceMapper.class);
    var sources = sql.getMapper(FactorRowRefMapper.class);
    results = sql.getMapper(FinanceEffectivePriceMapper.class);
    linked = sql.getMapper(PriceLinkedItemMapper.class);
    ranges = sql.getMapper(PriceRangeFactorRuleMapper.class);
    finance = new FinanceFactorPriceReader(batches, prices, identities, sources);
    // Use the production transaction annotations: a caught missing publication must not
    // mark the import's shared transaction rollback-only.
    var financeProxy = new org.springframework.aop.framework.ProxyFactory(finance);
    financeProxy.setProxyTargetClass(true);
    financeProxy.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    finance = (FinanceFactorPriceReader) financeProxy.getProxy();
    var canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    publications =
        new FinanceFactorPublicationService(
            new FinanceFactorWorkbookParser(canonical),
            canonical,
            identities,
            batches,
            prices,
            sources,
            json,
            manager,
            mock(FinanceEffectivePriceService.class),
            com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(sql, manager, json),
            com.sanhua.marketingcost.service.settlement.SettlementEffectivePriceTestSupport.service(sql, json));
    snapshots = new OriginalLinkedRuleSnapshot(json);
    calculator =
        mock(
            OriginalLinkedRuleCalculator.class,
            withSettings()
                .mockMaker(org.mockito.MockMakers.INLINE)
                .defaultAnswer(CALLS_REAL_METHODS));
    rangeCalculator =
        mock(
            OriginalRangeRuleCalculator.class,
            withSettings()
                .mockMaker(org.mockito.MockMakers.INLINE)
                .defaultAnswer(CALLS_REAL_METHODS));
    service =
        new FinanceEffectivePriceService(
            results,
            linked,
            ranges,
            batches,
            finance,
            new OriginalRangeRuleSnapshot(json),
            calculator,
            rangeCalculator,
            json,
            new OriginalLinkedPublicationSource(
                new OriginalLinkedWorkbookParser(canonical), snapshots, batches, identities));
    transaction = new TransactionTemplate(manager);
  }

  @Test
  void onlyAbsentPublicationMayBeCaughtWithoutRollingBackCaller() throws Exception {
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null);
    transaction.executeWithoutResult(
        status -> {
          assertThatThrownBy(() -> finance.readDefault(context, "WAIT"))
              .isInstanceOf(FinanceFactorPriceReader.MissingPublicationException.class);
          assertThat(status.isRollbackOnly()).isFalse();
          linked("WAIT", "A", "2026-10", "[cu]+2");
        });
    assertThat(linked.selectCount(null)).isOne();
    publish("2026-10", "CU_90000");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      var invalid =
                          new FinanceFactorImportContext(
                              "COMMERCIAL", "210", "2026-10", null, "MISSING_SCHEME");
                      assertThatThrownBy(() -> finance.readDefault(invalid, "WAIT"))
                          .isInstanceOf(IllegalArgumentException.class)
                          .isNotInstanceOf(
                              FinanceFactorPriceReader.MissingPublicationException.class);
                      assertThat(status.isRollbackOnly()).isTrue();
                    }))
        .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
  }

  @Test
  void explicitInitializationIsIdempotentAndValidatesScope() throws Exception {
    publish("2026-10", "CU_90000");
    linked("INIT", "A", "2026-10", "[cu]+2");
    assertThat(transaction.execute(status -> service.initialize("COMMERCIAL", "210")).generated())
        .isOne();
    clearInvocations(calculator, rangeCalculator);
    var repeated = transaction.execute(status -> service.initialize("COMMERCIAL", "210"));
    assertThat(repeated).isEqualTo(new FinanceEffectivePriceService.Generation(0, 1, 0));
    verifyNoInteractions(calculator, rangeCalculator);
    assertThatThrownBy(() -> service.initialize("UNKNOWN", "210"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.initialize("COMMERCIAL", "999"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(results.selectCount(null)).isOne();
  }

  @Test
  @Tag("slow")
  void measureBatchGenerationAndReadReuse() throws Exception {
    var measurements = new java.util.ArrayList<Map<String, Object>>();
    for (int materials : List.of(100, 1000, 5000)) {
      for (int suppliers : List.of(1, 3)) {
        prepare();
        publish("2026-10", "CU_90000");
        long batch = sourceBatch("2026-10", "LINKED_ORIGINAL");
        transaction.executeWithoutResult(
            status -> {
              for (int i = 0; i < materials; i++)
                for (int s = 0; s < suppliers; s++)
                  linked("PERF" + i, "S" + s, "2026-10", "[cu]*0.1+" + s, batch);
            });
        var originals = linked.selectList(null);
        var base =
            finance.readDefault(
                new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null),
                "PERF0");
        for (int sample = 0; sample < 5; sample++) {
          // Disposable fixture: each sample measures the same cold generation size.
          jdbc.update("DELETE FROM lp_finance_effective_price");
          int count = materials * suppliers;
          measure(
              measurements,
              "coldGeneration",
              materials,
              suppliers,
              sample,
              () -> {
                assertThat(generate().generated()).isEqualTo(count);
              });
          assertThat(org.mockito.Mockito.mockingDetails(calculator).getInvocations())
              .hasSize(count);
          measure(
              measurements,
              "idempotentGeneration",
              materials,
              suppliers,
              sample,
              () -> {
                assertThat(generate())
                    .isEqualTo(new FinanceEffectivePriceService.Generation(0, count, 0));
              });
          verifyNoInteractions(calculator, rangeCalculator);
          measure(
              measurements,
              "batchRead",
              materials,
              suppliers,
              sample,
              () -> {
                assertThat(
                        service.read(
                            "LINKED",
                            originals.stream().map(PriceLinkedItem::getId).toList(),
                            base.financial()))
                    .hasSize(count);
              });
          verifyNoInteractions(calculator, rangeCalculator);
        }
      }
    }
    var report = java.nio.file.Path.of(System.getProperty("efp.performance.report"));
    java.nio.file.Files.createDirectories(report.getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), measurements);
  }

  @Test
  @Tag("slow")
  void measureImportAndPublicationTransactions() throws Exception {
    var report = new java.util.ArrayList<Map<String, Object>>();
    for (int sample = 0; sample < 5; sample++) {
      for (boolean originalsFirst : List.of(true, false)) {
        prepare();
        useSynchronousPublication();
        if (!originalsFirst) publish("2026-10", "CU_90000");
        var importer = linkedImporter(service);
        var input = originalCommand(linkedWorkbook());
        var preview = importer.preview(input);
        measure(
            report,
            originalsFirst ? "linkedImportWaiting" : "linkedImportReady",
            1,
            1,
            sample,
            () -> importer.confirm(confirmed(input, preview)));
        var rangeImporter = rangeImporter();
        byte[] bytes = rangeWorkbook();
        var rangeInput =
            new OriginalRangeImportService.Command(
                bytes, "range.xlsx", "COMMERCIAL", "2026-10", null, null);
        var rangePreview = rangeImporter.preview(rangeInput);
        measure(
            report,
            originalsFirst ? "rangeImportWaiting" : "rangeImportReady",
            1,
            1,
            sample,
            () ->
                rangeImporter.confirm(
                    new OriginalRangeImportService.Command(
                        bytes,
                        "range.xlsx",
                        "COMMERCIAL",
                        "2026-10",
                        rangePreview.fileSha256(),
                        rangePreview.previewFingerprint())));
        if (originalsFirst)
          measure(
              report, "firstBasePublication", 2, 1, sample, () -> publish("2026-10", "CU_90000"));
        assertThat(results.selectCount(null)).isEqualTo(2);
        if (!originalsFirst) continue;
        var adjustments =
            new FinanceFactorCuAdjustmentService(
                sql.getMapper(FactorMonthlyPriceMapper.class),
                sql.getMapper(FactorUploadBatchMapper.class),
                sql.getMapper(FactorIdentityMapper.class),
                sql.getMapper(FactorRowRefMapper.class),
                publications);
        var snapshot =
            finance.readDefault(
                new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "M1");
        var monthlyId = snapshot.financial().required("AVG|1#CU").monthlyPriceId();
        var copper = new BigDecimal("91000");
        var adjusted = adjustments.preview(monthlyId, copper, "performance", "COMMERCIAL");
        measure(
            report,
            "baseRevisionPublication",
            2,
            1,
            sample,
            () ->
                adjustments.publish(
                    monthlyId,
                    copper,
                    "performance",
                    "COMMERCIAL",
                    adjusted.previewFingerprint(),
                    "efp-test"));
        assertThat(results.selectCount(null)).isEqualTo(4);
      }
    }
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of(System.getProperty("efp.publication.report")).toFile(), report);
  }

  @FunctionalInterface
  interface CheckedAction {
    void run() throws Exception;
  }

  void measure(
      List<Map<String, Object>> report,
      String operation,
      int materials,
      int suppliers,
      int sample,
      CheckedAction action)
      throws Exception {
    clearInvocations(calculator, rangeCalculator);
    SQL_METER.reset();
    long start = System.nanoTime();
    action.run();
    report.add(
        Map.of(
            "operation",
            operation,
            "materials",
            materials,
            "suppliers",
            suppliers,
            "sample",
            sample,
            "milliseconds",
            (System.nanoTime() - start) / 1_000_000.0,
            "sqlReads",
            SQL_METER.reads,
            "sqlWrites",
            SQL_METER.writes,
            "calculations",
            mockingDetails(calculator).getInvocations().size()
                + mockingDetails(rangeCalculator).getInvocations().size()));
  }

  @org.apache.ibatis.plugin.Intercepts({
    @org.apache.ibatis.plugin.Signature(
        type = org.apache.ibatis.executor.Executor.class,
        method = "query",
        args = {
          org.apache.ibatis.mapping.MappedStatement.class,
          Object.class,
          org.apache.ibatis.session.RowBounds.class,
          org.apache.ibatis.session.ResultHandler.class
        }),
    @org.apache.ibatis.plugin.Signature(
        type = org.apache.ibatis.executor.Executor.class,
        method = "update",
        args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class})
  })
  static class SqlMeter implements org.apache.ibatis.plugin.Interceptor {
    int reads;
    int writes;

    void reset() {
      reads = 0;
      writes = 0;
    }

    public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
      if (invocation.getMethod().getName().equals("query")) reads++;
      else writes++;
      return invocation.proceed();
    }
  }

  @Test
  void storesEverySupplierAndRangeWithFinancialEvidenceThenReadsWithoutCalculation()
      throws Exception {
    publish("2026-10", "CU_90000");
    var a = linked("A", "S1", "2026-10", "[cu]*0.1+2");
    var b = linked("A", "S2", "2026-10", "[cu]*0.1+3");
    var r = range("R", "2026-10", "3.123456789012345678");
    assertThat(generate().generated()).isEqualTo(3);
    var base =
        finance.readDefault(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "A");
    var saved = service.read("LINKED", List.of(a.getId(), b.getId()), base.financial());
    assertThat(saved).hasSize(2);
    assertThat(saved.get(a.getId()).getUnitPrice()).isEqualByComparingTo("11");
    assertThat(saved.get(b.getId()).getUnitPrice()).isEqualByComparingTo("12");
    var ranged = service.require(r, base.financial());
    assertThat(ranged.getUnitPrice()).isEqualByComparingTo("3.123456789012345678");
    assertThat(service.calculation(ranged).range().sourceBand().address().cell()).isEqualTo("D2");
    clearInvocations(calculator, rangeCalculator);
    assertThat(generate()).isEqualTo(new FinanceEffectivePriceService.Generation(0, 3, 0));
    service.require(a, base.financial());
    service.calculation(saved.get(a.getId()));
    verifyNoInteractions(calculator, rangeCalculator);
    assertThat(results.selectCount(null)).isEqualTo(3);
  }

  @Test
  void financialRevisionAppendsPricesWithoutChangingOriginals() throws Exception {
    publish("2026-10", "CU_90000");
    var a = linked("A", "S1", "2026-10", "[cu]*0.1+2");
    generate();
    String before = linked.selectById(a.getId()).getSourceInputSnapshotJson();
    publish("2026-10", "CU_100000");
    assertThat(generate().generated()).isOne();
    assertThat(results.selectList(null))
        .extracting(FinanceEffectivePrice::getUnitPrice)
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactlyInAnyOrder(new BigDecimal("11"), new BigDecimal("12"));
    assertThat(linked.selectById(a.getId()).getSourceInputSnapshotJson()).isEqualTo(before);
  }

  @Test
  void listsAndDetailsReadSavedResultsAndExposeWaitingWithoutWritesOrPublicCalculation()
      throws Exception {
    var original = linked("MAT", "A", "2026-10", "[cu]*0.1+1");
    var range = range("RANGE", "2026-10", "12");
    var views =
        new OriginalPriceTrialService(
            linked,
            ranges,
            new OriginalRangeRuleSnapshot(json),
            calculator,
            rangeCalculator,
            finance,
            sql.getMapper(FactorUploadBatchMapper.class),
            service);
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "efp-test", "", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(auth);
    try {
      var request =
          new OriginalPriceTrialService.BatchRequest(List.of(original.getId()), "2026-10");
      assertThat(views.effective("LINKED", request).getFirst().status())
          .isEqualTo("WAIT_FINANCE_BASE");
      assertThat(results.selectCount(null)).isZero();
      assertThat(
              views
                  .effective(
                      "LINKED",
                      new OriginalPriceTrialService.BatchRequest(
                          List.of(original.getId()), "2026-09"))
                  .getFirst()
                  .status())
          .isEqualTo("NOT_APPLICABLE");
      publish("2026-10", "CU_90000");
      generate();
      String before = json.writeValueAsString(results.selectList(null));
      clearInvocations(calculator, rangeCalculator);
      var linkedView = views.effective("LINKED", request).getFirst();
      assertThat(linkedView.status()).isEqualTo("READY");
      assertThat(linkedView.unitPrice()).isEqualByComparingTo("10");
      assertThat(
              views
                  .effective(
                      "RANGE",
                      new OriginalPriceTrialService.BatchRequest(List.of(range.getId()), "2026-10"))
                  .getFirst()
                  .unitPrice())
          .isEqualByComparingTo("12");
      var detail = new OriginalPriceTrialService.Request("2026-10", null);
      assertThat(views.linked(original.getId(), detail).summary().financePrice())
          .isEqualByComparingTo("10");
      assertThat(views.range(range.getId(), detail).summary().financePrice())
          .isEqualByComparingTo("12");
      assertThat(
              views
                  .linked(
                      original.getId(),
                      new OriginalPriceTrialService.Request("2026-10", new BigDecimal("90000")))
                  .summary()
                  .difference())
          .isZero();
      verifyNoInteractions(calculator, rangeCalculator);
      assertThat(json.writeValueAsString(results.selectList(null))).isEqualTo(before);
      results.deleteById(linkedView.effectivePriceId());
      var missing = views.effective("LINKED", request).getFirst();
      assertThat(missing.status()).isEqualTo("ERROR");
      assertThat(missing.unitPrice()).isNull();
      assertThatThrownBy(() -> views.linked(original.getId(), detail)).hasMessageContaining("未就绪");
      verifyNoInteractions(calculator, rangeCalculator);
      auth.setDetails(Map.of("businessUnitType", "HOUSEHOLD"));
      assertThatThrownBy(() -> views.effective("LINKED", request)).hasMessageContaining("其他业务单元");
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"LINKED", "RANGE"})
  void selectsPersistedPricesAndRatioChangesNeverCalculateOrRewrite(String type) throws Exception {
    publish("2026-10", "CU_90000");
    List<FinanceEffectivePriceService.SourceRule> rules;
    if (type.equals("LINKED")) {
      rules =
          List.of(
              FinanceEffectivePriceService.SourceRule.linked(
                  linked("MAT", "A", "2026-10", "[cu]*0.1+1")),
              FinanceEffectivePriceService.SourceRule.linked(
                  linked("MAT", "B", "2026-10", "[cu]*0.1+3")));
    } else {
      rules =
          List.of(
              FinanceEffectivePriceService.SourceRule.range(range("MAT", "A", "2026-10", "10")),
              FinanceEffectivePriceService.SourceRule.range(range("MAT", "B", "2026-10", "12")));
    }
    generate();
    String before = json.writeValueAsString(results.selectList(null));
    clearInvocations(calculator, rangeCalculator);
    var selection =
        new FinanceEffectivePriceSelectionService(
            service,
            new SupplierPriceSelectionService(
                mock(SupplierSupplyRatioMapper.class), mock(SupplierPriceDecisionMapper.class)));
    var financial =
        finance
            .readDefault(
                new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "MAT")
            .financial();
    var rows = selection.read(rules, financial);
    var scope = FinanceEffectivePriceSelectionTest.scope(type);
    var a = FinanceEffectivePriceSelectionTest.ratio("A", "0.7");
    var b = FinanceEffectivePriceSelectionTest.ratio("B", "0.3");
    assertThat(
            selection.select(scope, rows.values(), null, List.of(a, b)).price().getSupplierCode())
        .isEqualTo("A");
    a.setSupplyRatio(new BigDecimal("0.5"));
    b.setSupplyRatio(new BigDecimal("0.5"));
    assertThat(
            selection.select(scope, rows.values(), null, List.of(a, b)).price().getSupplierCode())
        .isEqualTo("B");
    assertThat(json.writeValueAsString(results.selectList(null))).isEqualTo(before);
    results.deleteById(rows.get(rules.getLast().id()).getId());
    assertThatThrownBy(() -> selection.read(rules, financial)).hasMessageContaining("未就绪");
    verifyNoInteractions(calculator, rangeCalculator);
  }

  @Test
  void originalsCanWaitAndLaterFinancialMonthCreatesApplicableResult() throws Exception {
    var a = linked("A", "S1", "2026-09", "[cu]*0.1+2");
    assertThat(generate()).isEqualTo(new FinanceEffectivePriceService.Generation(0, 0, 1));
    publish("2026-10", "CU_90000");
    assertThat(generate().generated()).isOne();
    var row = results.selectList(null).getFirst();
    assertThat(row.getOriginalMonth()).isEqualTo("2026-09");
    assertThat(row.getFinanceMonth()).isEqualTo("2026-10");
    assertThat(row.getEffectiveFromMonth()).isEqualTo("2026-10");
    var future =
        finance.readDefault(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, null), "A");
    assertThat(service.require(a, future.financial()).getId()).isEqualTo(row.getId());
    assertThat(results.selectCount(null)).isOne();
  }

  @Test
  void quoteLinkedUsesFinancialWinnerForBothSidesAndMissingSavedCandidateBlocks() throws Exception {
    publish("2026-10", "CU_90000");
    linked("MAT", "A", "2026-10", "[cu]*0.2-8");
    var b = linked("MAT", "B", "2026-10", "[cu]*0.1+3");
    generate();
    var ratioMapper = mock(SupplierSupplyRatioMapper.class);
    var decisions = mock(SupplierPriceDecisionMapper.class);
    var aRatio = FinanceEffectivePriceSelectionTest.ratio("A", "0.5");
    aRatio.setMaterialCode("MAT");
    var bRatio = FinanceEffectivePriceSelectionTest.ratio("B", "0.5");
    bRatio.setMaterialCode("MAT");
    when(ratioMapper.selectList(any())).thenReturn(List.of(aRatio, bRatio));
    var supplierPrices = new SupplierPriceSelectionService(ratioMapper, decisions);
    var oaMapper = mock(OaFormMapper.class);
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("120000"));
    when(oaMapper.selectOne(any())).thenReturn(form);
    var months = mock(MaterialPriceMonthSelector.class);
    when(months.select(any(), any(), any()))
        .thenReturn(new PriceMonthSelection("2026-10", "2026-10"));
    var calcRows = mock(PriceLinkedCalcItemMapper.class);
    var preview = mock(com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService.class);
    var runtime = new OriginalLinkedPriceRuntime(snapshots, calculator, finance, json);
    var ensure =
        new LinkedPriceEnsureServiceImpl(
            calcRows,
            linked,
            mock(BomCostingRowMapper.class),
            oaMapper,
            new PriceLinkedCalcServiceImpl(calcRows, linked, oaMapper, json, preview),
            new SupplierPreferredPriceSelector(
                mock(com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService.class)),
            months,
            runtime,
            service,
            new FinanceEffectivePriceSelectionService(service, supplierPrices),
            supplierPrices);
    var request =
        com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest.quote(
            "OA-EFP", "COMMERCIAL", "2026-10", java.util.Set.of("MAT"));
    request.setPriceOrgCode("210");
    request.setPriceAsOfTime(java.time.LocalDateTime.of(2026, 10, 14, 12, 0));
    request.setSupplierPriceReviewContext(new SupplierPriceReviewContext(17L));
    request.setPriceScenarioType(
        com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("90")));
    clearInvocations(calculator, rangeCalculator);
    String before = json.writeValueAsString(results.selectList(null));
    var p0 = ensure.calculate(request).getFirst();
    assertThat(p0.getPartUnitPrice()).as(p0.getCalcMessage()).isEqualByComparingTo("12");
    assertThat(p0.getSupplierCode()).isEqualTo("B");
    ensure.calculate(request);
    verifyNoInteractions(calculator, rangeCalculator, preview, calcRows);
    request.setPriceScenarioType(com.sanhua.marketingcost.enums.QuotePriceScenarioType.OA_LOCKED);
    var p1 = ensure.calculate(request).getFirst();
    assertThat(p1.getPartUnitPrice()).as(p1.getCalcMessage()).isEqualByComparingTo("15");
    assertThat(p1.getSupplierCode()).isEqualTo("B");
    assertThat(p1.getSourcePriceRecordId()).isEqualTo(p0.getSourcePriceRecordId());
    assertThat(json.readTree(p1.getInputSnapshotJson()).path("effectivePriceId"))
        .isEqualTo(json.readTree(p0.getInputSnapshotJson()).path("effectivePriceId"));
    verify(calculator, times(1)).calculate(any(), any());
    form.setCopperPrice(new BigDecimal("90000"));
    assertThat(ensure.calculate(request).getFirst().getPartUnitPrice()).isEqualByComparingTo("12");
    verify(calculator, times(1)).calculate(any(), any());
    when(ratioMapper.selectList(any())).thenReturn(List.of());
    assertThat(ensure.calculate(request).getFirst().getFailureCode())
        .isEqualTo(SupplierPriceSelectionService.REVIEW_REQUIRED);
    var review = request.getSupplierPriceReviewContext().reviews().getFirst();
    assertThat(review.scope().oaFormItemId()).isEqualTo(17L);
    var approved = new SupplierPriceDecision();
    approved.setDecision("FALLBACK_HIGH");
    approved.setFingerprint(review.fingerprint());
    when(decisions.latest(any())).thenReturn(approved);
    var fallback = ensure.calculate(request).getFirst();
    assertThat(fallback.getPartUnitPrice()).isEqualByComparingTo("12");
    assertThat(fallback.getWarningMessage()).contains("最高价");
    assertThat(json.writeValueAsString(results.selectList(null))).isEqualTo(before);
    results.deleteById(
        results.selectList(null).stream()
            .filter(row -> row.getOriginalId().equals(b.getId()))
            .findFirst()
            .orElseThrow()
            .getId());
    assertThat(ensure.calculate(request).getFirst().getFailureCode())
        .isEqualTo("LINKED_RULE_ERROR");
    verify(calculator, times(1)).calculate(any(), any());
  }

  @Test
  void quoteRangeReadsSavedCandidatesAndNeverCalculatesFinancialPrices() throws Exception {
    publish("2026-10", "CU_90000");
    var a = range("MAT", "A", "2026-10", "10");
    var b = range("MAT", "B", "2026-10", "12");
    generate();
    var ratioMapper = mock(SupplierSupplyRatioMapper.class);
    var aRatio = FinanceEffectivePriceSelectionTest.ratio("A", "0.5");
    aRatio.setMaterialCode("MAT");
    var bRatio = FinanceEffectivePriceSelectionTest.ratio("B", "0.5");
    bRatio.setMaterialCode("MAT");
    when(ratioMapper.selectList(any())).thenReturn(List.of(aRatio, bRatio));
    var suppliers =
        new SupplierPriceSelectionService(ratioMapper, mock(SupplierPriceDecisionMapper.class));
    var bands = mock(PriceRangeItemMapper.class);
    var rows =
        java.util.stream.Stream.of(a, b)
            .map(
                rule -> {
                  var row = new PriceRangeItem();
                  row.setId(rule.getId());
                  row.setFactorRuleId(rule.getId());
                  row.setSourceSheetName("区间");
                  row.setSourceCellRef("D2");
                  return row;
                })
            .toList();
    when(bands.selectList(any())).thenReturn(rows);
    var months = mock(MaterialPriceMonthSelector.class);
    when(months.select(any(), any(), any()))
        .thenReturn(new PriceMonthSelection("2026-10", "2026-10"));
    var oa = mock(OaFormMapper.class);
    var resolver =
        new RangePriceResolver(
            bands,
            ranges,
            oa,
            new SupplierPreferredPriceSelector(
                mock(com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService.class)),
            months,
            new OriginalRangePriceRuntime(
                new OriginalRangeRuleSnapshot(json), rangeCalculator, finance, bands, oa, json),
            service,
            new FinanceEffectivePriceSelectionService(service, suppliers),
            suppliers);
    var run = new com.sanhua.marketingcost.dto.CostRunContext();
    run.setBusinessUnitType("COMMERCIAL");
    run.setPriceOrgCode("210");
    run.setPricingMonth("2026-10");
    run.setPriceAsOfTime(java.time.LocalDateTime.of(2026, 10, 14, 12, 0));
    run.setPriceScenarioType("FINANCE_QUOTE_BASE");
    run.setSupplierPriceReviewContext(new SupplierPriceReviewContext(17L));
    var item = new com.sanhua.marketingcost.dto.CostRunPartItemDto();
    item.setPartCode("MAT");
    var request = new PriceResolveRequest(item, null, run);
    clearInvocations(calculator, rangeCalculator);
    var price = resolver.resolveBatch("OA-EFP", List.of(request)).get(request);
    assertThat(price.unitPrice()).as(price.remark()).isEqualByComparingTo("12");
    assertThat(price.evidence().supplierCode()).isEqualTo("B");
    aRatio.setSupplyRatio(new BigDecimal("0.7"));
    bRatio.setSupplyRatio(new BigDecimal("0.3"));
    assertThat(resolver.resolveBatch("OA-EFP", List.of(request)).get(request).unitPrice())
        .isEqualByComparingTo("10");
    when(ratioMapper.selectList(any())).thenReturn(List.of());
    assertThat(resolver.resolveBatch("OA-EFP", List.of(request)).get(request).failureCode())
        .isEqualTo(SupplierPriceSelectionService.REVIEW_REQUIRED);
    assertThat(run.getSupplierPriceReviewContext().reviews().getFirst().scope().oaFormItemId())
        .isEqualTo(17L);
    verifyNoInteractions(calculator, rangeCalculator);
  }

  @Test
  void newMonthRangeBoundaryPreventsGeneratingOldLinkedMaterialForLaterFinance() throws Exception {
    publish("2026-09", "CU_90000");
    linked("OLD", "S1", "2026-09", "[cu]*0.1+2");
    range("NEW", "2026-10", "3");
    publish("2026-11", "CU_100000");
    assertThat(generate().generated()).isEqualTo(3);
    assertThat(results.selectList(null).stream().filter(row -> row.getMaterialCode().equals("OLD")))
        .allMatch(row -> row.getFinanceMonth().equals("2026-09"));
  }

  @Test
  void failedCalculationRollsBackNewSourcesAndAnyAlreadyWrittenBatch() throws Exception {
    publish("2026-10", "CU_90000");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      for (int i = 0; i < 201; i++) linked("M" + i, "S1", "2026-10", "[cu]+2");
                      linked("BROKEN", "S1", "2026-10", "[unknown]");
                      service.generateScope("COMMERCIAL", "210");
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("财务有效价生成失败");
    assertThat(results.selectCount(null)).isZero();
    assertThat(linked.selectCount(null)).isZero();
  }

  @Test
  void queryDoesNotRepairMissingOrMutatedResults() throws Exception {
    publish("2026-10", "CU_90000");
    var a = linked("A", "S1", "2026-10", "[cu]+2");
    var base =
        finance.readDefault(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "A");
    assertThatThrownBy(() -> service.require(a, base.financial())).hasMessageContaining("未就绪");
    verifyNoInteractions(calculator);
    generate();
    a.setSourceInputSnapshotJson(a.getSourceInputSnapshotJson() + " ");
    assertThat(service.require(a, base.financial())).isNotNull();
    a.setFormulaExpr("[cu]+3");
    assertThatThrownBy(() -> service.require(a, base.financial())).hasMessageContaining("来源已变化");
    assertThat(results.selectCount(null)).isOne();
  }

  @Test
  void databaseRejectsDuplicateInputsAndGenerationRequiresTransaction() throws Exception {
    publish("2026-10", "CU_90000");
    linked("A", "S1", "2026-10", "[cu]+2");
    assertThatThrownBy(() -> service.generateScope("COMMERCIAL", "210"))
        .hasMessageContaining("发布事务");
    generate();
    var saved = results.selectList(null).getFirst();
    saved.setId(null);
    assertThatThrownBy(() -> results.insert(saved))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(results.selectCount(null)).isOne();
  }

  @Test
  void queryCannotCrossBusinessUnitOrSchemeAndMigrationIsRepeatable() throws Exception {
    publish("2026-10", "CU_90000");
    var a = linked("A", "S1", "2026-10", "[cu]+2");
    generate();
    var base =
        finance.readDefault(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "A");
    var actual = base.financial();
    var other =
        new FactorPriceSnapshot(
            new FactorPriceScope(
                "HOUSEHOLD",
                "210",
                "2026-10",
                actual.scope().schemeCode(),
                actual.scope().condition()),
            actual.batchId(),
            actual.revision(),
            actual.fileName(),
            actual.fileSha256(),
            actual.contentHash(),
            actual.factors(),
            actual.schemeCodes(),
            actual.publishedBy(),
            actual.publishedAt(),
            actual.publicationNote());
    assertThat(service.read("LINKED", List.of(a.getId()), other)).isEmpty();
    var otherScheme =
        finance.read(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_100000"),
            FactorPriceScope.Condition.GENERAL,
            null);
    assertThat(service.read("LINKED", List.of(a.getId()), otherScheme)).isEmpty();
    var before = results.selectList(null);
    var execution =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot effective_test <"
                + " /tmp/V325__finance_effective_price.sql");
    assertThat(execution.getExitCode()).as(execution.getStderr()).isZero();
    assertThat(results.selectList(null)).isEqualTo(before);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void bothImportOrdersGenerateIdenticalLinkedAndRangePricesBeforeReturning(boolean originalsFirst)
      throws Exception {
    useSynchronousPublication();
    if (!originalsFirst) publish("2026-10", "CU_90000");
    var importer = linkedImporter(service);
    var input = originalCommand(linkedWorkbook());
    var preview = importer.preview(input);
    assertThat(preview.isCanConfirm()).as(preview.getErrors().toString()).isTrue();
    var linkedResult = importer.confirm(confirmed(input, preview));
    assertThat(linkedResult.getEffectivePriceStatus())
        .isEqualTo(originalsFirst ? "WAIT_FINANCE_BASE" : "READY");
    var rangeImporter = rangeImporter();
    byte[] bytes = rangeWorkbook();
    var rangeInput =
        new OriginalRangeImportService.Command(
            bytes, "区间.xlsx", "COMMERCIAL", "2026-10", null, null);
    var rangePreview = rangeImporter.preview(rangeInput);
    assertThat(rangePreview.canConfirm()).as(rangePreview.errors().toString()).isTrue();
    rangeImporter.confirm(
        new OriginalRangeImportService.Command(
            bytes,
            "区间.xlsx",
            "COMMERCIAL",
            "2026-10",
            rangePreview.fileSha256(),
            rangePreview.previewFingerprint()));
    if (originalsFirst) {
      assertThat(results.selectCount(null)).isZero();
      publish("2026-10", "CU_90000");
    }
    var rows = results.selectList(null);
    assertThat(rows).hasSize(2);
    var linkedPrice =
        rows.stream().filter(row -> row.getPriceType().equals("LINKED")).findFirst().orElseThrow();
    var rangePrice =
        rows.stream().filter(row -> row.getPriceType().equals("RANGE")).findFirst().orElseThrow();
    assertThat(rangePrice.getUnitPrice()).isEqualByComparingTo("3");
    var basis =
        finance.readDefault(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null), "M1");
    BigDecimal expected =
        new BigDecimal("9").add(basis.requiredPrice("AVG|1#ZN", FactorPriceUnit.CNY_KG, true));
    assertThat(linkedPrice.getUnitPrice()).isEqualByComparingTo(expected);
    assertThat(service.calculation(linkedPrice).linkedRule().factors().values())
        .anyMatch(factor -> factor.canonicalKey().equals("AVG|1#ZN"));
    var replayPreview = importer.preview(input);
    var replay = importer.confirm(confirmed(input, replayPreview));
    assertThat(replay.getLinkedVersionCreatedCount()).isZero();
    assertThat(replay.getLinkedUnchangedSkippedCount()).isOne();
    assertThat(replay.getEffectivePriceGeneratedCount()).isZero();
    assertThat(linked.selectCount(null)).isOne();
    // 先原稿后基价时，客户报价也必须使用发布时重新绑定的规则，不能回到早期编译常量。
    var runtime = new OriginalLinkedPriceRuntime(snapshots, calculator, finance, json);
    var original = linked.selectList(null).getFirst();
    var saved = service.calculation(linkedPrice);
    clearInvocations(calculator, rangeCalculator);
    assertThat(
            runtime.previewSaved(original, "2026-10", null, null, linkedPrice, saved).getResult())
        .isEqualByComparingTo(expected);
    verifyNoInteractions(calculator, rangeCalculator);
    assertThat(
            runtime
                .previewSaved(
                    original, "2026-10", new BigDecimal("100000"), null, linkedPrice, saved)
                .getResult())
        .isEqualByComparingTo(expected.add(BigDecimal.ONE));
    verify(calculator, times(1)).calculate(any(), any());
  }

  @Test
  void changedFinanceDoesNotTurnSameOriginalFileIntoANewRuleVersion() throws Exception {
    useSynchronousPublication();
    publish("2026-10", "CU_90000");
    var importer = linkedImporter(service);
    var input = originalCommand(linkedWorkbook());
    importer.confirm(confirmed(input, importer.preview(input)));
    var before = linked.selectList(null).getFirst();
    publish("2026-10", "CU_100000");
    var response = importer.confirm(confirmed(input, importer.preview(input)));
    assertThat(response.getLinkedVersionCreatedCount()).isZero();
    assertThat(linked.selectList(null)).hasSize(1);
    assertThat(linked.selectById(before.getId()).getSourceInputSnapshotJson())
        .isEqualTo(before.getSourceInputSnapshotJson());
    assertThat(results.selectCount(null)).isEqualTo(2);
  }

  @Test
  void requiredCalculationFailureRollsBackFinancialPublication() throws Exception {
    useSynchronousPublication();
    publish("2026-10", "CU_90000");
    var importer = rangeImporter();
    byte[] bytes = rangeWorkbook();
    var input =
        new OriginalRangeImportService.Command(
            bytes, "区间.xlsx", "COMMERCIAL", "2026-10", null, null);
    var preview = importer.preview(input);
    importer.confirm(
        new OriginalRangeImportService.Command(
            bytes,
            "区间.xlsx",
            "COMMERCIAL",
            "2026-10",
            preview.fileSha256(),
            preview.previewFingerprint()));
    var before = results.selectList(null);
    assertThatThrownBy(() -> publish("2026-10", "CU_120000"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("财务有效价生成失败");
    assertThat(
            publications
                .latest(new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null))
                .getDefaultSchemeCode())
        .isEqualTo("CU_90000");
    assertThat(results.selectList(null)).isEqualTo(before);
  }

  @Test
  void generationFailureRollsBackOriginalRowsAndWorkbookBatch() throws Exception {
    publish("2026-10", "CU_90000");
    var failing = mock(FinanceEffectivePriceService.class);
    when(failing.generateScope("COMMERCIAL", "210"))
        .thenThrow(new IllegalArgumentException("有效价写入失败"));
    var importer = linkedImporter(failing);
    var input = originalCommand(linkedWorkbook());
    var preview = importer.preview(input);
    long batchesBefore = sql.getMapper(FactorUploadBatchMapper.class).selectCount(null);
    assertThatThrownBy(() -> importer.confirm(confirmed(input, preview)))
        .hasMessageContaining("有效价写入失败");
    assertThat(linked.selectCount(null)).isZero();
    assertThat(sql.getMapper(FactorUploadBatchMapper.class).selectCount(null))
        .isEqualTo(batchesBefore);
    assertThat(results.selectCount(null)).isZero();
  }

  @Test
  void newOriginalCannotSilentlyLoseWorkbookAndFallbackToEarlyBindings() throws Exception {
    useSynchronousPublication();
    var importer = linkedImporter(service);
    var input = originalCommand(linkedWorkbook());
    importer.confirm(confirmed(input, importer.preview(input)));
    jdbc.update(
        "UPDATE lp_factor_upload_batch SET source_workbook=NULL WHERE"
            + " import_type='LINKED_ORIGINAL'");
    assertThatThrownBy(() -> publish("2026-10", "CU_90000")).hasMessageContaining("原稿工作簿来源缺失");
    assertThat(
            publications.latest(
                new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null)))
        .isNull();
    assertThat(results.selectCount(null)).isZero();
  }

  void useSynchronousPublication() {
    var canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    publications =
        new FinanceFactorPublicationService(
            new FinanceFactorWorkbookParser(canonical),
            canonical,
            sql.getMapper(FactorIdentityMapper.class),
            sql.getMapper(FactorUploadBatchMapper.class),
            sql.getMapper(FactorMonthlyPriceMapper.class),
            sql.getMapper(FactorRowRefMapper.class),
            json,
            manager,
            service,
            com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(sql, manager, json),
            com.sanhua.marketingcost.service.settlement.SettlementEffectivePriceTestSupport.service(sql, json));
  }

  OriginalLinkedImportService linkedImporter(FinanceEffectivePriceService generation) {
    var parser =
        new OriginalLinkedWorkbookParser(
            new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    return new OriginalLinkedImportService(
        parser,
        new OriginalLinkedRuleCalculator(),
        snapshots,
        finance,
        sql.getMapper(FactorIdentityMapper.class),
        sql.getMapper(FactorUploadBatchMapper.class),
        linked,
        json,
        manager,
        generation);
  }

  OriginalRangeImportService rangeImporter() {
    return new OriginalRangeImportService(
        new OriginalRangeWorkbookParser(),
        new OriginalRangeRuleCalculator(),
        new OriginalRangeRuleSnapshot(json),
        finance,
        sql.getMapper(FactorIdentityMapper.class),
        sql.getMapper(FactorUploadBatchMapper.class),
        ranges,
        sql.getMapper(PriceRangeItemMapper.class),
        json,
        manager,
        service);
  }

  com.sanhua.marketingcost.dto.PriceLinkedImportCommand originalCommand(byte[] bytes) {
    return new com.sanhua.marketingcost.dto.PriceLinkedImportCommand(
        bytes, "联动.xlsx", "2026-10", "COMMERCIAL", false, null, null, null, null);
  }

  com.sanhua.marketingcost.dto.PriceLinkedImportCommand confirmed(
      com.sanhua.marketingcost.dto.PriceLinkedImportCommand input,
      com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse preview) {
    return new com.sanhua.marketingcost.dto.PriceLinkedImportCommand(
            input.getFileBytes(),
            input.getSourceFileName(),
            input.getPricingMonth(),
            input.getBusinessUnitType(),
            false,
            null,
            null,
            null,
            preview.getFileSha256())
        .withPreviewFingerprint(preview.getPreviewFingerprint());
  }

  byte[] linkedWorkbook() throws Exception {
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var out = new java.io.ByteArrayOutputStream()) {
      var factors = book.createSheet("因素");
      var header = factors.createRow(0);
      String[] names = {"序号", "名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      for (int i = 1; i <= 2; i++) {
        var row = factors.createRow(i);
        row.createCell(0).setCellValue(i);
        row.createCell(1).setCellValue(i == 1 ? "铜" : "锌");
        row.createCell(2).setCellValue(i == 1 ? "Cu" : "1#Zn");
        row.createCell(3).setCellValue("平均价");
        row.createCell(4).setCellValue(i == 1 ? 80 : 25);
        row.createCell(5).setCellValue("公斤");
      }
      var main = book.createSheet("ImportData");
      var h = main.createRow(0);
      String[] headers = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
      var row = main.createRow(1);
      String[] values = {"210", "M1", "供应商", "S1", "SPEC", "只"};
      for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      row.createCell(6).setCellFormula("因素!E2*0.1+因素!E3");
      row.createCell(7).setCellValue(false);
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(out);
      return out.toByteArray();
    }
  }

  byte[] rangeWorkbook() throws Exception {
    try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var out = new java.io.ByteArrayOutputStream()) {
      var main = book.createSheet("ImportData");
      var h = main.createRow(0);
      String[] headers = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);
      var row = main.createRow(1);
      String[] values = {"210", "R1", "测试供应商有限公司", "S1", "SPEC", "只"};
      for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      row.createCell(6).setCellValue(99);
      row.createCell(7).setCellValue(false);
      var matrix = book.createSheet("矩阵");
      matrix.createRow(0).createCell(0).setCellValue("铜价区间表");
      var mh = matrix.createRow(1);
      mh.createCell(0).setCellValue("U9代码");
      mh.createCell(1).setCellValue("图号");
      mh.createCell(2).setCellValue("供应商名称");
      mh.createCell(3).setCellValue("90000-94999");
      var tax = matrix.createRow(2);
      tax.createCell(3).setCellValue("不含税");
      tax.createCell(4).setCellValue("含13%税价");
      var data = matrix.createRow(3);
      data.createCell(0).setCellValue("R1");
      data.createCell(1).setCellValue("SPEC");
      data.createCell(2).setCellValue("测试供应商");
      data.createCell(3).setCellValue(3);
      data.createCell(4).setCellValue(3.39);
      book.write(out);
      return out.toByteArray();
    }
  }

  FinanceEffectivePriceService.Generation generate() {
    return transaction.execute(status -> service.generateScope("COMMERCIAL", "210"));
  }

  void publish(String month, String scheme) throws Exception {
    byte[] file;
    try (var stream = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      file = stream.readAllBytes();
    }
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", month, null, scheme);
    var preview = publications.preview(file, "基价.xlsx", context);
    publications.publish(file, "基价.xlsx", context, preview.previewFingerprint(), "efp-test");
  }

  PriceLinkedItem linked(String material, String supplier, String month, String expression) {
    return linked(material, supplier, month, expression, sourceBatch(month, "LINKED_ORIGINAL"));
  }

  PriceLinkedItem linked(
      String material, String supplier, String month, String expression, Long batch) {
    var key =
        new MaterialPriceRuleKey("COMMERCIAL", "210", material, supplier, supplier, "规格", "只");
    var compiled = new CompiledFormula(expression, List.of(), Map.of("cu", new BigDecimal("80")));
    var input =
        new OriginalLinkedWorkbookParser.FactorInput(
            "AVG|1#CU",
            new Address("因素", "E2"),
            FactorPriceUnit.CNY_KG,
            "元/公斤",
            "AVG|1#CU",
            "SAME_CANONICAL_FACTOR");
    var rule =
        new OriginalLinkedWorkbookParser.Rule(
            key,
            material,
            "ImportData",
            2,
            "O2",
            expression,
            expression,
            "0.0000",
            false,
            compiled,
            Map.of("cu", input),
            BigDecimal.TEN);
    var row = new PriceLinkedItem();
    row.setBusinessUnitType("COMMERCIAL");
    row.setOrgCode("210");
    row.setMaterialCode(material);
    row.setSupplierCode(supplier);
    row.setSupplierName(supplier);
    row.setSpecModel("规格");
    row.setUnit("只");
    row.setPricingMonth(month);
    row.setRuleIdentityKey(key.identityKey());
    row.setRuleRevision(1);
    row.setEffectiveFrom(java.time.YearMonth.parse(month).atDay(1));
    row.setRuleStatus("PUBLISHED");
    row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    row.setSourceUploadBatchId(batch);
    row.setFormulaExpr(expression);
    row.setSourceKind("PUBLIC");
    row.setTaxIncluded(0);
    row.setDeleted(0);
    row.setSourceInputSnapshotJson(snapshots.write(List.of(rule), "source-only"));
    row.setCreatedAt(LocalDateTime.now());
    row.setUpdatedAt(LocalDateTime.now());
    linked.insert(row);
    return row;
  }

  PriceRangeFactorRule range(String material, String month, String value) {
    return range(material, "R1", month, value);
  }

  PriceRangeFactorRule range(String material, String supplier, String month, String value) {
    var key =
        new MaterialPriceRuleKey("COMMERCIAL", "210", material, supplier, supplier, "规格", "只");
    var address = new Address("区间", "D2");
    var cell =
        new OriginalRangeWorkbookParser.CellSource(address, value, "NUMERIC", null, "0.0000");
    var band =
        new OriginalRangeWorkbookParser.Band(
            new OriginalRangeWorkbookParser.Bounds(
                new BigDecimal("80000"), new BigDecimal("100000"), true, true),
            cell,
            address,
            cell,
            null,
            new BigDecimal(value),
            null);
    var rule =
        new OriginalRangeWorkbookParser.Rule(
            key,
            material,
            "ImportData",
            2,
            cell,
            false,
            "AVG|1#CU",
            FactorPriceUnit.CNY_TON,
            "区间",
            2,
            "规格",
            supplier,
            "EXACT",
            List.of(band),
            List.of());
    var row = new PriceRangeFactorRule();
    row.setBusinessUnitType("COMMERCIAL");
    row.setOrgCode("210");
    row.setMaterialCode(material);
    row.setSupplierCode(supplier);
    row.setSupplierName(supplier);
    row.setSpecModel("规格");
    row.setPriceUnit("只");
    row.setPricingMonth(month);
    row.setRuleIdentityKey(key.identityKey());
    row.setVersionNo(1);
    row.setEffectiveFrom(java.time.YearMonth.parse(month).atDay(1));
    row.setRuleStatus("PUBLISHED");
    row.setFactorCode("AVG|1#CU");
    row.setFactorName("铜");
    row.setSourceUploadBatchId(sourceBatch(month, "RANGE_ORIGINAL"));
    row.setImportBatchNo("EFP-RANGE-" + row.getSourceUploadBatchId());
    row.setFactorUnit("CNY_TON");
    row.setCurrentFlag(1);
    row.setSourceInputSnapshotJson(
        new OriginalRangeRuleSnapshot(json).write(List.of(rule), "source-only"));
    row.setCreatedAt(LocalDateTime.now());
    row.setUpdatedAt(LocalDateTime.now());
    ranges.insert(row);
    return row;
  }

  Long sourceBatch(String month, String type) {
    var batch = new FactorUploadBatch();
    batch.setBatchNo("EFP-" + java.util.UUID.randomUUID());
    batch.setImportType(type);
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setPriceOrgCode("210");
    batch.setPriceMonth(month);
    batch.setStatus("PUBLISHED");
    batch.setFileName("原稿.xlsx");
    batch.setUploadedBy("efp-test");
    batch.setPublishedAt(LocalDateTime.now());
    batch.setCreatedAt(LocalDateTime.now());
    batch.setUpdatedAt(LocalDateTime.now());
    sql.getMapper(FactorUploadBatchMapper.class).insert(batch);
    return batch.getId();
  }
}
