package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
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
class OriginalRangeImportIntegrationTest {
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
  private static PriceRangeFactorRuleMapper rules;
  private static PriceRangeItemMapper bands;
  private static SqlSessionTemplate effectiveSessions;
  private static DataSourceTransactionManager transactions;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final FactorCanonicalKeyServiceImpl canonical =
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
  private OriginalRangeImportService service;
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
            com.sanhua.marketingcost.mapper.PriceLinkedItemMapper.class,
            com.sanhua.marketingcost.mapper.ScrapPriceMapper.class,
            FactorIdentityMapper.class,
            FactorUploadBatchMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class,
            PriceRangeFactorRuleMapper.class,
            PriceRangeItemMapper.class)) config.addMapper(mapper);
    var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    effectiveSessions = sql;
    identities = sql.getMapper(FactorIdentityMapper.class);
    batches = sql.getMapper(FactorUploadBatchMapper.class);
    prices = sql.getMapper(FactorMonthlyPriceMapper.class);
    sources = sql.getMapper(FactorRowRefMapper.class);
    rules = sql.getMapper(PriceRangeFactorRuleMapper.class);
    bands = sql.getMapper(PriceRangeItemMapper.class);
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
    service = service(bands);
  }

  @Test
  void nativeBatchReadsRulesBandsCustomerAndFinancialSnapshotOnce() throws Exception {
    var command = command(workbook(1, false));
    service.confirm(confirmed(command, service.preview(command)));
    var countedRules =
        mock(PriceRangeFactorRuleMapper.class, org.mockito.AdditionalAnswers.delegatesTo(rules));
    var countedBands =
        mock(PriceRangeItemMapper.class, org.mockito.AdditionalAnswers.delegatesTo(bands));
    var countedFactors =
        mock(FactorMonthlyPriceMapper.class, org.mockito.AdditionalAnswers.delegatesTo(prices));
    var reader = new FinanceFactorPriceReader(batches, countedFactors, identities, sources);
    var oa = new com.sanhua.marketingcost.entity.OaForm();
    oa.setCopperPrice(new java.math.BigDecimal("100000"));
    var oaMapper = mock(OaFormMapper.class);
    when(oaMapper.selectOne(any())).thenReturn(oa);
    var supplierService =
        mock(com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService.class);
    var months = mock(com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector.class);
    when(months.select(any(), any(), any()))
        .thenReturn(
            new com.sanhua.marketingcost.service.pricing.PriceMonthSelection("2026-10", "2026-10"));
    var effective = com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(effectiveSessions, json);
    var supplierPrices = new com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService(
        mock(com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper.class),
        mock(com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper.class));
    var resolver =
        new com.sanhua.marketingcost.service.pricing.RangePriceResolver(
            countedBands,
            countedRules,
            oaMapper,
            new com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector(
                supplierService),
            months,
            new OriginalRangePriceRuntime(
                new OriginalRangeRuleSnapshot(json),
                new OriginalRangeRuleCalculator(),
                reader,
                countedBands,
                oaMapper,
                json), effective,
            new com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceSelectionService(effective, supplierPrices), supplierPrices);
    var run = new com.sanhua.marketingcost.dto.CostRunContext();
    run.setPricingMonth("2026-10");
    run.setBusinessUnitType("COMMERCIAL");
    run.setPriceOrgCode("210");
    run.setPriceAsOfTime(java.time.LocalDateTime.of(2026, 10, 1, 12, 0));
    var requests =
        new java.util.ArrayList<com.sanhua.marketingcost.service.pricing.PriceResolveRequest>();
    for (int quantity : new int[] {1, 2, 3}) {
      var item = new com.sanhua.marketingcost.dto.CostRunPartItemDto();
      item.setPartCode("M01");
      item.setPartQty(java.math.BigDecimal.valueOf(quantity));
      requests.add(
          new com.sanhua.marketingcost.service.pricing.PriceResolveRequest(item, null, run));
    }
    var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
    var result = tx.execute(status -> resolver.resolveBatch("OA", requests));
    assertThat(result).hasSize(3);
    assertThat(result.values())
        .allSatisfy(
            price -> {
              assertThat(price.unitPrice()).isEqualByComparingTo("3");
              assertThat(price.resultRefId()).isNotNull();
              assertThat(price.evidence().inputSnapshotJson()).contains("100000");
            });
    org.mockito.Mockito.verify(countedRules, org.mockito.Mockito.times(1)).selectList(any());
    org.mockito.Mockito.verify(countedBands, org.mockito.Mockito.times(1)).selectList(any());
    org.mockito.Mockito.verify(countedFactors, org.mockito.Mockito.times(1))
        .findPublishedScheme(any(), any());
    org.mockito.Mockito.verify(oaMapper, org.mockito.Mockito.times(1)).selectOne(any());
    org.mockito.Mockito.verify(months, org.mockito.Mockito.times(1)).select(any(), any(), any());
  }

  @Test
  void originalRulesAreImmutableReplayIsIdempotentAndFinanceChangesNeedNoReimport()
      throws Exception {
    var command = command(workbook(1, false));
    var preview = service.preview(command);
    assertThat(preview.errors()).isEmpty();
    assertThat(preview.businessRowCount()).isOne();
    assertThat(preview.rows().getFirst().result().priceExcludingTax()).isEqualByComparingTo("1");
    var accepted = confirmed(command, preview);
    var publication = service.confirm(accepted);
    assertThat(publication.replay()).isFalse();
    assertThat(preview.rows())
        .extracting(OriginalRangeImportService.PreviewRow::action)
        .containsOnly("NEW");
    assertThat(service.preview(command).rows())
        .extracting(OriginalRangeImportService.PreviewRow::action)
        .containsOnly("UNCHANGED");
    assertThat(service.confirm(accepted).replay()).isTrue();
    assertThat(service.confirm(confirmed(command, service.preview(command))).replay()).isTrue();
    assertThat(rules.selectCount(null)).isEqualTo(1);
    assertThat(bands.selectCount(null)).isEqualTo(4);
    var rule = rules.selectList(null).getFirst();
    String immutable = rule.getSourceInputSnapshotJson();
    var oa = new com.sanhua.marketingcost.entity.OaForm();
    oa.setCopperPrice(new java.math.BigDecimal("100000"));
    var oaMapper = mock(OaFormMapper.class);
    when(oaMapper.selectOne(any())).thenReturn(oa);
    var runtime =
        new OriginalRangePriceRuntime(
            new OriginalRangeRuleSnapshot(json),
            new OriginalRangeRuleCalculator(),
            finance,
            bands,
            oaMapper,
            json);
    var run = new com.sanhua.marketingcost.dto.CostRunContext();
    run.setPricingMonth("2026-10");
    run.setBusinessUnitType("COMMERCIAL");
    var customer = runtime.resolve(rule, "OA", run, null);
    assertThat(customer.unitPrice()).isEqualByComparingTo("3");
    run.setPriceScenarioType("FINANCE_QUOTE_BASE");
    var base = runtime.resolve(rule, "OA", run, null);
    assertThat(base.unitPrice()).isEqualByComparingTo("1");
    var evidence = new OriginalRangeCalculationEvidence(json);
    assertThat(
            evidence.validatePair(
                "M01",
                customer.evidence().inputSnapshotJson(),
                base.evidence().inputSnapshotJson(),
                customer.unitPrice(),
                base.unitPrice(),
                new java.math.BigDecimal("100"),
                new java.math.BigDecimal("90")))
        .isTrue();
    publishFinance("CU_100000");
    var updated = runtime.resolve(rule, "OA", run, null);
    assertThat(updated.unitPrice()).isEqualByComparingTo("3");
    assertThat(rules.selectById(rule.getId()).getSourceInputSnapshotJson()).isEqualTo(immutable);
    assertThat(rules.selectCount(null)).isEqualTo(1);
    assertThat(evidence.read(base.evidence().inputSnapshotJson()).result().priceExcludingTax())
        .isEqualByComparingTo("1");
    assertThatThrownBy(
            () ->
                evidence.validatePair(
                    "M01",
                    customer.evidence().inputSnapshotJson(),
                    updated.evidence().inputSnapshotJson(),
                    customer.unitPrice(),
                    updated.unitPrice(),
                    null,
                    null))
        .hasMessageContaining("输入版本不一致");
  }

  @Test
  void fileMonthAndFinancialRevisionChangesInvalidatePreview() throws Exception {
    var command = command(workbook(1, false));
    var preview = service.preview(command);
    var accepted = confirmed(command, preview);
    var changed = command(workbook(2, false));
    assertThatThrownBy(
            () ->
                service.confirm(
                    new OriginalRangeImportService.Command(
                        changed.bytes(),
                        changed.fileName(),
                        changed.businessUnitType(),
                        changed.pricingMonth(),
                        accepted.expectedSha256(),
                        accepted.previewFingerprint())))
        .hasMessageContaining("摘要不一致");
    assertThatThrownBy(
            () ->
                service.confirm(
                    new OriginalRangeImportService.Command(
                        command.bytes(),
                        command.fileName(),
                        command.businessUnitType(),
                        "2026-11",
                        accepted.expectedSha256(),
                        accepted.previewFingerprint())))
        .hasMessageContaining("重新预检");
    publishFinance("CU_100000");
    assertThatThrownBy(() -> service.confirm(accepted)).hasMessageContaining("重新预检");
    assertThat(rules.selectCount(null)).isZero();
    assertThat(bands.selectCount(null)).isZero();
  }

  @Test
  void lateBandFailureRollsBackTheEntirePublication() throws Exception {
    var original = command(workbook(1, false));
    service.confirm(confirmed(original, service.preview(original)));
    long count = batches.selectCount(null);
    var failing = mock(PriceRangeItemMapper.class, delegatesTo(bands));
    var writes = new AtomicInteger();
    doAnswer(
            call -> {
              if (writes.incrementAndGet() == 2)
                throw new IllegalStateException("forced last band failure");
              return bands.insert(
                  (com.sanhua.marketingcost.entity.PriceRangeItem) call.getArgument(0));
            })
        .when(failing)
        .insert(any(com.sanhua.marketingcost.entity.PriceRangeItem.class));
    var transactionService = service(failing);
    var changed = command(workbook(2, false));
    assertThatThrownBy(
            () ->
                transactionService.confirm(confirmed(changed, transactionService.preview(changed))))
        .hasMessageContaining("forced last band failure");
    assertThat(batches.selectCount(null)).isEqualTo(count);
    assertThat(rules.selectCount(null)).isEqualTo(1);
    assertThat(bands.selectCount(null)).isEqualTo(4);
    assertThat(
            new OriginalRangeRuleSnapshot(json)
                .read(rules.selectList(null).getFirst())
                .rule()
                .bands()
                .getFirst()
                .price())
        .isEqualByComparingTo("1");
  }

  @Test
  void concurrentConfirmCreatesOneImmutableRevision() throws Exception {
    var command = command(workbook(1, false));
    var accepted = confirmed(command, service.preview(command));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> service.confirm(accepted));
      var second = executor.submit(() -> service.confirm(accepted));
      var values = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(values.stream().filter(OriginalRangeImportService.Publication::replay).count())
          .isOne();
    }
    assertThat(rules.selectCount(null)).isEqualTo(1);
    assertThat(bands.selectCount(null)).isEqualTo(4);
  }

  @Test
  void missingCurrentBandBlocksWholeFileWithoutPublishingGoodRowsOnly() throws Exception {
    var command = command(workbook(1, true));
    var preview = service.preview(command);
    assertThat(preview.canConfirm()).isFalse();
    assertThat(preview.errors()).isNotEmpty();
    assertThatThrownBy(() -> service.confirm(confirmed(command, preview)))
        .hasMessageContaining("整批未发布");
    assertThat(rules.selectCount(null)).isZero();
    assertThat(bands.selectCount(null)).isZero();
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
  void allElevenOriginalFilesPublishAll378SourcesWithReplayAndPreservePreciseSnapshots()
      throws Exception {
    var files = new java.util.LinkedHashMap<String, Integer>();
    for (var row :
        json.readTree(java.nio.file.Path.of(System.getProperty("prc.baseline")).toFile()))
      if (row.path("sample").asText().startsWith("R"))
        files.merge(row.path("file").asText(), 1, Integer::sum);
    var reports = new java.util.ArrayList<java.util.Map<String, Object>>();
    int total = 0;
    for (var entry : files.entrySet()) {
      var file = java.nio.file.Path.of(entry.getKey());
      var command =
          new OriginalRangeImportService.Command(
              java.nio.file.Files.readAllBytes(file),
              file.getFileName().toString(),
              "COMMERCIAL",
              "2026-10",
              null,
              null);
      var preview = service.preview(command);
      assertThat(preview.errors()).as(entry.getKey()).isEmpty();
      assertThat(preview.businessRowCount()).isEqualTo(entry.getValue());
      var accepted = confirmed(command, preview);
      var published = service.confirm(accepted);
      assertThat(service.confirm(accepted).replay()).isTrue();
      total += published.businessRowCount();
      reports.add(
          java.util.Map.of(
              "file",
              file.toString(),
              "sourceRows",
              published.businessRowCount(),
              "ruleCount",
              published.ruleCount(),
              "batchIds",
              published.batchIds()));
    }
    assertThat(total).isEqualTo(378);
    assertThat(files).hasSize(11);
    var snapshots = new OriginalRangeRuleSnapshot(json);
    int sources = 0;
    for (var rule : rules.selectList(null)) {
      var saved = snapshots.read(rule);
      sources += saved.sources().size();
      assertThat(saved.rule().bands()).isNotEmpty();
      assertThat(rule.getRuleStatus()).isEqualTo("PUBLISHED");
      assertThat(rule.getPricingMonth()).isEqualTo("2026-10");
    }
    assertThat(sources).isEqualTo(378);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of(System.getProperty("prc.report"))
                .resolveSibling("original-range-publication-378.json")
                .toFile(),
            reports);
  }

  private OriginalRangeImportService service(PriceRangeItemMapper mapper) {
    return new OriginalRangeImportService(
        new OriginalRangeWorkbookParser(),
        new OriginalRangeRuleCalculator(),
        new OriginalRangeRuleSnapshot(json),
        finance,
        identities,
        batches,
        rules,
        mapper,
        json,
        transactions,
        com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(
            effectiveSessions, json));
  }

  private void publishFinance(String scheme) throws Exception {
    byte[] bytes;
    try (var input = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      bytes = input.readAllBytes();
    }
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, scheme);
    var preview = publications.preview(bytes, "财务.xlsx", context);
    publications.publish(bytes, "财务.xlsx", context, preview.previewFingerprint(), "test");
  }

  private OriginalRangeImportService.Command command(byte[] bytes) {
    return new OriginalRangeImportService.Command(
        bytes, "区间原稿.xlsx", "COMMERCIAL", "2026-10", null, null);
  }

  private OriginalRangeImportService.Command confirmed(
      OriginalRangeImportService.Command command, OriginalRangeImportService.Preview preview) {
    return new OriginalRangeImportService.Command(
        command.bytes(),
        command.fileName(),
        command.businessUnitType(),
        command.pricingMonth(),
        preview.fileSha256(),
        preview.previewFingerprint());
  }

  private byte[] workbook(double start, boolean missing) throws Exception {
    try (var book = new XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var main = book.createSheet("ImportData");
      var header = main.createRow(0);
      String[] names = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      var row = main.createRow(1);
      String[] values = {"210", "M01", "测试供应商有限公司", "S01", "SPEC", "只"};
      for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      row.createCell(6).setCellValue(99);
      row.createCell(7).setCellValue(false);
      var matrix = book.createSheet("矩阵");
      matrix.createRow(0).createCell(0).setCellValue("铜价区间表");
      var h = matrix.createRow(1);
      h.createCell(0).setCellValue("U9代码");
      h.createCell(1).setCellValue("图号");
      h.createCell(2).setCellValue("供应商名称");
      var tax = matrix.createRow(2);
      var data = matrix.createRow(3);
      data.createCell(0).setCellValue("M01");
      data.createCell(1).setCellValue("SPEC");
      data.createCell(2).setCellValue("测试供应商");
      for (int i = 0; i < 4; i++) {
        int c = i * 2 + 3;
        h.createCell(c).setCellValue((90000 + i * 5000) + "-" + (94999 + i * 5000));
        tax.createCell(c).setCellValue("不含税");
        tax.createCell(c + 1).setCellValue("含13%税价");
        if (!missing || i != 0) data.createCell(c).setCellValue(start + i);
        data.createCell(c + 1).setCellValue((start + i) * 1.13);
      }
      book.write(out);
      return out.toByteArray();
    }
  }
}
