package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.mapping.Environment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class OriginalLinkedImportIntegrationTest {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4").withCommand("--max-allowed-packet=64M")
          .withDatabaseName("original_rules")
          .withUsername("root")
          .withPassword("test-only-password");
  private static final String[] SCRIPTS = {
    "prc01_pre_migration_schema.sql",
    "V308__original_price_version_contract.sql",
    "V309__finance_factor_publication_note.sql",
    "V311__original_linked_expression_capacity.sql",
    "V325__finance_effective_price.sql",
    "V326__original_workbook_source.sql",
          "V330__scrap_price_monthly.sql"
  };
  private static FactorIdentityMapper identities;
  private static FactorUploadBatchMapper batches;
  private static FactorMonthlyPriceMapper prices;
  private static FactorRowRefMapper sources;
  private static PriceLinkedItemMapper items;
  private static SqlSessionTemplate effectiveSessions;
  private static DataSourceTransactionManager transactions;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final FactorCanonicalKeyServiceImpl canonical =
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
  private OriginalLinkedImportService service;
  private FinanceFactorPublicationService publications;
  private FinanceFactorPriceReader finance;

  @BeforeAll
  static void start() {
    MYSQL.start();
    for (String name : SCRIPTS)
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/" + name), "/tmp/" + name);
    var data =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    transactions = new DataSourceTransactionManager(data);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(new Environment("original", new SpringManagedTransactionFactory(), data));
    for (var mapper :
        List.of(
            com.sanhua.marketingcost.mapper.FinanceEffectivePriceMapper.class,
            com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper.class,
            com.sanhua.marketingcost.mapper.ScrapPriceMapper.class,
            FactorIdentityMapper.class,
            FactorUploadBatchMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class,
            PriceLinkedItemMapper.class)) config.addMapper(mapper);
    var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    effectiveSessions = sql;
    identities = sql.getMapper(FactorIdentityMapper.class);
    batches = sql.getMapper(FactorUploadBatchMapper.class);
    prices = sql.getMapper(FactorMonthlyPriceMapper.class);
    sources = sql.getMapper(FactorRowRefMapper.class);
    items = sql.getMapper(PriceLinkedItemMapper.class);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE original_rules");
      statement.execute("CREATE DATABASE original_rules CHARACTER SET utf8mb4");
    }
    for (String name : SCRIPTS) {
      var result =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot original_rules < \"$1\"",
              "migration",
              "/tmp/" + name);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    finance = new FinanceFactorPriceReader(batches, prices, identities, sources);
    publications =
        new FinanceFactorPublicationService(
            new FinanceFactorWorkbookParser(canonical),
            canonical,
            identities,
            batches,
            prices,
            sources,
            json,
            transactions,
            com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(
                effectiveSessions, json),
            com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(effectiveSessions, transactions, json));
    publishFinance("CU_90000");
    service = service(items);
  }

  @Test
  void financialRevisionSelectionIgnoresFutureHistoryAndOtherOrganizations() throws Exception {
    var revisions =
        new CostInputRevisionServiceImpl(
            new org.springframework.jdbc.core.JdbcTemplate(transactions.getDataSource()));
    var org = java.util.Set.of("210");
    String a = revisions.financePublicationRevision("COMMERCIAL", "2026-10", org);
    publishFinance("CU_100000");
    String b = revisions.financePublicationRevision("COMMERCIAL", "2026-10", org);
    assertThat(b).isNotEqualTo(a);
    for (String month : List.of("2026-09", "2026-11")) {
      byte[] bytes;
      try (var input = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
        bytes = input.readAllBytes();
      }
      var context = new FinanceFactorImportContext("COMMERCIAL", "210", month, null, "CU_90000");
      var preview = publications.preview(bytes, "财务.xlsx", context);
      publications.publish(bytes, "财务.xlsx", context, preview.previewFingerprint(), "test");
      assertThat(revisions.financePublicationRevision("COMMERCIAL", "2026-10", org)).isEqualTo(b);
    }
    String boardBefore =
        revisions.financePublicationRevision("COMMERCIAL", "2026-10", java.util.Set.of("220"));
    byte[] board;
    try (var input = getClass().getResourceAsStream("/finance-factor/plate-original.xlsx")) {
      board = input.readAllBytes();
    }
    var context = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    var preview = publications.preview(board, "板换.xlsx", context);
    publications.publish(board, "板换.xlsx", context, preview.previewFingerprint(), "test");
    assertThat(revisions.financePublicationRevision("COMMERCIAL", "2026-10", org)).isEqualTo(b);
    assertThat(
            revisions.financePublicationRevision("COMMERCIAL", "2026-10", java.util.Set.of("220")))
        .isNotEqualTo(boardBefore);
    assertThat(revisions.financePublicationRevision("HOUSEHOLD", "2026-10", org))
        .isEqualTo(boardBefore);
  }

  @Test
  void previewHasNoWritesAndPublishingPreservesLongFormulaAndOriginalInputs() throws Exception {
    String formula = String.join("+", java.util.Collections.nCopies(150, "因素!E2")) + "+因素!E3";
    var command = command(workbook(formula, "因素!E2/1.13"));
    var preview = service.preview(command);
    assertThat(preview.isCanConfirm()).as(preview.getErrors().toString()).isTrue();
    assertThat(preview.getRows())
        .extracting(PriceLinkedType2ImportPreviewResponse.RowPreview::getAction)
        .containsOnly("NEW");
    assertThat(preview.getFinancial().get("210").months().sourceMonth()).isEqualTo("2026-10");
    assertThat(count("lp_price_linked_item")).isZero();
    assertThat(count("lp_factor_upload_batch")).isOne();
    assertThat(preview.getRows().getFirst().getSystemTaxExcludedPrice())
        .isGreaterThan(java.math.BigDecimal.ZERO);
    var confirmed = service.confirm(confirmed(command, preview));
    assertThat(confirmed.getLinkedVersionCreatedCount()).isEqualTo(2);
    var rows = items.selectList(null);
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().getFormulaExpr()).hasSizeGreaterThan(512);
    var rule = new OriginalLinkedRuleSnapshot(json).read(rows.getFirst());
    assertThat(rule.sourceFormulaText()).isEqualTo("铜价×用量＋加工费\n按原稿含税口径");
    assertThat(rule.identity().supplierCode()).isEqualTo("SUP");
    assertThat(rule.compiled().sourceInputs().values())
        .contains(new java.math.BigDecimal("109.114"));
    assertThat(count("lp_factor_monthly_price")).isEqualTo(56);
    assertThat(
            finance
                .readEffective(context("CU_90000"), Condition.GENERAL, "M1")
                .financial()
                .factors()
                .get("AVG|1#CU")
                .price())
        .isEqualByComparingTo("90");
    var replay = service.confirm(confirmed(command, preview));
    assertThat(replay.getFactorUploadBatchId()).isEqualTo(confirmed.getFactorUploadBatchId());
    assertThat(replay.getLinkedUnchangedSkippedCount()).isEqualTo(2);
    assertThat(service.preview(command).getRows())
        .extracting(PriceLinkedType2ImportPreviewResponse.RowPreview::getAction)
        .containsOnly("UNCHANGED");
    assertThat(count("lp_price_linked_item")).isEqualTo(2);
  }

  @Test
  void identicalOriginalReplayUsesSavedPricesAfterFinancialRevisionWithoutCreatingRules()
      throws Exception {
    var command = command(workbook("因素!E2", "因素!E3"));
    var confirmed = confirmed(command, service.preview(command));
    service.confirm(confirmed);
    publishFinance("CU_100000");
    var replay = service.confirm(confirmed);
    assertThat(replay.getLinkedVersionCreatedCount()).isZero();
    assertThat(replay.getLinkedUnchangedSkippedCount()).isEqualTo(2);
    assertThat(replay.getEffectivePriceGeneratedCount()).isZero();
    assertThat(count("lp_price_linked_item")).isEqualTo(2);
    assertThat(count("lp_finance_effective_price")).isEqualTo(4);
  }

  @Test
  void identicalDuplicateSourcesAreRetainedButConflictingSameIdentityIsRejected() throws Exception {
    byte[] identical;
    try (var book =
            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(workbook("因素!E2", "因素!E2")));
        var out = new ByteArrayOutputStream()) {
      book.getSheet("ImportData").getRow(2).getCell(1).setCellValue("M1");
      book.write(out);
      identical = out.toByteArray();
    }
    var command = command(identical);
    var preview = service.preview(command);
    assertThat(preview.isCanConfirm()).isTrue();
    assertThat(preview.getDuplicateRowCount()).isOne();
    var result = service.confirm(confirmed(command, preview));
    assertThat(result.getLinkedVersionCreatedCount()).isOne();
    var saved =
        json.readValue(
            items.selectList(null).getFirst().getSourceInputSnapshotJson(),
            OriginalLinkedRuleSnapshot.SavedRule.class);
    assertThat(saved.duplicateSources()).hasSize(1);
    assertThat(saved.duplicateSources().getFirst().row()).isEqualTo(3);
    try (var book =
            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(identical));
        var out = new ByteArrayOutputStream()) {
      book.getSheet("ImportData").getRow(2).getCell(6).setCellFormula("因素!E2+1");
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(out);
      var conflict = command(out.toByteArray());
      var failed = service.preview(conflict);
      assertThat(failed.isCanConfirm()).isFalse();
      assertThatThrownBy(() -> service.confirm(confirmed(conflict, failed)))
          .hasMessageContaining("不同规则");
      assertThat(count("lp_price_linked_item")).isOne();
    }
  }

  @Test
  void changedFinanceVersionAndReplacedFileBothInvalidatePreview() throws Exception {
    var command = command(workbook("因素!E2", "因素!E3"));
    var preview = service.preview(command);
    publishFinance("CU_100000");
    assertThatThrownBy(() -> service.confirm(confirmed(command, preview)))
        .hasMessageContaining("版本已变化");
    var replaced = command(workbook("因素!E2+1", "因素!E3"));
    assertThatThrownBy(() -> service.confirm(confirmed(replaced, preview)))
        .hasMessageContaining("SHA-256");
    assertThat(count("lp_price_linked_item")).isZero();
  }

  @Test
  void sourceErrorsAndMissingFinanceFactorBlockWholeBatch() throws Exception {
    var invalid = command(workbook("因素!E2", "1/0"));
    var preview = service.preview(invalid);
    assertThat(preview.isCanConfirm()).isFalse();
    assertThat(preview.getErrors()).hasSize(1);
    assertThatThrownBy(() -> service.confirm(confirmed(invalid, preview)))
        .hasMessageContaining("整批未发布");
    execute(
        "DELETE p FROM lp_factor_monthly_price p JOIN lp_factor_identity i ON"
            + " i.id=p.factor_identity_id WHERE i.canonical_factor_key='AVG|1#ZN' AND"
            + " p.scheme_code='CU_90000'");
    var missing = command(workbook("因素!E2", "因素!E3"));
    var missingPreview = service.preview(missing);
    assertThat(missingPreview.isCanConfirm()).isFalse();
    assertThat(missingPreview.getErrors())
        .anyMatch(e -> e.getMessage().contains("缺少财务因素=AVG|1#ZN"));
    assertThatThrownBy(() -> service.confirm(confirmed(missing, missingPreview)))
        .hasMessageContaining("整批未发布");
    assertThat(count("lp_price_linked_item")).isZero();
    assertThat(count("lp_factor_upload_batch")).isOne();
  }

  @Test
  void failureOnSecondInsertRollsBackBatchAndFirstRule() throws Exception {
    var broken = mock(PriceLinkedItemMapper.class, delegatesTo(items));
    var inserts = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (inserts.incrementAndGet() == 2) throw new IllegalStateException("模拟第二行写入失败");
              return items.insert((PriceLinkedItem) invocation.getArgument(0));
            })
        .when(broken)
        .insert(any(PriceLinkedItem.class));
    var failing = service(broken);
    var command = command(workbook("因素!E2", "因素!E3"));
    var preview = failing.preview(command);
    assertThatThrownBy(() -> failing.confirm(confirmed(command, preview)))
        .hasMessageContaining("第二行");
    assertThat(count("lp_price_linked_item")).isZero();
    assertThat(count("lp_factor_upload_batch")).isOne();
  }

  @Test
  void staleRulePreviewIsRejectedAndPriorPublishedRuleRemainsImmutable() throws Exception {
    var first = command(workbook("因素!E2", "因素!E3"));
    var firstPreview = service.preview(first);
    service.confirm(confirmed(first, firstPreview));
    var old =
        items.selectList(null).stream().map(PriceLinkedItem::getSourceInputSnapshotJson).toList();
    var second = command(workbook("因素!E2+1", "因素!E3"));
    var stale = command(workbook("因素!E2+2", "因素!E3"));
    var secondPreview = service.preview(second);
    var stalePreview = service.preview(stale);
    service.confirm(confirmed(second, secondPreview));
    assertThatThrownBy(() -> service.confirm(confirmed(stale, stalePreview)))
        .hasMessageContaining("版本已变化");
    assertThat(
            items.selectList(null).stream()
                .filter(i -> i.getRuleRevision() == 1)
                .map(PriceLinkedItem::getSourceInputSnapshotJson)
                .toList())
        .containsExactlyElementsOf(old);
    assertThat(count("lp_price_linked_item")).isEqualTo(4);
  }

  @Test
  void concurrentConfirmationPublishesExactlyOneBatch() throws Exception {
    var command = command(workbook("因素!E2", "因素!E3"));
    var preview = service.preview(command);
    var confirm = confirmed(command, preview);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> service.confirm(confirm));
      var b = executor.submit(() -> service.confirm(confirm));
      assertThat(a.get(30, TimeUnit.SECONDS).getFactorUploadBatchId())
          .isEqualTo(b.get(30, TimeUnit.SECONDS).getFactorUploadBatchId());
    }
    assertThat(count("lp_factor_upload_batch")).isEqualTo(2);
    assertThat(count("lp_price_linked_item")).isEqualTo(2);
  }

  @Test
  void quoteAndMonthlyEntriesUseCurrentFinancialFactorsAndOnlyCustomerCopperChanges()
      throws Exception {
    var command = command(workbook("因素!E2+因素!E3", "因素!E2/1.13"));
    service.confirm(confirmed(command, service.preview(command)));
    var item =
        items.selectList(null).stream()
            .filter(i -> i.getMaterialCode().equals("M1"))
            .findFirst()
            .orElseThrow();
    var runtime =
        new OriginalLinkedPriceRuntime(
            new OriginalLinkedRuleSnapshot(json),
            new OriginalLinkedRuleCalculator(),
            finance,
            json);
    var previews =
        new PriceLinkedFormulaPreviewServiceImpl(null, null, null, null, null, json, runtime, null);

    var calc =
        new PriceLinkedCalcServiceImpl(
            mock(PriceLinkedCalcItemMapper.class), items, mock(OaFormMapper.class), json, previews);
    var oa = new com.sanhua.marketingcost.entity.OaForm();
    oa.setCopperPrice(new java.math.BigDecimal("100000"));
    oa.setZincPrice(new java.math.BigDecimal("999999"));
    var p0 = new com.sanhua.marketingcost.entity.PriceLinkedCalcItem();
    p0.setPricingMonth("2026-11");
    var p1 = new com.sanhua.marketingcost.entity.PriceLinkedCalcItem();
    p1.setPricingMonth("2026-11");
    calc.calculateQuoteItemForEnsure(
        p0,
        item,
        oa,
        java.util.Map.of("Cu", new java.math.BigDecimal("888")),
        "FINANCE_QUOTE_BASE", null);
    calc.calculateQuoteItemForEnsure(p1, item, oa, null);
    assertThat(p0.getCalcStatus()).isEqualTo("OK");
    assertThat(p1.getCalcStatus()).isEqualTo("OK");
    assertThat(p0.getPartUnitPrice()).isEqualByComparingTo("111.636291");
    assertThat(p1.getPartUnitPrice()).isEqualByComparingTo("121.636291");
    assertThat(p1.getPartUnitPrice().subtract(p0.getPartUnitPrice())).isEqualByComparingTo("10");
    assertThat(p0.getInputSnapshotJson()).contains("2026-11", "2026-10", "CU_90000");
    assertThat(p0.getCalcFingerprint()).hasSize(64).isNotEqualTo(p1.getCalcFingerprint());
    var repository = mock(com.sanhua.marketingcost.service.PriceLinkedImportBasisRepository.class);
    when(repository.findById(item.getId())).thenReturn(item);
    when(repository.findUploadBatchById(item.getSourceUploadBatchId()))
        .thenReturn(batches.selectById(item.getSourceUploadBatchId()));
    var authentication =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "tester", "n/a", List.of());
    authentication.setDetails(
        java.util.Map.of(
            com.sanhua.marketingcost.security.BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,
            "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(authentication);
    try {
      var basis =
          new PriceLinkedImportBasisServiceImpl(repository, json).getImportBasis(item.getId());
      assertThat(basis.isImportBasisAvailable()).isTrue();
      assertThat(basis.getSnapshot().inputCells()).isNotEmpty();
      assertThat(basis.getFactorBindings()).hasSize(2);
      verify(repository, never()).findBindings(any());
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    var first = previews.previewForRefresh(item, java.util.Map.of());
    publishFinance("CU_100000");
    var second = previews.previewForRefresh(item, java.util.Map.of());
    assertThat(second.getError()).isNull();
    assertThat(second.getResult()).isNotEqualByComparingTo(first.getResult());
    assertThat(second.getCalcFingerprint()).isNotEqualTo(first.getCalcFingerprint());
    assertThat(count("lp_price_linked_item")).isEqualTo(2);
    assertThat(items.selectById(item.getId()).getSourceInputSnapshotJson())
        .isEqualTo(item.getSourceInputSnapshotJson());
    item.setSourceInputSnapshotJson("{}");
    calc.calculateQuoteItemForEnsure(p0, item, oa, null);
    assertThat(p0.getCalcStatus()).isEqualTo("FAILED");
    assertThat(p0.getPartUnitPrice()).isNull();
    assertThat(p0.getInputSnapshotJson()).isNull();
    assertThat(p0.getCalcMessage()).contains("快照不可用");
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
  void allTenOriginalFilesPublishEverySourceAndRetainBothOrganizations() throws Exception {
    byte[] boardFile;
    try (var stream = getClass().getResourceAsStream("/finance-factor/plate-original.xlsx")) {
      boardFile = stream.readAllBytes();
    }
    var boardContext =
        new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    var boardPreview = publications.preview(boardFile, "板换.xlsx", boardContext);
    publications.publish(
        boardFile, "板换.xlsx", boardContext, boardPreview.previewFingerprint(), "test");
    var files = new java.util.LinkedHashSet<String>();
    for (var row :
        json.readTree(java.nio.file.Path.of(System.getProperty("prc.baseline")).toFile()))
      if (row.path("sample").asText().startsWith("L")) files.add(row.path("file").asText());
    int sourceRows = 0;
    int ruleRows = 0;
    var reports = new java.util.ArrayList<Object>();
    for (String file : files) {
      var command = command(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(file)));
      var preview = service.preview(command);
      assertThat(preview.getErrors()).as(file).isEmpty();
      assertThat(preview.isCanConfirm()).isTrue();
      var result = service.confirm(confirmed(command, preview));
      sourceRows += result.getBusinessRowCount();
      ruleRows += result.getLinkedVersionCreatedCount();
      reports.add(
          java.util.Map.of(
              "file",
              file,
              "sourceRows",
              result.getBusinessRowCount(),
              "ruleRows",
              result.getLinkedVersionCreatedCount(),
              "batches",
              result.getSourceUploadBatchIds()));
    }
    var saved = items.selectList(null);
    assertThat(sourceRows).isEqualTo(3776);
    assertThat(ruleRows).isEqualTo(3771);
    assertThat(saved).hasSize(3771);
    assertThat(saved.stream().filter(i -> i.getOrgCode().equals("220")).count()).isEqualTo(1);
    int sources = 0;
    for (var item : saved) {
      var snapshot =
          json.readValue(
              item.getSourceInputSnapshotJson(), OriginalLinkedRuleSnapshot.SavedRule.class);
      sources += 1 + snapshot.duplicateSources().size();
      assertThat(batches.selectById(item.getSourceUploadBatchId()).getPriceOrgCode())
          .isEqualTo(item.getOrgCode());
    }
    assertThat(sources).isEqualTo(3776);
    var runtime =
        new OriginalLinkedPriceRuntime(
            new OriginalLinkedRuleSnapshot(json),
            new OriginalLinkedRuleCalculator(),
            finance,
            json);
    var magnetic =
        saved.stream()
            .filter(i -> i.getMaterialCode().equals("201320083"))
            .findFirst()
            .orElseThrow();
    var computed = runtime.preview(magnetic, "2026-10", null);
    assertThat(computed.getResult())
        .isCloseTo(
            new java.math.BigDecimal("2.81672566371681"),
            org.assertj.core.data.Offset.offset(new java.math.BigDecimal("0.00000001")));
    assertThat(magnetic.getSourceInputSnapshotJson())
        .contains("联动价|NEOLET-6HR", "AVG|氧化钕", "CONFIRMED_NEOLET_FINISHED_POWDER");
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of(System.getProperty("prc.report"))
                .resolveSibling("original-publication-3776.json")
                .toFile(),
            reports);
  }

  private OriginalLinkedImportService service(PriceLinkedItemMapper mapper) {
    return new OriginalLinkedImportService(
        new OriginalLinkedWorkbookParser(canonical),
        new OriginalLinkedRuleCalculator(),
        new OriginalLinkedRuleSnapshot(json),
        finance,
        identities,
        batches,
        mapper,
        json,
        transactions,
        com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(
            effectiveSessions, json));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
  void boardOriginalPublishes123RowsAndReplaysFreshPreviewWithoutLosingSources() throws Exception {
    byte[] financeFile;
    try (var input = getClass().getResourceAsStream("/finance-factor/plate-original.xlsx")) {
      financeFile = input.readAllBytes();
    }
    var scope = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    var fp = publications.preview(financeFile, "板换.xlsx", scope);
    publications.publish(financeFile, "板换.xlsx", scope, fp.previewFingerprint(), "test");
    String file = null;
    for (var row :
        json.readTree(java.nio.file.Path.of(System.getProperty("prc.baseline")).toFile()))
      if (row.path("sample").asText().equals("B01")) {
        file = row.path("file").asText();
        break;
      }
    assertThat(file).isNotNull();
    var command = command(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(file)));
    var preview = service.preview(command);
    assertThat(preview.getErrors()).isEmpty();
    assertThat(preview.getBusinessRowCount()).isEqualTo(123);
    var published = service.confirm(confirmed(command, preview));
    var saved = items.selectList(null);
    assertThat(saved).hasSize(123);
    assertThat(saved).allMatch(i -> i.getOrgCode().equals("220"));
    var originals = new java.util.LinkedHashMap<Long, String>();
    saved.forEach(i -> originals.put(i.getId(), i.getSourceInputSnapshotJson()));
    var fresh = service.preview(command);
    var replay = service.confirm(confirmed(command, fresh));
    assertThat(replay.getSourceUploadBatchIds()).isEqualTo(published.getSourceUploadBatchIds());
    assertThat(items.selectList(null)).hasSize(123);
    var runtime =
        new OriginalLinkedPriceRuntime(
            new OriginalLinkedRuleSnapshot(json),
            new OriginalLinkedRuleCalculator(),
            finance,
            json);
    var oa = new com.sanhua.marketingcost.entity.OaForm();
    var material =
        saved.stream()
            .filter(i -> i.getMaterialCode().equals("9810000080436"))
            .findFirst()
            .orElseThrow();
    oa.setBoardTender(true);
    var tender = runtime.preview(material, "2026-10", null, oa);
    assertThat(tender.getError()).isNull();
    assertThat(tender.getResult())
        .isCloseTo(
            new java.math.BigDecimal("14.24778761061947"),
            within(new java.math.BigDecimal("0.00000001")));
    oa.setSus304Price(new java.math.BigDecimal("23000"));
    var customer = runtime.preview(material, "2026-10", null, oa);
    assertThat(customer.getResult())
        .isCloseTo(
            new java.math.BigDecimal("21.32743362831858"),
            within(new java.math.BigDecimal("0.00000001")));
    assertThat(customer.getInputSnapshotJson())
        .contains("CUSTOMER_SUS", "boardInputs", "boardPricing");
    oa.setBoardTender(false);
    assertThat(runtime.preview(material, "2026-10", null, oa).getResult())
        .isEqualByComparingTo(customer.getResult());
    var snapshot = json.readTree(customer.getInputSnapshotJson());
    var context =
        json.treeToValue(
            snapshot.path("context"),
            com.sanhua.marketingcost.service.pricing.PriceFactorContext.class);
    assertThat(context.financialOnly().boardInputs()).isEqualTo(context.boardInputs());
    items
        .selectList(null)
        .forEach(
            i -> assertThat(i.getSourceInputSnapshotJson()).isEqualTo(originals.get(i.getId())));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of(System.getProperty("prc.report"))
                .resolveSibling("board-publication-123.json")
                .toFile(),
            java.util.Map.of(
                "passed",
                true,
                "sourceRows",
                123,
                "storedRules",
                saved.size(),
                "batchIds",
                published.getSourceUploadBatchIds(),
                "freshPreviewReplay",
                true));
  }

  private FinanceFactorImportContext context(String scheme) {
    return new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, scheme);
  }

  private void publishFinance(String scheme) throws Exception {
    byte[] file;
    try (var stream = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      file = stream.readAllBytes();
    }
    var preview = publications.preview(file, "财务.xlsx", context(scheme));
    publications.publish(
        file, "财务.xlsx", context(scheme), preview.previewFingerprint(), "test-finance");
  }

  private PriceLinkedImportCommand command(byte[] bytes) {
    return new PriceLinkedImportCommand(
        bytes, "原稿.xlsx", "2026-10", "COMMERCIAL", false, null, null, null, null);
  }

  private PriceLinkedImportCommand confirmed(
      PriceLinkedImportCommand command, PriceLinkedType2ImportPreviewResponse preview) {
    return new PriceLinkedImportCommand(
            command.getFileBytes(),
            command.getSourceFileName(),
            command.getPricingMonth(),
            command.getBusinessUnitType(),
            false,
            null,
            null,
            null,
            preview.getFileSha256())
        .withPreviewFingerprint(preview.getPreviewFingerprint());
  }

  private static byte[] workbook(String formula1, String formula2) throws Exception {
    try (var book = new XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var factors = book.createSheet("因素");
      var header = factors.createRow(0);
      String[] factorHeaders = {"序号", "名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < factorHeaders.length; i++)
        header.createCell(i).setCellValue(factorHeaders[i]);
      for (int i = 1; i <= 2; i++) {
        var row = factors.createRow(i);
        row.createCell(0).setCellValue(i);
        row.createCell(1).setCellValue(i == 1 ? "铜" : "锌");
        row.createCell(2).setCellValue(i == 1 ? "Cu" : "1#Zn");
        row.createCell(3).setCellValue("平均价");
        row.createCell(4).setCellValue(i == 1 ? 109.114 : 25);
        row.createCell(5).setCellValue("公斤");
      }
      var main = book.createSheet("ImportData");
      var mainHeader = main.createRow(0);
      String[] headers = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税", "联动公式"};
      for (int i = 0; i < headers.length; i++) mainHeader.createCell(i).setCellValue(headers[i]);
      for (int i = 1; i <= 2; i++) {
        var row = main.createRow(i);
        String[] values = {"210", "M" + i, "供应商", "SUP", "SPEC", "只"};
        for (int j = 0; j < values.length; j++) row.createCell(j).setCellValue(values[j]);
        row.createCell(6).setCellFormula(i == 1 ? formula1 : formula2);
        row.createCell(7).setCellValue(false);
        row.createCell(8).setCellValue("铜价×用量＋加工费\n按原稿含税口径");
      }
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();
      book.write(out);
      return out.toByteArray();
    }
  }

  private static void execute(String sql) throws Exception {
    try (var c =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static int count(String table) throws Exception {
    try (var c =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getInt(1);
    }
  }
}
