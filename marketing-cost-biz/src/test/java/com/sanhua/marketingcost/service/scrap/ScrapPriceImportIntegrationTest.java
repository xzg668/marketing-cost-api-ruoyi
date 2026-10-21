package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.controller.ScrapPriceController;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.mapping.Environment;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 原文件、生产Mapper/服务/Controller与真实MySQL，写入严格限制在Testcontainers。 */
@Tag("integration")
class ScrapPriceImportIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("scrap_import_test")
          .withUsername("root")
          .withPassword("test-only-password");
  static ScrapPriceMapper prices;
  static SqlSessionTemplate sql;
  static JdbcTemplate jdbc;
  static DataSourceTransactionManager transactions;
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final ScrapPriceWorkbookParser parser = new ScrapPriceWorkbookParser(json);
  final ScrapPriceRuleCodec rules = new ScrapPriceRuleCodec(json);
  ScrapPriceImportService service;
  ScrapPriceQueryService query;
  MockMvc api;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    for (String script :
        List.of(
            "prc01_pre_migration_schema.sql",
            "V308__original_price_version_contract.sql",
            "V309__finance_factor_publication_note.sql",
            "V326__original_workbook_source.sql")) {
      MYSQL.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/" + script);
      var result =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot scrap_import_test < \"$1\"",
              "migration",
              "/tmp/" + script);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V330__scrap_price_monthly.sql"), "/tmp/scrap.sql");
    var migration =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot scrap_import_test < /tmp/scrap.sql");
    assertThat(migration.getExitCode()).as(migration.getStderr()).isZero();
    var data =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    transactions = new DataSourceTransactionManager(data);
    jdbc = new JdbcTemplate(data);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(
        new Environment("scrap-import", new SpringManagedTransactionFactory(), data));
    var pagination = new MybatisPlusInterceptor();
    pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    config.addInterceptor(pagination);
    config.addMapper(ScrapPriceMapper.class);
    config.addMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class);
    sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    prices = sql.getMapper(ScrapPriceMapper.class);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void prepare() {
    jdbc.update("DELETE FROM lp_scrap_price WHERE source_rule_id IS NOT NULL");
    jdbc.update("DELETE FROM lp_scrap_price");
    jdbc.update("DELETE FROM lp_factor_upload_batch");
    service =
        new ScrapPriceImportService(
            parser,
            prices,
            rules,
            transactions,
            mock(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),
            mock(ScrapPriceGenerationService.class),
            sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class));
    query = new ScrapPriceQueryService(prices);
    api =
        MockMvcBuilders.standaloneSetup(new ScrapPriceController(service, query))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
    authenticate("COMMERCIAL");
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void originalAndRepeatedImportReconcileEveryStoredRuleWithoutChangingIdsOrMetadata()
      throws Exception {
    byte[] file = ScrapPriceWorkbookParserTest.original();
    var first = confirm(file, "2026-10");
    assertThat(first.inserted()).isEqualTo(12);
    assertThat(first.updated()).isZero();
    String before = snapshot();
    var repeated = confirm(file, "2026-10");
    assertThat(repeated.inserted()).isZero();
    assertThat(repeated.updated()).isZero();
    assertThat(repeated.unchanged()).isEqualTo(12);
    assertThat(repeated.importBatchNo()).isNull();
    assertThat(snapshot()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_factor_upload_batch", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT source_workbook FROM lp_factor_upload_batch", byte[].class))
        .isEqualTo(file);
    assertThat(jdbc.queryForObject("SELECT import_type FROM lp_factor_upload_batch", String.class))
        .isEqualTo("SCRAP_PRICE");
    assertThat(
            jdbc.queryForObject(
                "SELECT publication_revision FROM lp_factor_upload_batch", Integer.class))
        .isNull();
    var stored = prices.selectList(null);
    assertThat(stored).hasSize(12);
    for (var source : parser.parse(file, "original.xlsx").rows()) {
      var actual =
          stored.stream()
              .filter(p -> p.getMaterialCode().equals(source.rule().materialCode()))
              .findFirst()
              .orElseThrow();
      assertThat(rules.read(actual)).isEqualTo(source.rule());
      assertThat(actual.getSourceJson()).isEqualTo(source.evidenceJson());
      assertThat(actual.getSourceRow()).isEqualTo(source.row());
      assertThat(actual.getRuleSourceMonth()).isEqualTo("2026-10");
      assertThat(actual.getSupplierCode()).isNull();
      assertThat(actual.getSourceFileSha256())
          .isEqualTo("8fd1c6ebfe206a2540ad6e2b5bef50ee4c4aef05b29eddb72a0921ccfca6471b");
    }
  }

  @Test
  void changingOneFixedAmountUpdatesOnlyThatIdAndKeepsTheOtherEleven() throws Exception {
    confirm(ScrapPriceWorkbookParserTest.original(), "2026-10");
    Long id = row("301990444").getId();
    var before = rowsExcept(id);
    byte[] changed =
        ScrapPriceWorkbookParserTest.change(s -> s.getRow(1).getCell(5).setCellValue(8.125));
    var result = confirm(changed, "2026-10");
    assertThat(result.inserted()).isZero();
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.unchanged()).isEqualTo(11);
    assertThat(row("301990444").getId()).isEqualTo(id);
    assertThat(row("301990444").getFixedPrice()).isEqualByComparingTo("8.125");
    assertThat(row("301990444").getRuleRevision()).isEqualTo(2);
    assertThat(rowsExcept(id)).isEqualTo(before);
  }

  @Test
  void changingOneActualFormulaUpdatesOneRowRegardlessOfDescription() throws Exception {
    confirm(ScrapPriceWorkbookParserTest.original(), "2026-10");
    var original = row("301990135");
    var before = rowsExcept(original.getId());
    var result =
        confirm(
            ScrapPriceWorkbookParserTest.change(
                s -> s.getRow(6).getCell(5).setCellFormula("($M$1*0.98)/1.13")),
            "2026-10");
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.unchanged()).isEqualTo(11);
    assertThat(row("301990135").getExpression()).contains("0.98");
    assertThat(row("301990135").getId()).isEqualTo(original.getId());
    assertThat(rowsExcept(original.getId())).isEqualTo(before);
  }

  @Test
  void partialFileRetainsMissingRowsAndOtherMonthsRemainSeparate() throws Exception {
    byte[] original = ScrapPriceWorkbookParserTest.original();
    confirm(original, "2026-10");
    byte[] partial =
        ScrapPriceWorkbookParserTest.change(
            s -> {
              for (int r = 2; r <= 12; r++) s.removeRow(s.getRow(r));
              s.getRow(1).getCell(5).setCellValue(9);
            });
    assertThat(confirm(partial, "2026-10").updated()).isEqualTo(1);
    assertThat(count()).isEqualTo(12);
    assertThat(confirm(original, "2026-11").inserted()).isEqualTo(12);
    assertThat(count()).isEqualTo(24);
    assertThat(query.list("COMMERCIAL", "2026-09", null, null, null, 1, 20).getTotal()).isZero();
  }

  @Test
  void duplicateRowsMergeButAnyBadRowBlocksTheWholeFile() throws Exception {
    var duplicate =
        ScrapPriceWorkbookParserTest.change(s -> s.copyRows(1, 1, 13, new CellCopyPolicy()));
    var result = confirm(duplicate, "2026-10");
    assertThat(result.duplicates()).isEqualTo(1);
    assertThat(count()).isEqualTo(12);
    String before = snapshot();
    for (byte[] invalid :
        List.of(
            ScrapPriceWorkbookParserTest.change(
                s -> {
                  s.getRow(1).getCell(5).setCellValue(9);
                  s.getRow(12).getCell(6).setCellValue("未知类型");
                }),
            ScrapPriceWorkbookParserTest.change(
                s -> {
                  s.copyRows(1, 1, 13, new CellCopyPolicy());
                  s.getRow(13).getCell(5).setCellValue(9);
                }))) {
      var preview = service.preview(invalid, "original.xlsx", "COMMERCIAL", "2026-10");
      assertThat(preview.canConfirm()).isFalse();
      assertThatThrownBy(
              () ->
                  service.confirm(
                      invalid,
                      "original.xlsx",
                      "COMMERCIAL",
                      "2026-10",
                      preview.previewFingerprint(),
                      "test"))
          .hasMessageContaining("整批未导入");
      assertThat(snapshot()).isEqualTo(before);
    }
  }

  @Test
  void stalePreviewCannotOverwriteAnEditOrUseADifferentFileOrMonth() throws Exception {
    byte[] original = ScrapPriceWorkbookParserTest.original();
    confirm(original, "2026-10");
    var preview = service.preview(original, "original.xlsx", "COMMERCIAL", "2026-10");
    var row = row("301990444");
    service.edit(
        row.getId(),
        "COMMERCIAL",
        new ScrapPriceImportService.Edit(row.getRowVersion(), fixed(row, "10", null, null)),
        "editor");
    String before = snapshot();
    assertThatThrownBy(
            () ->
                service.confirm(
                    original,
                    "original.xlsx",
                    "COMMERCIAL",
                    "2026-10",
                    preview.previewFingerprint(),
                    "test"))
        .hasMessageContaining("重新预检");
    var fresh = service.preview(original, "original.xlsx", "COMMERCIAL", "2026-10");
    assertThatThrownBy(
            () ->
                service.confirm(
                    original,
                    "original.xlsx",
                    "COMMERCIAL",
                    "2026-11",
                    fresh.previewFingerprint(),
                    "test"))
        .hasMessageContaining("重新预检");
    byte[] changed =
        ScrapPriceWorkbookParserTest.change(s -> s.getRow(1).getCell(5).setCellValue(11));
    assertThatThrownBy(
            () ->
                service.confirm(
                    changed,
                    "original.xlsx",
                    "COMMERCIAL",
                    "2026-10",
                    fresh.previewFingerprint(),
                    "test"))
        .hasMessageContaining("重新预检");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void failureAfterFirstInsertRollsBackEveryRow() throws Exception {
    byte[] file = ScrapPriceWorkbookParserTest.original();
    ScrapPriceMapper failing = mock(ScrapPriceMapper.class, delegatesTo(prices));
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            call -> {
              if (calls.incrementAndGet() == 2)
                throw new IllegalStateException("injected write failure");
              return prices.insert(call.getArgument(0, ScrapPrice.class));
            })
        .when(failing)
        .insert(any(ScrapPrice.class));
    var failingService =
        new ScrapPriceImportService(
            parser,
            failing,
            rules,
            transactions,
            mock(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),
            mock(ScrapPriceGenerationService.class),
            sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class));
    var preview = failingService.preview(file, "original.xlsx", "COMMERCIAL", "2026-10");
    assertThatThrownBy(
            () ->
                failingService.confirm(
                    file,
                    "original.xlsx",
                    "COMMERCIAL",
                    "2026-10",
                    preview.previewFingerprint(),
                    "test"))
        .hasMessageContaining("injected write failure");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(count()).isZero();
  }

  @Test
  void concurrentConfirmationHasOneCompleteWinnerAndNoDuplicates() throws Exception {
    byte[] file = ScrapPriceWorkbookParserTest.original();
    var preview = service.preview(file, "original.xlsx", "COMMERCIAL", "2026-10");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> confirm =
          () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
              service.confirm(
                  file,
                  "original.xlsx",
                  "COMMERCIAL",
                  "2026-10",
                  preview.previewFingerprint(),
                  "test");
              return true;
            } catch (IllegalArgumentException stale) {
              assertThat(stale).hasMessageContaining("重新预检");
              return false;
            }
          };
      var first = executor.submit(confirm);
      var second = executor.submit(confirm);
      start.countDown();
      assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(count()).isEqualTo(12);
    assertThat(prices.selectList(null))
        .allSatisfy(row -> assertThat(row.getRuleRevision()).isEqualTo(1));
  }

  @Test
  void sourceWorkbookAndPricesRollBackTogetherIfTheSynchronousRefreshFails() throws Exception {
    var generation = mock(ScrapPriceGenerationService.class);
    when(generation.refreshInTransaction(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("calculation rejected"));
    var importer =
        new ScrapPriceImportService(
            parser,
            prices,
            rules,
            transactions,
            mock(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),
            generation,
            sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class));
    byte[] file = ScrapPriceWorkbookParserTest.original();
    var preview = importer.preview(file, "original.xlsx", "COMMERCIAL", "2026-10");
    assertThatThrownBy(
            () ->
                importer.confirm(
                    file,
                    "original.xlsx",
                    "COMMERCIAL",
                    "2026-10",
                    preview.previewFingerprint(),
                    "test"))
        .hasMessageContaining("calculation rejected");
    assertThat(count()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_factor_upload_batch", Integer.class))
        .isZero();
  }

  @Test
  void editKeepsIdSupportsTypeSwitchAndRejectsStaleOrConflictingSupplier() throws Exception {
    confirm(ScrapPriceWorkbookParserTest.original(), "2026-10");
    var original = row("301990444");
    var linked =
        new ScrapPriceRule(
            original.getMaterialCode(),
            original.getMaterialName(),
            "",
            "KG",
            "S1",
            "供方一",
            "LINKED",
            null,
            "[Cu]*0.9/1.13",
            "[Cu]*0.9/1.13",
            Map.of("Cu", "AVG|1#CU"));
    var changed =
        service.edit(
            original.getId(),
            "COMMERCIAL",
            new ScrapPriceImportService.Edit(original.getRowVersion(), linked),
            "test");
    assertThat(changed.getFixedPrice()).isNull();
    assertThat(changed.getOriginType()).isEqualTo("MANUAL");
    assertThat(changed.getSupplierCode()).isEqualTo("S1");
    assertThatThrownBy(
            () ->
                service.edit(
                    original.getId(),
                    "COMMERCIAL",
                    new ScrapPriceImportService.Edit(original.getRowVersion(), linked),
                    "test"))
        .hasMessageContaining("已被修改");
    confirm(ScrapPriceWorkbookParserTest.original(), "2026-10"); // 公共供应商独立保留。
    assertThat(count()).isEqualTo(13);
    assertThatThrownBy(
            () ->
                service.edit(
                    changed.getId(),
                    "COMMERCIAL",
                    new ScrapPriceImportService.Edit(
                        changed.getRowVersion(), fixed(changed, "2", null, null)),
                    "test"))
        .hasMessageContaining("已存在");
    var back =
        service.edit(
            changed.getId(),
            "COMMERCIAL",
            new ScrapPriceImportService.Edit(
                changed.getRowVersion(), fixed(changed, "2", "S1", "新名称")),
            "test");
    assertThat(back.getExpression()).isNull();
    assertThat(back.getSourceFormula()).isNull();
    assertThat(back.getUnitPrice()).isEqualByComparingTo("2");
    assertThat(back.getSupplierName()).isEqualTo("新名称");
    assertThat(back.getId()).isEqualTo(original.getId());
  }

  @Test
  void actualMultipartApiPreviewAndGetAreReadOnlyAndStoredDataMatchesApi() throws Exception {
    byte[] file = ScrapPriceWorkbookParserTest.original();
    String before = snapshot();
    var preview =
        api.perform(
                multipart("/api/v1/scrap-prices/import/preview")
                    .file(upload(file))
                    .param("priceMonth", "2026-10")
                    .param("businessUnitType", "HOUSEHOLD"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.inserted").value(12))
            .andExpect(jsonPath("$.data.rows[9].rule.fixedPrice").value("2.6283185840708"))
            .andReturn();
    assertThat(snapshot()).isEqualTo(before);
    String fingerprint =
        json.readTree(preview.getResponse().getContentAsString())
            .path("data")
            .path("previewFingerprint")
            .asText();
    api.perform(
            multipart("/api/v1/scrap-prices/import")
                .file(upload(file))
                .param("priceMonth", "2026-10")
                .param("previewFingerprint", fingerprint)
                .principal(() -> "test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.inserted").value(12));
    before = snapshot();
    api.perform(get("/api/v1/scrap-prices").param("priceMonth", "2026-10").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(12))
        .andExpect(jsonPath("$.data.records.length()").value(5));
    api.perform(
            get("/api/v1/scrap-prices").param("priceMonth", "2026-10").param("priceType", "FIXED"))
        .andExpect(jsonPath("$.data.total").value(6));
    var precise = row("301990752");
    var detail =
        api.perform(get("/api/v1/scrap-prices/" + precise.getId()))
            .andExpect(jsonPath("$.data.businessUnitType").value("COMMERCIAL"))
            .andReturn();
    var value =
        json.readTree(detail.getResponse().getContentAsString()).path("data").path("fixedPrice");
    assertThat(value.isTextual()).isTrue();
    assertThat(new BigDecimal(value.asText())).isEqualByComparingTo("2.6283185840708");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void businessUnitCannotReadOrModifyAnotherUnitsPrice() throws Exception {
    confirm(ScrapPriceWorkbookParserTest.original(), "2026-10");
    var row = row("301990444");
    assertThat(query.list("HOUSEHOLD", "2026-10", null, null, null, 1, 20).getTotal()).isZero();
    assertThatThrownBy(() -> query.detail("HOUSEHOLD", row.getId())).hasMessageContaining("无权访问");
    assertThatThrownBy(
            () ->
                service.edit(
                    row.getId(),
                    "HOUSEHOLD",
                    new ScrapPriceImportService.Edit(
                        row.getRowVersion(), fixed(row, "2", null, null)),
                    "test"))
        .hasMessageContaining("无权访问");
    assertThat(count()).isEqualTo(12);
  }

  private ScrapPriceImportService.ImportResult confirm(byte[] file, String month) {
    var preview = service.preview(file, "original.xlsx", "COMMERCIAL", month);
    assertThat(preview.errors()).isEmpty();
    return service.confirm(
        file, "original.xlsx", "COMMERCIAL", month, preview.previewFingerprint(), "test");
  }

  private ScrapPrice row(String code) {
    return prices.selectList(null).stream()
        .filter(r -> r.getMaterialCode().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private long count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price", Long.class);
  }

  private String snapshot() throws Exception {
    return json.writeValueAsString(jdbc.queryForList("SELECT * FROM lp_scrap_price ORDER BY id"));
  }

  private String rowsExcept(Long id) throws Exception {
    return json.writeValueAsString(
        jdbc.queryForList("SELECT * FROM lp_scrap_price WHERE id<>? ORDER BY id", id));
  }

  private ScrapPriceRule fixed(ScrapPrice row, String amount, String supplier, String name) {
    return new ScrapPriceRule(
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getSpecification(),
        "KG",
        supplier,
        name,
        "FIXED",
        new BigDecimal(amount),
        null,
        null,
        Map.of());
  }

  private MockMultipartFile upload(byte[] file) {
    return new MockMultipartFile(
        "file",
        "original.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        file);
  }

  private void authenticate(String unit) {
    var auth = new UsernamePasswordAuthenticationToken("test", "", List.of());
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, unit));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
