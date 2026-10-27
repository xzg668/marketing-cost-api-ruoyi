package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.mapping.Environment;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 真实Spring事务、生产Mapper、完整来源工作簿和独立MySQL；失败必须整批回滚。 */
@Tag("integration")
class FinanceFactorPublicationIntegrationTest {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4").withCommand("--max-allowed-packet=64M")
          .withDatabaseName("finance_factor")
          .withUsername("root")
          .withPassword("test-only-password");
  private static FactorIdentityMapper identities;
  private static FactorUploadBatchMapper batches;
  private static FactorMonthlyPriceMapper prices;
  private static FactorRowRefMapper sources;
  private static SqlSessionTemplate effectiveSessions;
  private static DataSourceTransactionManager transactions;
  private FinanceFactorPublicationService service;
  private final FinanceFactorImportContext commercial =
      new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_90000");

  @BeforeAll
  static void start() {
    MYSQL.start();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/prc01_pre_migration_schema.sql"), "/tmp/base.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V308__original_price_version_contract.sql"),
        "/tmp/migration.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V309__finance_factor_publication_note.sql"),
        "/tmp/note.sql");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V318__technical_factor_source_reference.sql"),
        "/tmp/technical-source.sql");
    for (String name :
        List.of("V325__finance_effective_price.sql", "V326__original_workbook_source.sql", "V330__scrap_price_monthly.sql", "V332__settlement_price_versions.sql", "V341__settlement_effective_price.sql"))
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/" + name), "/tmp/" + name);
    var dataSource =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    transactions = new DataSourceTransactionManager(dataSource);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(
        new Environment("finance-factor", new SpringManagedTransactionFactory(), dataSource));
    for (var mapper :
        List.of(
            com.sanhua.marketingcost.mapper.FinanceEffectivePriceMapper.class,
            com.sanhua.marketingcost.mapper.PriceLinkedItemMapper.class,
            com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper.class,
            com.sanhua.marketingcost.mapper.ScrapPriceMapper.class,
            FactorIdentityMapper.class,
            FactorUploadBatchMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class)) config.addMapper(mapper);
    var template = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    effectiveSessions = template;
    identities = template.getMapper(FactorIdentityMapper.class);
    batches = template.getMapper(FactorUploadBatchMapper.class);
    prices = template.getMapper(FactorMonthlyPriceMapper.class);
    sources = template.getMapper(FactorRowRefMapper.class);
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
      statement.execute("DROP DATABASE finance_factor");
      statement.execute("CREATE DATABASE finance_factor CHARACTER SET utf8mb4");
    }
    script("/tmp/base.sql");
    script("/tmp/migration.sql");
    script("/tmp/note.sql");
    script("/tmp/technical-source.sql");
    script("/tmp/V325__finance_effective_price.sql");
    script("/tmp/V326__original_workbook_source.sql");
    script("/tmp/V330__scrap_price_monthly.sql");
    script("/tmp/V332__settlement_price_versions.sql");
    script("/tmp/V341__settlement_effective_price.sql");
    service = service(sources);
  }

  @Test
  void technicalFormulaUsesPublishedInputsAndNewRevisionChangesItsFingerprint() throws Exception {
    byte[] file = FinanceFactorWorkbookParserTest.fixture("commercial");
    publishCommercial(file);
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var query =
        new com.sanhua.marketingcost.formula.registry.FinanceFactorVariableQuery(
            reader,
            identities,
            prices,
            new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var variableMapper = mock(com.sanhua.marketingcost.mapper.PriceVariableMapper.class);
    var assistant =
        new org.apache.ibatis.builder.MapperBuilderAssistant(
            new MybatisConfiguration(), "tech-test");
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        assistant, com.sanhua.marketingcost.entity.PriceVariable.class);
    var copper = technicalVariable("Cu", "Cu");
    var zinc = technicalVariable("Zn", "1#Zn");
    org.mockito.Mockito.when(variableMapper.selectList(any())).thenReturn(List.of(copper, zinc));
    var alias = new com.sanhua.marketingcost.formula.normalize.VariableAliasIndex(variableMapper);
    alias.init();
    var placeholders =
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport.defaultRegistry();
    var json = new ObjectMapper().findAndRegisterModules();
    var registry =
        new com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl(
            variableMapper, query, json, placeholders);
    var calculator =
        new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
            new com.sanhua.marketingcost.formula.normalize.FormulaNormalizer(alias, placeholders),
            registry,
            variableMapper,
            mock(com.sanhua.marketingcost.mapper.PriceLinkedItemMapper.class),
            new com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker(),
            json,
            null,
            query);
    var rule = new com.sanhua.marketingcost.entity.PriceLinkedItem();
    rule.setId(501L);
    rule.setTechnicalVersionId(81L);
    rule.setSourceKind("TECH_SUPPLEMENTAL");
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setOrgCode("210");
    rule.setMaterialCode("TECH-1");
    rule.setPricingMonth("2026-11");
    rule.setTaxIncluded(1);
    rule.setFormulaExpr("Cu+Zn");
    var form = new com.sanhua.marketingcost.entity.OaForm();
    form.setCopperPrice(new java.math.BigDecimal("120000"));
    form.setZincPrice(new java.math.BigDecimal("99000"));
    var finance =
        com.sanhua.marketingcost.dto.LinkedPriceVariableContext.quote(form)
            .factorSource("FINANCE_QUOTE_BASE")
            .pricingMonth("2026-11");
    var customer =
        com.sanhua.marketingcost.dto.LinkedPriceVariableContext.quote(form)
            .factorSource("OA_LOCKED")
            .pricingMonth("2026-11");
    var p0 = calculator.previewForRefresh(rule, finance, form);
    var p1 = calculator.previewForRefresh(rule, customer, form);
    assertThat(p0.getError()).isNull();
    assertThat(p1.getError()).isNull();
    assertThat(p1.getResult().subtract(p0.getResult())).isEqualByComparingTo("30");
    var before = json.readTree(p0.getInputSnapshotJson());
    assertThat(before.path("inputs").path("Cu").decimalValue()).isEqualByComparingTo("90");
    assertThat(before.path("inputs").path("Zn"))
        .isEqualTo(json.readTree(p1.getInputSnapshotJson()).path("inputs").path("Zn"));
    assertThat(before.path("calculatorVersion").asText()).isEqualTo("TECH_FORMULA_V1");
    var customerSnapshot = json.readTree(p1.getInputSnapshotJson());
    for (String stable : List.of("definitions", "factorKeys", "usedFinanceKeys"))
      assertThat(customerSnapshot.path(stable)).as(stable).isEqualTo(before.path(stable));
    assertThat(calculator.previewForRefresh(rule, finance, form).getCalcFingerprint())
        .isEqualTo(p0.getCalcFingerprint());
    var revised = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_100000");
    var preview = service.preview(file, "财务.xlsx", revised);
    service.publish(file, "财务.xlsx", revised, preview.previewFingerprint(), "finance");
    var after = calculator.previewForRefresh(rule, finance, form);
    assertThat(after.getError()).isNull();
    assertThat(after.getCalcFingerprint()).isNotEqualTo(p0.getCalcFingerprint());
    assertThat(json.readTree(after.getInputSnapshotJson()).path("inputs").path("Cu").decimalValue())
        .isEqualByComparingTo("100");
    assertThat(json.readTree(p0.getInputSnapshotJson())).isEqualTo(before);
  }

  private com.sanhua.marketingcost.entity.PriceVariable technicalVariable(
      String code, String factor) {
    var value = new com.sanhua.marketingcost.entity.PriceVariable();
    value.setVariableCode(code);
    value.setVariableName(code);
    value.setStatus("active");
    value.setFactorType("FINANCE_FACTOR");
    value.setResolverKind("FINANCE");
    value.setResolverParams("{\"factorCode\":\"" + factor + "\",\"priceSource\":\"平均价\"}");
    return value;
  }

  @Test
  void technicalImportReferencesPublishedIdentityAndPreservesHistoricalInputs() throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var keys = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    var authority = new FinanceFactorAuthority(identities, prices, keys);
    assertThat(authority.manages("COMMERCIAL", "平均价", "1#Zn")).isTrue();
    assertThat(authority.manages("OTHER", "平均价", "1#Zn")).isFalse();
    var before = prices.selectList(null);
    var publicCu = factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null);
    var source = new com.sanhua.marketingcost.dto.FactorRowParseResult();
    source.setSourceSheetName("影响因素");
    source.setSourceRowNumber(2);
    source.setFactorSeqNo("1");
    source.setFactorName("技术文件旧铜价");
    source.setShortName("Cu");
    source.setPriceSource("平均价");
    source.setPrice(new java.math.BigDecimal("109.114"));
    source.setUnit("公斤");
    var sheet = new com.sanhua.marketingcost.dto.FactorSheetParseResult();
    sheet.setSheetName("影响因素");
    sheet.getRows().add(source);
    var workbook = new com.sanhua.marketingcost.dto.FactorWorkbookParseResult();
    workbook.setSourceFileName("技术原输入.xlsx");
    workbook.getSheets().add(sheet);
    var importer =
        new com.sanhua.marketingcost.service.impl.FactorMonthlyPriceUpsertServiceImpl(
            identities,
            prices,
            mock(com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper.class),
            authority);
    org.springframework.test.util.ReflectionTestUtils.setField(
        importer, "factorCanonicalKeyService", keys);
    var sourceService =
        new com.sanhua.marketingcost.service.impl.FactorUploadBatchServiceImpl(batches, sources);
    var request = new com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setPriceMonth("2026-10");
    request.setParseResult(workbook);
    request.setUploadedBy("integration");
    var batch = sourceService.createFactorBatch(request);
    var result =
        importer.upsert(
            workbook, "2026-10", "COMMERCIAL", "integration", batch.getId(), "OVERWRITE");
    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getFactorIdentityId()).isEqualTo(publicCu.getFactorIdentityId());
              assertThat(row.getFactorMonthlyPriceId()).isNull();
              assertThat(row.getMonthlyPriceAction()).isEqualTo("FINANCE_REFERENCE");
            });
    assertThat(sourceService.saveRowRefs(batch.getId(), workbook, result).getErrors()).isEmpty();
    var saved = sourceService.findRowRef(batch.getId(), "影响因素", 2);
    assertThat(saved.getFactorMonthlyPriceId()).isNull();
    assertThat(saved.getPrice()).isEqualByComparingTo("109.114");
    script("/tmp/technical-source.sql");
    assertThat(sourceService.findRowRef(batch.getId(), "影响因素", 2).getPrice())
        .isEqualByComparingTo("109.114");
    assertThat(prices.selectList(null)).usingRecursiveComparison().isEqualTo(before);
    assertThatThrownBy(() -> authority.requirePrivate(publicCu.getFactorIdentityId()))
        .hasMessageContaining("财务基价");
  }

  @Test
  void variableAndIdentityConsumersUseTheSamePublicationWithOrganizationAndRevisionIsolation()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var query =
        new com.sanhua.marketingcost.formula.registry.FinanceFactorVariableQuery(
            reader,
            identities,
            prices,
            new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var rule = new com.sanhua.marketingcost.entity.PriceLinkedItem();
    rule.setMaterialCode("TECH-MAT-1");
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setOrgCode("210");
    var request =
        new com.sanhua.marketingcost.formula.registry.VariableContext()
            .linkedItem(rule)
            .pricingMonth("2026-11");
    var copper = java.util.Map.<String, Object>of("factorCode", "cu", "priceSource", "平均价");
    var captured = query.query(copper, request).orElseThrow();
    assertThat(captured.price()).isEqualByComparingTo("90");
    assertThat(captured.sourceMonth()).isEqualTo("2026-10");
    var identity =
        factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null).getFactorIdentityId();
    assertThat(
            query
                .query(java.util.Map.of("factorIdentityId", identity), request)
                .orElseThrow()
                .price())
        .isEqualByComparingTo("90");
    assertThat(prices.countPublishedFactorKey("COMMERCIAL", "210", "AVG|1#CU")).isPositive();
    assertThat(prices.countPublishedFactorKey("HOUSEHOLD", "210", "AVG|1#CU")).isZero();
    assertThat(prices.countPublishedFactorKey("COMMERCIAL", "220", "AVG|1#CU")).isZero();
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var revised = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_100000");
    var preview = service.preview(file, "财务.xlsx", revised);
    service.publish(file, "财务.xlsx", revised, preview.previewFingerprint(), "finance");
    assertThat(query.query(copper, request).orElseThrow().price()).isEqualByComparingTo("100");
    assertThat(captured.price()).isEqualByComparingTo("90");
    var board = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    var boardFile = FinanceFactorWorkbookParserTest.fixture("plate");
    var boardPreview = service.preview(boardFile, "板换.xlsx", board);
    service.publish(boardFile, "板换.xlsx", board, boardPreview.previewFingerprint(), "finance");
    var zinc = java.util.Map.<String, Object>of("shortName", "1#Zn", "priceSource", "平均价");
    // 商用原表 I4 缓存值；切换整份财务方案时Zn随该方案变化。
    assertThat(query.query(zinc, request).orElseThrow().price())
        .isEqualByComparingTo("24.0403236023383");
    rule.setOrgCode("220");
    assertThat(query.query(zinc, request).orElseThrow().price()).isEqualByComparingTo("21.636");
  }

  @Test
  void previewIsReadOnlyAndPublishingPreservesEverySourceWhileDeduplicatingCopper()
      throws Exception {
    byte[] file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var preview = service.preview(file, "商业基价.xlsx", commercial);
    assertThat(preview.workbook().problems()).isEmpty();
    assertThat(preview.changes()).hasSize(56).allMatch(c -> c.kind().equals("NEW"));
    assertCounts(0, 0, 0, 0);
    var published =
        service.publish(
            file, "商业基价.xlsx", commercial, preview.previewFingerprint(), "finance-user");
    assertThat(published.unchanged()).isFalse();
    assertThat(published.revision()).isOne();
    assertThat(published.sourceRowCount()).isEqualTo(60);
    assertCounts(14, 1, 56, 60);
    assertThat(prices.findPublishedScheme(scope("210", "CU_90000", Condition.GENERAL), null))
        .hasSize(14);
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null).getPrice())
        .isEqualByComparingTo("90");
    assertThat(factor("210", "CU_100000", Condition.GENERAL, "AVG|1#CU", null).getPrice())
        .isEqualByComparingTo("100");
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|PCU", null).getPrice())
        .isEqualByComparingTo("115.979");
    assertThat(
            count(
                "SELECT COUNT(*) FROM lp_factor_row_ref WHERE scheme_code='CU_90000' AND"
                    + " factor_identity_id="
                    + factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null)
                        .getFactorIdentityId()))
        .isEqualTo(2);
    var batch = batches.selectById(published.batchId());
    assertThat(batch.getUploadedBy()).isEqualTo("finance-user");
    assertThat(batch.getStatus()).isEqualTo("PUBLISHED");
    assertThat(batch.getDefaultSchemeCode()).isEqualTo("CU_90000");
    assertThat(batch.getFileSha256()).isEqualTo(preview.workbook().fileSha256());
  }

  @Test
  void commercialAndBoardShareIdentitiesButKeepTheirPricesConditionsAndUnitsSeparate()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var context = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    var file = FinanceFactorWorkbookParserTest.fixture("plate");
    var preview = service.preview(file, "板换.xlsx", context);
    service.publish(file, "板换.xlsx", context, preview.previewFingerprint(), "finance");
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|1#ZN", null).getPrice())
        .isEqualByComparingTo("21.6362912421045");
    assertThat(factor("220", "BOARD", Condition.NON_TENDER, "AVG|1#ZN", null).getPrice())
        .isEqualByComparingTo("21.636");
    assertThat(factor("220", "BOARD", Condition.NON_TENDER, "AVG|304/2B/1219", null).getPrice())
        .isEqualByComparingTo("20");
    assertThat(factor("220", "BOARD", Condition.TENDER, "AVG|304/2B/1219", null).getPrice())
        .isEqualByComparingTo("15");
    assertThat(factor("220", "BOARD", Condition.TENDER, "AVG|1#AG", null).getPrice())
        .isEqualByComparingTo("15772.86");
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null).getFactorIdentityId())
        .isEqualTo(
            factor("220", "BOARD", Condition.NON_TENDER, "AVG|1#CU", null).getFactorIdentityId());
    assertThat(
            count(
                "SELECT COUNT(*) FROM lp_factor_row_ref WHERE original_price=20000 AND price=20 AND"
                    + " conversion_basis='BOARD_FIXED_SUS_VALUE_DIVIDE_1000'"))
        .isEqualTo(2);
  }

  @Test
  void repeatedConfirmationIsIdempotentAndSameMonthCorrectionRetainsOldPrices() throws Exception {
    byte[] original = FinanceFactorWorkbookParserTest.fixture("commercial");
    var preview = service.preview(original, "A.xlsx", commercial);
    var first =
        service.publish(original, "A.xlsx", commercial, preview.previewFingerprint(), "finance");
    var repeat =
        service.publish(
            original, "改名但内容相同.xlsx", commercial, preview.previewFingerprint(), "finance");
    assertThat(repeat.unchanged()).isTrue();
    assertThat(repeat.batchId()).isEqualTo(first.batchId());
    assertCounts(14, 1, 56, 60);
    var old = factor("210", "CU_90000", Condition.GENERAL, "AVG|PCU", null);
    var corrected = changedPcu(116);
    var newPreview = service.preview(corrected, "B.xlsx", commercial);
    assertThat(newPreview.changes().stream().filter(c -> c.kind().equals("CHANGED"))).hasSize(4);
    var second =
        service.publish(
            corrected, "B.xlsx", commercial, newPreview.previewFingerprint(), "finance");
    assertThat(second.revision()).isEqualTo(2);
    assertCounts(14, 2, 112, 120);
    assertThat(prices.selectById(old.getId()).getPrice()).isEqualByComparingTo("115.979");
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|PCU", 1).getPrice())
        .isEqualByComparingTo("115.979");
    assertThat(factor("210", "CU_90000", Condition.GENERAL, "AVG|PCU", null).getPrice())
        .isEqualByComparingTo("116");
    // 再次发布旧文件是明确恢复到新修订，不能返回已经被替代的旧批次冒充当前成功。
    var restorePreview = service.preview(original, "A.xlsx", commercial);
    assertThat(
            service
                .publish(
                    original, "A.xlsx", commercial, restorePreview.previewFingerprint(), "finance")
                .revision())
        .isEqualTo(3);
  }

  @Test
  void fileAndCurrentPublicationChangesInvalidatePreviewWithoutAnyPartialWrites() throws Exception {
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var before = service.preview(file, "A.xlsx", commercial);
    assertThatThrownBy(
            () ->
                service.publish(
                    changedPcu(116), "A.xlsx", commercial, before.previewFingerprint(), "finance"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重新预检");
    assertCounts(0, 0, 0, 0);
    publishCommercial(file);
    var staleFile = changedPcu(117);
    var stale = service.preview(staleFile, "C.xlsx", commercial);
    publishCommercial(changedPcu(116));
    assertThatThrownBy(
            () ->
                service.publish(
                    staleFile, "C.xlsx", commercial, stale.previewFingerprint(), "finance"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重新预检");
    assertCounts(14, 2, 112, 120);
  }

  @Test
  void changingDefaultSchemePublishesANewCompleteVersion() throws Exception {
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    publishCommercial(file);
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, "CU_100000");
    var preview = service.preview(file, "A.xlsx", context);
    var second = service.publish(file, "A.xlsx", context, preview.previewFingerprint(), "finance");
    assertThat(second.revision()).isEqualTo(2);
    assertThat(service.latest(context).getDefaultSchemeCode()).isEqualTo("CU_100000");
    assertThat(prices.findPublishedScheme(scope("210", "CU_90000", Condition.GENERAL), 2))
        .hasSize(14);
    assertThat(prices.findPublishedScheme(scope("210", "CU_100000", Condition.GENERAL), 2))
        .hasSize(14);
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var defaults = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-10", null, null);
    var current = reader.read(defaults, Condition.GENERAL, null);
    assertThat(current.scope().schemeCode()).isEqualTo("CU_100000");
    assertThat(current.required("AVG|1#CU").price()).isEqualByComparingTo("100");
    assertThat(current.required("AVG|1#CU").sources())
        .extracting(FactorPriceSnapshot.Source::cellRef)
        .containsExactly("I3", "I7");
    var previous = reader.read(defaults, Condition.GENERAL, 1);
    assertThat(previous.scope().schemeCode()).isEqualTo("CU_90000");
    assertThat(previous.required("AVG|1#CU").price()).isEqualByComparingTo("90");
    assertThatThrownBy(() -> previous.required("AVG|NOT_PRESENT"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("缺少因素");
  }

  @Test
  void sourceWriteFailureRollsBackBatchPricesAndNewIdentitiesTogether() throws Exception {
    FactorRowRefMapper failing = mock(FactorRowRefMapper.class, delegatesTo(sources));
    AtomicInteger inserted = new AtomicInteger();
    doAnswer(
            call -> {
              if (inserted.incrementAndGet() == 3)
                throw new IllegalStateException("injected source write failure");
              return sources.insert(call.getArgument(0, FactorRowRef.class));
            })
        .when(failing)
        .insert(any(FactorRowRef.class));
    var broken = service(failing);
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var preview = broken.preview(file, "A.xlsx", commercial);
    assertThatThrownBy(
            () ->
                broken.publish(file, "A.xlsx", commercial, preview.previewFingerprint(), "finance"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected");
    assertThat(inserted.get()).isEqualTo(3);
    assertCounts(0, 0, 0, 0);
  }

  @Test
  void copperMaintenanceUsesTheSamePublicationAndKeepsAllOtherInputsAndOriginalSources()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var cu = factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null);
    var zinc = factor("210", "CU_90000", Condition.GENERAL, "AVG|1#ZN", null);
    var adjustment =
        new FinanceFactorCuAdjustmentService(prices, batches, identities, sources, service);
    var preview =
        adjustment.preview(cu.getId(), new java.math.BigDecimal("100000"), "财务调整Cu", "COMMERCIAL");
    assertThat(preview.changes().stream().filter(c -> c.kind().equals("CHANGED")))
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.canonicalKey()).isEqualTo("AVG|1#CU");
              assertThat(change.previousPrice()).isEqualByComparingTo("90");
              assertThat(change.price()).isEqualByComparingTo("100");
            });
    assertCounts(14, 1, 56, 60);
    var result =
        adjustment.publish(
            cu.getId(),
            new java.math.BigDecimal("100000"),
            "财务调整Cu",
            "COMMERCIAL",
            preview.previewFingerprint(),
            "finance");
    assertThat(result.revision()).isEqualTo(2);
    assertThat(batches.selectById(result.batchId()).getPublicationNote()).isEqualTo("财务调整Cu");
    assertThat(prices.selectById(cu.getId()).getPrice()).isEqualByComparingTo("90");
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var current = reader.read(commercial, Condition.GENERAL, null);
    assertThat(current.required("AVG|1#CU").price()).isEqualByComparingTo("100");
    assertThat(current.required("AVG|1#ZN").price()).isEqualByComparingTo(zinc.getPrice());
    assertThat(current.required("AVG|PCU").price()).isEqualByComparingTo("115.979");
    assertThat(current.required("AVG|1#CU").sources())
        .allMatch(
            source ->
                source.conversionBasis().equals("FINANCE_MANUAL_CU_ADJUSTMENT")
                    && source.originalValue().equals("90"));
    assertThat(
            adjustment
                .publish(
                    cu.getId(),
                    new java.math.BigDecimal("100000"),
                    "财务调整Cu",
                    "COMMERCIAL",
                    preview.previewFingerprint(),
                    "finance")
                .unchanged())
        .isTrue();
    assertCounts(14, 2, 112, 120);
    assertThatThrownBy(
            () ->
                adjustment.preview(
                    cu.getId(), new java.math.BigDecimal("110000"), "再调整", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("刷新");
  }

  @Test
  void copperMaintenanceCannotEditPcuOrCrossBusinessUnitAndDoesNotRoundSmallPricesToZero()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var cu = factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null);
    var pcu = factor("210", "CU_90000", Condition.GENERAL, "AVG|PCU", null);
    var adjustment =
        new FinanceFactorCuAdjustmentService(prices, batches, identities, sources, service);
    assertThatThrownBy(
            () ->
                adjustment.preview(
                    pcu.getId(), new java.math.BigDecimal("100000"), "调整", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("财务Cu");
    assertThatThrownBy(
            () ->
                adjustment.preview(
                    cu.getId(), new java.math.BigDecimal("100000"), "调整", "HOUSEHOLD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当前业务单元");
    assertThatThrownBy(
            () -> adjustment.preview(cu.getId(), java.math.BigDecimal.ZERO, "调整", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("大于0");
    var precise =
        adjustment.preview(cu.getId(), new java.math.BigDecimal("0.00001"), "精度验证", "COMMERCIAL");
    assertThat(
            precise.changes().stream()
                .filter(c -> c.kind().equals("CHANGED"))
                .findFirst()
                .orElseThrow()
                .price())
        .isEqualByComparingTo("0.00000001");
    assertCounts(14, 1, 56, 60);
  }

  @RepeatedTest(5)
  void concurrentSamePreviewCreatesOnePublicationAndReturnsTheSameVersion() throws Exception {
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var preview = service.preview(file, "A.xlsx", commercial);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<FinanceFactorPublicationService.Publication> publish =
          () ->
              service.publish(file, "A.xlsx", commercial, preview.previewFingerprint(), "finance");
      var requests = executor.invokeAll(List.of(publish, publish));
      var first = requests.getFirst().get(15, TimeUnit.SECONDS);
      var second = requests.getLast().get(15, TimeUnit.SECONDS);
      assertThat(first.batchId()).isEqualTo(second.batchId());
      assertThat(List.of(first.unchanged(), second.unchanged()))
          .containsExactlyInAnyOrder(false, true);
    }
    assertCounts(14, 1, 56, 60);
  }

  @Test
  void effectiveInputsCarryWholeFinancialPublicationWithoutFutureOrBackfillTakingPrecedence()
      throws Exception {
    var file = FinanceFactorWorkbookParserTest.fixture("commercial");
    var october = publishCommercial(file);
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var novemberContext =
        new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, null);
    var carried = reader.readEffective(novemberContext, Condition.GENERAL, "MAT-01");
    assertThat(carried.months().sourceMonth()).isEqualTo("2026-10");
    assertThat(carried.months().requestedMonth()).isEqualTo("2026-11");
    assertThat(carried.months().carriedForward()).isTrue();
    assertThat(carried.financial().batchId()).isEqualTo(october.batchId());

    publishForMonth(changedPcu(120), "2026-12");
    publishForMonth(changedPcu(110), "2026-09");
    var afterFutureAndBackfill = reader.readEffective(novemberContext, Condition.GENERAL, "MAT-01");
    assertThat(afterFutureAndBackfill.financial().batchId()).isEqualTo(october.batchId());
    assertThat(afterFutureAndBackfill.financial().required("AVG|PCU").price())
        .isEqualByComparingTo("115.979");

    var november = publishForMonth(changedPcu(118), "2026-11");
    var current = reader.readEffective(novemberContext, Condition.GENERAL, "MAT-01");
    assertThat(current.months().carriedForward()).isFalse();
    assertThat(current.financial().batchId()).isEqualTo(november.batchId());
    assertThat(current.financial().required("AVG|PCU").price()).isEqualByComparingTo("118");
    var updated = publishForMonth(changedPcu(119), "2026-11");
    assertThat(
            reader
                .readEffective(novemberContext, Condition.GENERAL, "MAT-01")
                .financial()
                .batchId())
        .isEqualTo(updated.batchId());
    assertThat(current.financial().required("AVG|PCU").price()).isEqualByComparingTo("118");
    assertThat(carried.financial().required("AVG|PCU").price()).isEqualByComparingTo("115.979");
  }

  @Test
  void calculationTransactionPinsPublicationAcrossNewPublicationAndNestedTransactions()
      throws Exception {
    var first = publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    byte[] changed = changedPcu(119);
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
    outer.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var inner = new org.springframework.transaction.support.TransactionTemplate(transactions);
    inner.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var before =
        outer.execute(
            status -> {
              var p0 = reader.readEffective(commercial, Condition.GENERAL, "A");
              inner.execute(
                  innerStatus -> {
                    try {
                      publishCommercial(changed);
                    } catch (Exception error) {
                      throw new RuntimeException(error);
                    }
                    var newer = reader.readEffective(commercial, Condition.GENERAL, "B");
                    assertThat(newer.financial().required("AVG|PCU").price())
                        .isEqualByComparingTo("119");
                    assertThat(newer.financial().batchId()).isNotEqualTo(first.batchId());
                    return null;
                  });
              var p1 =
                  reader
                      .readEffective(commercial, Condition.GENERAL, "A")
                      .withCustomerCopperPerTon(new java.math.BigDecimal("100000"));
              assertThat(p1.financial()).isSameAs(p0.financial());
              assertThat(reader.readEffective(commercial, Condition.GENERAL, "B").financial())
                  .isSameAs(p0.financial());
              return p0;
            });
    assertThat(
            org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(
                reader))
        .isFalse();
    var next = reader.readEffective(commercial, Condition.GENERAL, "A");
    assertThat(next.financial().batchId()).isNotEqualTo(before.financial().batchId());
    assertThat(next.financial().required("AVG|PCU").price()).isEqualByComparingTo("119");
    assertThat(before.financial().required("AVG|PCU").price()).isEqualByComparingTo("115.979");
  }

  @Test
  void effectiveFinancialInputsNeverCrossBusinessUnitOrganizationOrUseFutureMonth()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    for (var context :
        List.of(
            new FinanceFactorImportContext("HOUSEHOLD", "210", "2026-11", null, null),
            new FinanceFactorImportContext("COMMERCIAL", "220", "2026-11", null, null),
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-09", null, null))) {
      assertThatThrownBy(() -> reader.readEffective(context, Condition.GENERAL, "MAT-01"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("未发布");
    }
    var missingScheme =
        new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, "MISSING");
    assertThatThrownBy(() -> reader.readEffective(missingScheme, Condition.GENERAL, "MAT-01"))
        .hasMessageContaining("不含所选方案");
  }

  @Test
  void publishedFinancialSchemeFeedsCopperOnlyCustomerScenarioWithAllSourcesIntact()
      throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    var p0 = reader.readEffective(commercial, Condition.GENERAL, "MAT-01");
    var p1 = p0.withCustomerCopperPerTon(new java.math.BigDecimal("100000"));
    assertThat(p0.financial().factors()).hasSize(14);
    assertThat(
            p0.requiredPrice(
                "AVG|1#CU", com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("90");
    assertThat(
            p1.requiredPrice(
                "AVG|CU", com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("100");
    for (var entry : p0.financial().factors().entrySet()) {
      if (entry.getKey().equals("AVG|1#CU")) continue;
      var unit =
          com.sanhua.marketingcost.service.pricing.FactorPriceUnit.require(entry.getValue().unit());
      assertThat(p1.requiredPrice(entry.getKey(), unit, true))
          .isEqualByComparingTo(entry.getValue().price());
      assertThat(p1.financial().required(entry.getKey()).sources())
          .isEqualTo(entry.getValue().sources());
    }
    assertThat(p1.financial()).isSameAs(p0.financial());
    assertThat(p1.financial().scope().schemeCode()).isEqualTo("CU_90000");
  }

  @Test
  void monthlyCommercialExportEditAndPublishCreatesNextMonthWithoutChangingPreviousPublication() throws Exception {
    byte[] original = FinanceFactorWorkbookParserTest.fixture("commercial");
    var october = publishCommercial(original);
    var oldPrices = prices.selectList(null);
    int oldBatches = count("SELECT COUNT(*) FROM lp_factor_upload_batch");
    var file = monthlyExport("COMMERCIAL", "210", "2026-11");
    assertThat(file.sourceMonth()).isEqualTo("2026-10");
    assertThat(file.fileName()).contains("商用", "2026-10", "2026-11");
    assertThat(count("SELECT COUNT(*) FROM lp_factor_upload_batch")).isEqualTo(oldBatches);
    assertThat(prices.selectList(null)).usingRecursiveComparison().isEqualTo(oldPrices);
    var parser = new FinanceFactorWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var parsed = parser.parse(file.bytes(), file.fileName(), "210", null);
    assertThat(parsed.problems()).isEmpty();assertThat(parsed.values()).hasSize(56);
    var target = parsed.values().stream().filter(v -> v.canonicalKey().equals("AVG|PCU") && v.schemeCode().equals("CU_90000")).findFirst().orElseThrow();
    byte[] edited = changeExportedPrice(file.bytes(), target, "120.1234567890123456");
    var november = new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, "CU_90000");
    var preview = service.preview(edited, file.fileName(), november);
    assertThat(preview.workbook().problems()).isEmpty();
    var saved = service.publish(edited, file.fileName(), november, preview.previewFingerprint(), "monthly-finance");
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    assertThat(reader.read(november, Condition.GENERAL, null).required("AVG|PCU").price()).isEqualByComparingTo("120.1234567890123456");
    assertThat(reader.read(commercial, Condition.GENERAL, null).batchId()).isEqualTo(october.batchId());
    assertThat(prices.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(FactorMonthlyPrice.class)
        .eq(FactorMonthlyPrice::getSourceUploadBatchId, october.batchId()))).usingRecursiveComparison().isEqualTo(oldPrices);
    assertThat(service.publish(edited, file.fileName(), november, preview.previewFingerprint(), "monthly-finance").batchId()).isEqualTo(saved.batchId());
  }

  @Test
  void monthlyBoardExportKeepsBothConditionsAndUsesExplicitPerKilogramSteelValues() throws Exception {
    var board = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, "BOARD");
    byte[] original = FinanceFactorWorkbookParserTest.fixture("plate");
    var first = service.preview(original, "board.xlsx", board);
    service.publish(original, "board.xlsx", board, first.previewFingerprint(), "finance");
    var file = monthlyExport("COMMERCIAL", "220", "2026-11");
    var parser = new FinanceFactorWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var parsed = parser.parse(file.bytes(), file.fileName(), "220", null);
    assertThat(parsed.values()).hasSize(16);assertThat(parsed.problems()).isEmpty();
    var target = parsed.values().stream().filter(v -> v.canonicalKey().equals("AVG|304/2B/1219") && v.condition() == Condition.NON_TENDER).findFirst().orElseThrow();
    byte[] edited = changeExportedPrice(file.bytes(), target, "5.1234567890123456");
    var next = new FinanceFactorImportContext("COMMERCIAL", "220", "2026-11", null, "BOARD");
    var preview = service.preview(edited, file.fileName(), next);
    assertThat(preview.workbook().problems()).isEmpty();
    service.publish(edited, file.fileName(), next, preview.previewFingerprint(), "finance");
    var reader = new FinanceFactorPriceReader(batches, prices, identities, sources);
    assertThat(reader.read(next, Condition.NON_TENDER, null).required("AVG|304/2B/1219").price()).isEqualByComparingTo("5.1234567890123456");
    assertThat(reader.read(board, Condition.NON_TENDER, null).required("AVG|304/2B/1219").price()).isEqualByComparingTo("20");
    assertThat(reader.read(next, Condition.TENDER, null).required("AVG|304/2B/1219").price())
        .isEqualByComparingTo(reader.read(board, Condition.TENDER, null).required("AVG|304/2B/1219").price());
    assertThatThrownBy(() -> service.preview(file.bytes(), file.fileName(), commercial)).hasMessageContaining("不能混用");
  }

  @Test
  void monthlyExportUsesLatestAdjustedCopperRatherThanOriginalWorkbookValues() throws Exception {
    publishCommercial(FinanceFactorWorkbookParserTest.fixture("commercial"));
    var cu = factor("210", "CU_90000", Condition.GENERAL, "AVG|1#CU", null);
    var adjustment = new FinanceFactorCuAdjustmentService(prices, batches, identities, sources, service);
    var checked = adjustment.preview(cu.getId(), new java.math.BigDecimal("100000"), "月末财务调整", "COMMERCIAL");
    adjustment.publish(cu.getId(), new java.math.BigDecimal("100000"), "月末财务调整", "COMMERCIAL", checked.previewFingerprint(), "finance");
    var file = monthlyExport("COMMERCIAL", "210", "2026-11");
    assertThat(file.revision()).isEqualTo(2);
    var parsed = new FinanceFactorWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()))
        .parse(file.bytes(), file.fileName(), "210", null);
    assertThat(parsed.problems()).isEmpty();assertThat(parsed.schemes()).hasSize(4);
    assertThat(parsed.values().stream().filter(v -> v.canonicalKey().equals("AVG|1#CU") && v.schemeCode().equals("CU_90000")).findFirst().orElseThrow().price()).isEqualByComparingTo("100");
    assertThat(prices.selectById(cu.getId()).getPrice()).isEqualByComparingTo("90");
  }

  @Test
  void monthlyExportIsExactPreviousMonthAndNeverCrossesOrganizationBusinessUnitOrYear() throws Exception {
    byte[] original = FinanceFactorWorkbookParserTest.fixture("commercial");
    publishCommercial(original);publishForMonth(original, "2026-12");
    assertThat(monthlyExport("COMMERCIAL", "210", "2027-01").sourceMonth()).isEqualTo("2026-12");
    assertThat(monthlyExport("COMMERCIAL", "210", "2026-11").sourceMonth()).isEqualTo("2026-10");
    assertThatThrownBy(() -> monthlyExport("COMMERCIAL", "210", "2026-12")).hasMessageContaining("2026-11", "没有已发布");
    assertThatThrownBy(() -> monthlyExport("COMMERCIAL", "220", "2026-11")).hasMessageContaining("板换", "没有已发布");
    assertThatThrownBy(() -> monthlyExport("HOUSEHOLD", "210", "2026-11")).hasMessageContaining("没有已发布");
  }

  private FinanceFactorWorkbookExportService.ExportFile monthlyExport(String businessUnit, String organization, String targetMonth) {
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return transaction.execute(status -> new FinanceFactorWorkbookExportService(batches, prices, identities, sources)
        .previousMonth(new FinanceFactorImportContext(businessUnit, organization, targetMonth, null, null)));
  }

  private static byte[] changeExportedPrice(byte[] bytes, com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value value, String price) throws Exception {
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes)); var output = new ByteArrayOutputStream()) {
      book.getSheetAt(0).getRow(value.rowNumber() - 1).getCell(value.columnNumber() - 1).setCellValue(price);
      book.write(output);return output.toByteArray();
    }
  }

  private FinanceFactorPublicationService.Publication publishForMonth(byte[] file, String month) {
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", month, null, "CU_90000");
    var preview = service.preview(file, "commercial.xlsx", context);
    return service.publish(
        file, "commercial.xlsx", context, preview.previewFingerprint(), "finance");
  }

  private FinanceFactorPublicationService.Publication publishCommercial(byte[] file) {
    var preview = service.preview(file, "commercial.xlsx", commercial);
    return service.publish(
        file, "commercial.xlsx", commercial, preview.previewFingerprint(), "finance");
  }

  private static byte[] changedPcu(double price) throws Exception {
    try (var workbook =
            WorkbookFactory.create(
                new ByteArrayInputStream(FinanceFactorWorkbookParserTest.fixture("commercial")));
        var output = new ByteArrayOutputStream()) {
      workbook.getSheetAt(0).getRow(13).getCell(4).setCellValue(price);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static FinanceFactorPublicationService service(FactorRowRefMapper sourceMapper) {
    var canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    return new FinanceFactorPublicationService(
        new FinanceFactorWorkbookParser(canonical),
        canonical,
        identities,
        batches,
        prices,
        sourceMapper,
        new ObjectMapper(),
        transactions,
        com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(
            effectiveSessions, new ObjectMapper().findAndRegisterModules()),
            com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(effectiveSessions, transactions, new ObjectMapper().findAndRegisterModules()),
            com.sanhua.marketingcost.service.settlement.SettlementEffectivePriceTestSupport.service(effectiveSessions, new ObjectMapper().findAndRegisterModules()));
  }

  private static FactorPriceScope scope(String org, String scheme, Condition condition) {
    return new FactorPriceScope("COMMERCIAL", org, "2026-10", scheme, condition);
  }

  private static FactorMonthlyPrice factor(
      String org, String scheme, Condition condition, String key, Integer revision) {
    return prices.findPublishedScheme(scope(org, scheme, condition), revision).stream()
        .filter(
            p -> identities.selectById(p.getFactorIdentityId()).getCanonicalFactorKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static void assertCounts(
      int identityCount, int batchCount, int priceCount, int sourceCount) throws Exception {
    assertThat(count("SELECT COUNT(*) FROM lp_factor_identity")).isEqualTo(identityCount);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_upload_batch")).isEqualTo(batchCount);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_monthly_price")).isEqualTo(priceCount);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_row_ref")).isEqualTo(sourceCount);
  }

  private static int count(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private static void script(String path) throws Exception {
    var result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot finance_factor < \"$1\"",
            "migration",
            path);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }
}
