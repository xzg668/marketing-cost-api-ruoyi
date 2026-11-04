package com.sanhua.marketingcost.service.pricing.folder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.io.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/** 每次测试使用独立容器中的空库；不连接开发库或既有验收库。 */
@Tag("integration")
class MonthlyPriceFolderIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withCommand("--max-allowed-packet=64M").withDatabaseName("monthly_folder")
      .withUsername("root").withPassword("folder-test-only");
  private static final String[] SCRIPTS = {"prc01_pre_migration_schema.sql",
      "V308__original_price_version_contract.sql", "V309__finance_factor_publication_note.sql",
      "V311__original_linked_expression_capacity.sql", "V325__finance_effective_price.sql",
      "V326__original_workbook_source.sql", "V330__scrap_price_monthly.sql",
      "V332__settlement_price_versions.sql", "V341__settlement_effective_price.sql",
      "V356__inquiry_price_sources.sql", "V359__monthly_price_folder_import.sql"};
  private static SqlSessionTemplate sql;
  private static DataSourceTransactionManager transactions;
  private static JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final FactorCanonicalKeyServiceImpl canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
  private MonthlyPriceFolderService folders;
  private OriginalLinkedImportService linked;
  private OriginalRangeImportService ranges;
  private FinanceFactorPublicationService publications;
  private FinanceEffectivePriceService effective;
  private FinanceFactorPriceReader finance;
  private final OriginalLinkedRuleSnapshot linkedSnapshots = new OriginalLinkedRuleSnapshot(json);
  private final OriginalRangeRuleSnapshot rangeSnapshots = new OriginalRangeRuleSnapshot(json);
  private final OriginalLinkedRuleCalculator linkedCalculator = new OriginalLinkedRuleCalculator();
  private final OriginalRangeRuleCalculator rangeCalculator = new OriginalRangeRuleCalculator();

  @BeforeAll static void start() {
    MYSQL.start();
    for (var name : SCRIPTS) MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/" + name), "/tmp/" + name);
    var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    transactions = new DataSourceTransactionManager(data); jdbc = new JdbcTemplate(data);
    var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(new Environment("folder", new SpringManagedTransactionFactory(), data));
    for (var mapper : List.of(FactorIdentityMapper.class, FactorUploadBatchMapper.class, FactorMonthlyPriceMapper.class,
        FactorRowRefMapper.class, FinanceEffectivePriceMapper.class, ScrapPriceMapper.class, PriceLinkedItemMapper.class,
        PriceRangeFactorRuleMapper.class, PriceRangeItemMapper.class, MonthlyPriceImportMapper.class, MonthlyPriceImportFileMapper.class))
      config.addMapper(mapper);
    sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
  }
  @AfterAll static void stop() { MYSQL.stop(); }
  @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }
  @BeforeEach void prepare() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE monthly_folder"); statement.execute("CREATE DATABASE monthly_folder CHARACTER SET utf8mb4");
    }
    for (String name : SCRIPTS) {
      var result = MYSQL.execInContainer("sh", "-c", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot monthly_folder < \"$1\"", "migration", "/tmp/" + name);
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    login("COMMERCIAL");
    finance = new FinanceFactorPriceReader(mapper(FactorUploadBatchMapper.class), mapper(FactorMonthlyPriceMapper.class),
        mapper(FactorIdentityMapper.class), mapper(FactorRowRefMapper.class));
    effective = FinanceEffectivePriceTestSupport.service(sql, json);
    publications = new FinanceFactorPublicationService(new FinanceFactorWorkbookParser(canonical), canonical,
        mapper(FactorIdentityMapper.class), mapper(FactorUploadBatchMapper.class), mapper(FactorMonthlyPriceMapper.class),
        mapper(FactorRowRefMapper.class), json, transactions, effective,
        com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(sql, transactions, json),
        com.sanhua.marketingcost.service.settlement.SettlementEffectivePriceTestSupport.service(sql, json),
        com.sanhua.marketingcost.service.pricing.inquiry.InquiryPriceTestSupport.refresh(sql, transactions, json));
    linked = new OriginalLinkedImportService(new OriginalLinkedWorkbookParser(canonical), linkedCalculator, linkedSnapshots,
        finance, mapper(FactorIdentityMapper.class), mapper(FactorUploadBatchMapper.class), mapper(PriceLinkedItemMapper.class), json, transactions, effective);
    ranges = new OriginalRangeImportService(new OriginalRangeWorkbookParser(), rangeCalculator, rangeSnapshots,
        finance, mapper(FactorIdentityMapper.class), mapper(FactorUploadBatchMapper.class), mapper(PriceRangeFactorRuleMapper.class),
        mapper(PriceRangeItemMapper.class), json, transactions, effective);
    folders = folderService(effective);
    publishFinance("210", "2026-10", "CU_90000");
  }

  @Test void brokenFileDoesNotBlockGoodFilesAndCompletionCarriesOriginalRulesAtCurrentBase() throws Exception {
    publishLinked(workbook(row("OLD", "因素!E2*0.1+2")), "2026-10");
    publishRange(rangeWorkbook("OLD_RANGE", 1), "2026-10");
    var beforeLinked = jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE pricing_month='2026-10'");
    var beforeRange = jdbc.queryForList("SELECT * FROM lp_price_range_factor_rule WHERE pricing_month='2026-10'");
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("good.xlsx", "broken.xlsx", "note.txt");
    upload(report, "good.xlsx", workbook(row("NEW", "因素!E2*0.2+3"), row("NO_PRICE", null)));
    upload(report, "broken.xlsx", new byte[]{1,2,3});
    upload(report, "note.txt", "说明".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    report = process(report.id(), "good.xlsx");
    assertThat(report.summary().importedRules()).isOne();
    assertThat(report.summary().noPriceRows()).isOne();
    assertThat(report.files().stream().filter(f -> f.path().equals("broken.xlsx")).findFirst().orElseThrow().rowCount()).isEqualTo(-1);
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11' AND rule_status='PUBLISHED'")).isZero();
    assertThat(count("lp_finance_effective_price", "original_month='2026-11'")).isZero();
    var blocked = report;
    assertThatThrownBy(() -> folders.complete(blocked.id(), blocked.revision())).hasMessageContaining("报错");
    var replacement = upload(report, "broken.xlsx", rangeWorkbook("NEW_RANGE", 10));
    assertThat(replacement.fileId()).isNotEqualTo(file(report, "broken.xlsx").id());
    report = process(report.id(), "broken.xlsx");
    report = folders.complete(report.id(), report.revision());
    assertThat(report.status()).isEqualTo("COMPLETE");
    assertThat(json.valueToTree(report.completion()).path("carried")).hasSize(2);
    assertPrice("NEW", "2026-11", "23"); assertPrice("OLD", "2026-11", "12");
    assertPrice("OLD_RANGE", "2026-11", "3"); assertPrice("NEW_RANGE", "2026-11", "12");
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE pricing_month='2026-10'")).isEqualTo(beforeLinked);
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_range_factor_rule WHERE pricing_month='2026-10'")).isEqualTo(beforeRange);
    int linkedCount = count("lp_price_linked_item", "1=1");
    assertThat(folders.complete(report.id(), report.revision())).isEqualTo(report);
    assertThat(count("lp_price_linked_item", "1=1")).isEqualTo(linkedCount);
    var frozenReceipt = json.writeValueAsString(report);
    publishLinked(workbook(row("NEW", "因素!E2*0.2+9")), "2026-11");
    assertThat(json.writeValueAsString(folders.report(report.id()))).isEqualTo(frozenReceipt);
  }

  @Test void differentSourcesAreAllPublishedAndSingleFileImportStillWorks() throws Exception {
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("a.xlsx", "b.xlsx", "duplicate.xlsx");
    var first = workbook(row("SAME", "因素!E2*1.05"), row("ONLY_A", "因素!E2+1"));
    upload(report, "a.xlsx", first);
    upload(report, "duplicate.xlsx", first);
    upload(report, "b.xlsx", workbook(row("SAME", "因素!E2*1.092")));
    report = process(report.id(), "a.xlsx");
    assertThat(report.summary().alternativeRuleCount()).isOne();
    assertThat(report.summary().importedRules()).isEqualTo(2);
    assertThat(report.summary().duplicateRows()).isEqualTo(2);
    report = process(report.id(), "b.xlsx");
    report = folders.complete(report.id(), report.revision());
    assertPrice("SAME", "2026-11", "109.2");
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11' AND rule_status='PUBLISHED'")).isEqualTo(3);
    publishLinked(workbook(row("SAME", "因素!E2*1.1")), "2026-11");
    publishRange(rangeWorkbook("SINGLE_RANGE", 5), "2026-11");
    assertPrice("SAME", "2026-11", "110"); assertPrice("SINGLE_RANGE", "2026-11", "7");
  }

  @Test void targetMonthBaseRequiredAndRepairingFinancialInputsReusesSavedFiles() throws Exception {
    var report = start("new.xlsx");
    upload(report, "new.xlsx", workbook(row("NEW", "因素!E2+2")));
    report = process(report.id(), "new.xlsx");
    assertThat(report.summary().errorRows()).isPositive();
    assertThat(count("lp_price_linked_item", "1=1")).isZero();
    publishFinance("210", "2026-11", "CU_100000");
    report = process(report.id(), "new.xlsx");
    assertThat(report.summary().errorRows()).isZero();
    folders.complete(report.id(), report.revision()); assertPrice("NEW", "2026-11", "102");
  }

  @Test void finalGenerationFailureRollsBackActivationAndCarryTogether() throws Exception {
    publishLinked(workbook(row("OLD", "因素!E2+1")), "2026-10");
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("new.xlsx"); upload(report, "new.xlsx", workbook(row("NEW", "因素!E2+2")));
    report = process(report.id(), "new.xlsx");
    var failing = mock(FinanceEffectivePriceService.class, org.mockito.AdditionalAnswers.delegatesTo(effective));
    doThrow(new IllegalArgumentException("测试：有效价保存失败")).when(failing).generateScope("COMMERCIAL", "210");
    var failedService = folderService(failing); var ready = report;
    assertThatThrownBy(() -> failedService.complete(ready.id(), ready.revision())).hasMessageContaining("有效价保存失败");
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11' AND rule_status='PUBLISHED'")).isZero();
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11'")).isOne();
    assertThat(folders.report(report.id()).revision()).isEqualTo(report.revision());
    folders.complete(report.id(), report.revision()); assertPrice("OLD", "2026-11", "101");
  }

  @Test void correctedFileRetainsOriginalAndDoesNotReuseDiscardedRuleRevision() throws Exception {
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("new.xlsx"); var firstBytes = workbook(row("NEW", "因素!E2+1"));
    var first = upload(report, "new.xlsx", firstBytes); report = process(report.id(), "new.xlsx");
    var second = upload(report, "new.xlsx", workbook(row("NEW", "因素!E2+2")));
    assertThat(second.fileId()).isNotEqualTo(first.fileId());
    assertThat(folders.download(report.id(), first.fileId()).bytes()).isEqualTo(firstBytes);
    report = process(report.id(), "new.xlsx");
    // 已替换文件的暂存行只用于审计，不应成为当前文件的冲突候选。
    assertThat(report.summary().alternativeRuleCount()).isZero();
    folders.complete(report.id(), report.revision()); assertPrice("NEW", "2026-11", "102");
    assertThat(count("lp_price_linked_item", "rule_status='NOT_SELECTED'")).isOne();
    publishLinked(workbook(row("NEW", "因素!E2+3")), "2026-11"); assertPrice("NEW", "2026-11", "103");
  }

  @Test void monthBoundaryDoesNotResurrectOlderMaterialAndExplicitNoPriceIsReported() throws Exception {
    publishFinance("210", "2026-09", "CU_90000");
    publishLinked(workbook(row("SEPTEMBER_ONLY", "因素!E2+1")), "2026-09");
    publishLinked(workbook(row("OCTOBER_ONLY", "因素!E2+2"), row("NOW_BLANK", "因素!E2+3")), "2026-10");
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("new.xlsx"); upload(report, "new.xlsx", workbook(row("NEW", "因素!E2+4"), row("NOW_BLANK", null)));
    report = process(report.id(), "new.xlsx"); report = folders.complete(report.id(), report.revision());
    assertThat(json.valueToTree(report.completion()).path("carried")).hasSize(1);
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11' AND material_code IN ('SEPTEMBER_ONLY','NOW_BLANK')")).isZero();
    assertThat(report.summary().sourceRows()).isEqualTo(2); assertThat(report.summary().noPriceRows()).isOne();
  }

  @Test void organizationTyposAreCorrectedBut220RemainsIndependent() throws Exception {
    publishFinance("210", "2026-11", "CU_100000"); publishFinance("220", "2026-11", "BOARD");
    var report = start("mixed.xlsx");
    upload(report, "mixed.xlsx", workbook(new RowData("211", "COMMON", "SUP", "因素!E2"), new RowData("220", "BOARD", "SUP", "5+2")));
    report = process(report.id(), "mixed.xlsx");
    assertThat(report.summary().errorRows()).isZero();
    assertThat(report.files().getFirst().notices()).anyMatch(n -> n.code().equals("ORGANIZATION_CORRECTED"));
    folders.complete(report.id(), report.revision());
    assertThat(jdbc.queryForMap("SELECT material_code,org_code FROM lp_price_linked_item WHERE material_code='BOARD'").get("org_code")).isEqualTo("220");
    assertThat(jdbc.queryForMap("SELECT material_code,org_code FROM lp_price_linked_item WHERE material_code='COMMON'").get("org_code")).isEqualTo("210");
  }

  @Test void requestReplayReloadAndBusinessUnitIsolationPreserveOneBatch() throws Exception {
    var report = folders.start("2026-11", "repeat-request", List.of("a.xlsx"));
    assertThat(folders.start("2026-11", "repeat-request", List.of("a.xlsx")).id()).isEqualTo(report.id());
    assertThatThrownBy(() -> folders.start("2026-11", "another-request", List.of("a.xlsx"))).hasMessageContaining("未完成");
    var bytes = workbook(row("A", "因素!E2")); upload(report, "a.xlsx", bytes);
    var saved = folders.report(report.id()); upload(saved, "a.xlsx", bytes);
    assertThat(folders.report(report.id()).revision()).isEqualTo(saved.revision());
    folders.uploadFailed(report.id(), file(saved, "a.xlsx").id(), "响应丢失");
    assertThat(folders.report(report.id()).summary().errorRows()).isZero();
    login("HOUSEHOLD");
    assertThatThrownBy(() -> folders.report(report.id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> folders.download(report.id(), file(saved, "a.xlsx").id())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    login("COMMERCIAL"); assertThat(folderService(effective).latest("2026-11")).isEqualTo(folders.report(report.id()));
  }

  @Test void oneThousandCurrentRowsAndFiveHundredCarriedRulesUseOneNovemberBase() throws Exception {
    var old = new ArrayList<RowData>(); var next = new ArrayList<RowData>();
    for (int i = 0; i < 1500; i++) old.add(row("M" + i, "因素!E2*0.1+2"));
    for (int i = 0; i < 1000; i++) next.add(row("M" + i, "因素!E2*0.1+3"));
    publishLinked(workbook(old.toArray(RowData[]::new)), "2026-10");
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("current.xlsx"); upload(report, "current.xlsx", workbook(next.toArray(RowData[]::new)));
    report = process(report.id(), "current.xlsx");
    assertThat(report.summary().importedRules()).isEqualTo(1000);
    report = folders.complete(report.id(), report.revision());
    assertThat(json.valueToTree(report.completion()).path("carried")).hasSize(500);
    assertThat(count("lp_price_linked_item", "pricing_month='2026-11' AND rule_status='PUBLISHED'")).isEqualTo(1500);
    assertPrice("M0", "2026-11", "13"); assertPrice("M1499", "2026-11", "12");
  }

  @Test void bothLinkedFormulasSurviveCarryAndHighestChangesWithTheNextMonthBase() throws Exception {
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("first.xlsx", "second.xlsx");
    upload(report, "first.xlsx", workbook(row("CROSS", "因素!E2")));
    upload(report, "second.xlsx", workbook(row("CROSS", "因素!E2*0.5+55")));
    process(report.id(), "first.xlsx"); report = process(report.id(), "second.xlsx");
    assertThat(report.summary().alternativeRuleCount()).isOne();
    assertThat(report.summary().importedRules()).isEqualTo(2);
    folders.complete(report.id(), report.revision()); assertPrice("CROSS", "2026-11", "105");
    publishFinance("210", "2026-12", "CU_120000");
    report = folders.start("2026-12", UUID.randomUUID().toString(), List.of("本月无新增.txt"));
    upload(report, "本月无新增.txt", "无新增原稿，接续上一月规则".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    report = folders.report(report.id()); report = folders.complete(report.id(), report.revision());
    assertThat(json.valueToTree(report.completion()).path("carried")).hasSize(2);
    assertPrice("CROSS", "2026-12", "120");
    assertThat(count("lp_price_linked_item", "pricing_month='2026-12' AND rule_status='PUBLISHED'")).isEqualTo(2);
    assertPrice("CROSS", "2026-11", "105");
  }

  @Test void singleWorkbookCanPublishTwoRulesAndRangeHighestAlsoChangesAfterCarry() throws Exception {
    publishLinked(workbook(row("DUAL", "因素!E2"), row("DUAL", "因素!E2+2")), "2026-10");
    assertPrice("DUAL", "2026-10", "92");
    publishFinance("210", "2026-11", "CU_100000");
    var report = start("range-a.xlsx", "range-b.xlsx");
    upload(report, "range-a.xlsx", rangePrices("RANGE_CROSS", 10, 20, 30, 40));
    upload(report, "range-b.xlsx", rangePrices("RANGE_CROSS", 15, 16, 17, 18));
    process(report.id(), "range-a.xlsx"); report = process(report.id(), "range-b.xlsx");
    folders.complete(report.id(), report.revision()); assertPrice("RANGE_CROSS", "2026-11", "30");
    publishFinance("210", "2026-12", "CU_90000");
    report = folders.start("2026-12", UUID.randomUUID().toString(), List.of("说明.txt"));
    upload(report, "说明.txt", "本月沿用规则".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    report = folders.report(report.id()); report = folders.complete(report.id(), report.revision());
    assertThat(count("lp_price_range_factor_rule", "pricing_month='2026-12' AND rule_status='PUBLISHED'")).isEqualTo(2);
    assertPrice("RANGE_CROSS", "2026-12", "15");
  }

  private MonthlyPriceFolderService folderService(FinanceEffectivePriceService effectivePrices) {
    var signatures = new OriginalRuleSignature(json, rangeCalculator);
    var sources = new MonthlyPriceFolderSources(mapper(PriceLinkedItemMapper.class), mapper(PriceRangeFactorRuleMapper.class), linkedSnapshots, rangeSnapshots, signatures);
    var validation = new MonthlyPriceFolderFinance(finance, linkedCalculator, rangeCalculator);
    var parser = new MonthlyPriceFolderParser(new OriginalLinkedWorkbookParser(canonical), new OriginalRangeWorkbookParser(),
        mapper(FactorIdentityMapper.class), new LinkedApprovalImportContext(mock(MaterialMasterRawMapper.class)));
    var carry = new MonthlyPriceRuleCarryForward(sources, mapper(PriceLinkedItemMapper.class), mapper(PriceRangeFactorRuleMapper.class),
        mapper(PriceRangeItemMapper.class), linkedSnapshots, rangeSnapshots, validation, effectivePrices);
    return new MonthlyPriceFolderService(mapper(MonthlyPriceImportMapper.class), mapper(MonthlyPriceImportFileMapper.class),
        mapper(FactorIdentityMapper.class), parser, new MonthlyPriceFolderCodec(json), new MonthlyPriceFolderPlan(), sources,
        signatures, validation, linked, ranges, carry, new MonthlyPriceFolderPublication(mapper(PriceLinkedItemMapper.class),
        mapper(PriceRangeFactorRuleMapper.class), mapper(PriceRangeItemMapper.class), mapper(FactorUploadBatchMapper.class)), transactions);
  }
  private static <T> T mapper(Class<T> type) { return sql.getMapper(type); }
  private static void login(String unit) {
    var auth = new UsernamePasswordAuthenticationToken("folder-tester", null, List.of());
    auth.setDetails(Map.of("businessUnitType", unit)); SecurityContextHolder.getContext().setAuthentication(auth);
  }
  private Report start(String... paths) { return folders.start("2026-11", UUID.randomUUID().toString(), List.of(paths)); }
  private FileView file(Report report, String path) { return report.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow(); }
  private MonthlyPriceFolderService.UploadResult upload(Report report, String path, byte[] bytes) {
    var current = folders.report(report.id()); return folders.upload(report.id(), file(current, path).id(), bytes);
  }
  private Report process(long id, String path) {
    var current = folders.report(id); return folders.process(id, file(current, path).id(), current.revision());
  }
  private static int count(String table, String where) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class); }
  private void assertPrice(String code, String month, String expected) {
    var signatures = new OriginalRuleSignature(json, rangeCalculator);
    var sources = new MonthlyPriceFolderSources(mapper(PriceLinkedItemMapper.class), mapper(PriceRangeFactorRuleMapper.class), linkedSnapshots, rangeSnapshots, signatures).read("COMMERCIAL", month, false);
    var linkedRows = sources.linked().values().stream().filter(row -> code.equals(row.getMaterialCode())).toList();
    var rangeRows = sources.ranges().values().stream().filter(row -> code.equals(row.getMaterialCode())).toList();
    var financial = finance.read(new FinanceFactorImportContext("COMMERCIAL", "210", month, null, null), FactorPriceScope.Condition.GENERAL, null);
    var rules = !linkedRows.isEmpty() ? linkedRows.stream().map(FinanceEffectivePriceService.SourceRule::linked).toList()
        : rangeRows.stream().map(FinanceEffectivePriceService.SourceRule::range).toList();
    assertThat(rules).as(code).isNotEmpty();
    var selection = new FinanceEffectivePriceSelectionService(effective,
        new SupplierPriceSelectionService(mock(SupplierSupplyRatioMapper.class), mock(SupplierPriceDecisionMapper.class)));
    var saved = selection.read(rules, financial);
    var chosen = selection.select(new SupplierPriceScope("COMMERCIAL", "FOLDER-TEST", null, month,
        java.time.YearMonth.parse(month).atDay(1), "210", code, linkedRows.isEmpty() ? "RANGE" : "LINKED"), saved.values(), null, List.of());
    assertThat(chosen.supplier().review()).isNull();
    assertThat(chosen.price().getUnitPrice()).as(code + "/" + month).isEqualByComparingTo(expected);
  }
  private void publishFinance(String org, String month, String scheme) throws Exception {
    byte[] bytes; try (var in = getClass().getResourceAsStream("/finance-factor/" + (org.equals("220") ? "plate" : "commercial") + "-original.xlsx")) { bytes = in.readAllBytes(); }
    var context = new FinanceFactorImportContext("COMMERCIAL", org, month, null, scheme);
    var preview = publications.preview(bytes, "财务.xlsx", context);
    publications.publish(bytes, "财务.xlsx", context, preview.previewFingerprint(), "folder-test");
  }
  private void publishLinked(byte[] bytes, String month) {
    var command = new PriceLinkedImportCommand(bytes, "原稿.xlsx", month, "COMMERCIAL", false, null, null, null, null);
    var preview = linked.preview(command); assertThat(preview.getErrors()).isEmpty();
    linked.confirm(new PriceLinkedImportCommand(bytes, "原稿.xlsx", month, "COMMERCIAL", false, null, null, null,
        preview.getFileSha256()).withPreviewFingerprint(preview.getPreviewFingerprint()));
  }
  private void publishRange(byte[] bytes, String month) {
    var command = new OriginalRangeImportService.Command(bytes, "区间.xlsx", "COMMERCIAL", month, null, null);
    var preview = ranges.preview(command); assertThat(preview.errors()).isEmpty();
    ranges.confirm(new OriginalRangeImportService.Command(bytes, "区间.xlsx", "COMMERCIAL", month, preview.fileSha256(), preview.previewFingerprint()));
  }
  record RowData(String org, String material, String supplier, String formula) {}
  static RowData row(String code, String formula) { return new RowData("210", code, "SUP", formula); }
  static byte[] workbook(RowData... rows) throws Exception {
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var factors = book.createSheet("因素"); var fh = factors.createRow(0);
      String[] titles = {"序号", "名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < titles.length; i++) fh.createCell(i).setCellValue(titles[i]);
      var f = factors.createRow(1); String[] values = {"1", "铜", "Cu", "平均价"};
      for (int i = 0; i < values.length; i++) f.createCell(i).setCellValue(values[i]);
      f.createCell(4).setCellValue(109.114); f.createCell(5).setCellValue("公斤");
      var main = book.createSheet("ImportData"); var header = main.createRow(0);
      String[] names = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      for (int i = 0; i < rows.length; i++) {
        var data = rows[i]; var row = main.createRow(i + 1);
        String[] cells = {data.org(), data.material(), "测试供应商", data.supplier(), "SPEC", "只"};
        for (int j = 0; j < cells.length; j++) row.createCell(j).setCellValue(cells[j]);
        if (data.formula() != null) row.createCell(6).setCellFormula(data.formula());
        row.createCell(7).setCellValue(false);
      }
      book.getCreationHelper().createFormulaEvaluator().evaluateAll(); book.write(out); return out.toByteArray();
    }
  }
  static byte[] rangeWorkbook(String code, double start) throws Exception {
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook(row(code, "99")))); var out = new ByteArrayOutputStream()) {
      var matrix = book.createSheet("矩阵"); matrix.createRow(0).createCell(0).setCellValue("铜价区间表");
      var h = matrix.createRow(1); h.createCell(0).setCellValue("U9代码"); h.createCell(1).setCellValue("图号"); h.createCell(2).setCellValue("供应商名称");
      var tax = matrix.createRow(2); var data = matrix.createRow(3);
      data.createCell(0).setCellValue(code); data.createCell(1).setCellValue("SPEC"); data.createCell(2).setCellValue("测试供应商");
      for (int i = 0; i < 4; i++) {
        int c = i * 2 + 3; h.createCell(c).setCellValue((90000 + i * 5000) + "-" + (94999 + i * 5000));
        tax.createCell(c).setCellValue("不含税"); tax.createCell(c + 1).setCellValue("含13%税价");
        data.createCell(c).setCellValue(start + i); data.createCell(c + 1).setCellValue((start + i) * 1.13);
      }
      book.write(out); return out.toByteArray();
    }
  }

  static byte[] rangePrices(String code, double... prices) throws Exception {
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(rangeWorkbook(code, 1))); var out = new ByteArrayOutputStream()) {
      var row = book.getSheet("矩阵").getRow(3);
      for (int i = 0; i < prices.length; i++) {
        row.getCell(i * 2 + 3).setCellValue(prices[i]); row.getCell(i * 2 + 4).setCellValue(prices[i] * 1.13);
      }
      book.write(out); return out.toByteArray();
    }
  }
}
