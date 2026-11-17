package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.config.MybatisPlusConfig;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real MySQL pagination and index reads; no application schedulers or external services. */
@Tag("integration")
class CmsWorkshopQueryMysqlTest {
  private static final String PRODUCT = "1145900030465";
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image", "mysql:8.0.36")))
          .withDatabaseName("cms_workshop_query_test")
          .withUsername("root")
          .withPassword("cms-query-test-only")
          .withLabel("sales-cost.task", "cms-workshop-query-20261116");
  private static JdbcTemplate jdbc;
  private static CmsWorkshopLaborRawMapper mapper;
  private static CmsCostQueryServiceImpl service;
  private static TransactionTemplate transaction;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    String schema = new ClassPathResource("db/V64__cms_cost_source_schema.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    for (String table : List.of("cms_cost_import_batch", "cms_workshop_labor_raw")) {
      var ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS " + table + " \\(.*?;", Pattern.DOTALL).matcher(schema);
      assertThat(ddl.find()).isTrue();
      jdbc.execute(ddl.group());
    }
    jdbc.execute("ALTER TABLE cms_cost_import_batch ADD subject_setting_file_name VARCHAR(255), ADD subject_setting_row_count INT DEFAULT 0");
    jdbc.execute("ALTER TABLE cms_workshop_labor_raw ADD UNIQUE KEY uk_cms_workshop_source_row(business_unit_type,period,source_row_id)");
    jdbc.update("INSERT INTO cms_cost_import_batch(id,batch_no,business_unit_type) VALUES (1,'CMS-ONE','COMMERCIAL'),(2,'CMS-TWO','COMMERCIAL'),(3,'CMS-OTHER','HOUSEHOLD')");

    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(CmsWorkshopLaborRawMapper.class);
    config.addMapper(CmsCostImportBatchMapper.class);
    config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(config);
    var sql = new SqlSessionTemplate(factory.getObject());
    mapper = sql.getMapper(CmsWorkshopLaborRawMapper.class);
    var target = new CmsCostQueryServiceImpl(sql.getMapper(CmsCostImportBatchMapper.class), null, mapper, null, null, null, null);
    var manager = new DataSourceTransactionManager(dataSource);
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    service = (CmsCostQueryServiceImpl) proxy.getProxy();
    transaction = new TransactionTemplate(manager);
    transaction.setReadOnly(true);

    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < 20000; i++) {
      rows.add(row(1, "BACKGROUND-" + (i % 100), "2026-03", "COMMERCIAL", i));
    }
    for (int month = 3; month <= 10; month++) {
      for (int i = 0; i < 21; i++) {
        rows.add(row(1, PRODUCT, "2026-" + String.format("%02d", month), "COMMERCIAL", rows.size()));
      }
    }
    rows.add(row(1, "X" + PRODUCT + "Y", "2026-03", "COMMERCIAL", rows.size()));
    rows.add(row(2, PRODUCT, "2025-03", "COMMERCIAL", rows.size()));
    rows.add(row(3, PRODUCT, "2026-03", "HOUSEHOLD", rows.size()));
    rows.add(row(3, "ONLY-HOUSEHOLD", "2026-03", "HOUSEHOLD", rows.size()));
    for (int i = 0; i < 205; i++) {
      rows.add(row(1, "WIDE-" + i, "2026-03", "COMMERCIAL", rows.size()));
    }
    rows.add(row(1, "MixÉ-001", "2026-03", "COMMERCIAL", rows.size()));
    jdbc.batchUpdate("INSERT INTO cms_workshop_labor_raw(import_batch_id,parent_code,period,business_unit_type,row_no,source_row_id,path,working_cost_cent,working_cost_yuan) VALUES (?,?,?,?,?,?,?,100,1)", rows);
    jdbc.execute("ANALYZE TABLE cms_workshop_labor_raw");
  }

  private static Object[] row(long batch, String code, String period, String unit, int sequence) {
    return new Object[] {batch, code, period, unit, sequence, "source-" + sequence, "processing-path/".repeat(50)};
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Test
  void specificMaterialDoesNotScanEveryWorkshopRowForPaginationCount() {
    transaction.executeWithoutResult(status -> {
      long before = sequentialReads();
      var page = service.pageWorkshopRows(null, PRODUCT, null, null, 1, 20, "COMMERCIAL");
      long reads = sequentialReads() - before;
      assertThat(page.getTotal()).isEqualTo(170);
      assertThat(page.getList()).hasSize(20);
      assertThat(reads).as("sequential MySQL row/index reads for one material search").isLessThan(3000);
    });
  }

  private static long sequentialReads() {
    return jdbc.queryForList("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_next','Handler_read_prev','Handler_read_rnd_next')")
        .stream().mapToLong(row -> Long.parseLong(row.get("Value").toString())).sum();
  }

  @ParameterizedTest
  @ValueSource(strings = {PRODUCT, "900030465", "114590003", "mixe", "WIDE-", "WIDE-_", "WIDE-%", "' OR 1=1 --", "NO-MATCH", "ONLY-HOUSEHOLD"})
  void completeAndPartialSearchesKeepOriginalResultsAndScope(String term) {
    assertOriginalResults(term, null, null, "COMMERCIAL", 1, 20);
  }

  @Test
  void yearAndMonthFiltersRetainMonthPrecedenceAndAllPages() {
    assertOriginalResults(PRODUCT, 2026, null, "COMMERCIAL", 2, 20);
    assertOriginalResults(PRODUCT, 2025, "2026-03", "COMMERCIAL", 1, 20);
    assertOriginalResults(PRODUCT, 2026, "2026-03", "COMMERCIAL", 2, 20);
    assertOriginalResults(PRODUCT, null, "2026-11", "COMMERCIAL", 1, 20);
    assertOriginalResults(PRODUCT, null, null, "COMMERCIAL", 10, 20);
  }

  @Test
  void emptyFilterOtherBusinessUnitAndWhitespaceKeepTheirOriginalMeaning() {
    assertOriginalResults("", null, null, "COMMERCIAL", 1, 20);
    assertOriginalResults("  " + PRODUCT + "  ", null, null, "HOUSEHOLD", 1, 20);
    assertOriginalResults(PRODUCT, null, null, null, 1, 20);
  }

  @Test
  void manyMatchingCodesAreNotTruncatedAtTheLookupLimit() {
    var page = service.pageWorkshopRows(null, "WIDE-", null, null, 2, 200, "COMMERCIAL");
    assertThat(page.getTotal()).isEqualTo(205);
    assertThat(page.getList()).hasSize(5);
    assertOriginalResults("WIDE-", null, null, "COMMERCIAL", 2, 200);
  }

  @Test
  void importBatchAndPageSizeBoundsRemainEffective() {
    var one = service.pageWorkshopRows("CMS-ONE", PRODUCT, null, null, 1, 200, "COMMERCIAL");
    assertThat(one.getTotal()).isEqualTo(169);
    assertThat(one.getList()).allSatisfy(row -> assertThat(row.getImportBatchId()).isEqualTo(1));
    assertThat(service.pageWorkshopRows("CMS-TWO", PRODUCT, null, null, 1, 20, "COMMERCIAL").getTotal()).isEqualTo(1);
    assertThat(service.pageWorkshopRows("CMS-OTHER", PRODUCT, null, null, 1, 20, "COMMERCIAL").getTotal()).isZero();
    assertThat(service.pageWorkshopRows(null, "WIDE-", null, null, 0, 1000, "COMMERCIAL").getList()).hasSize(200);
    assertThat(service.pageWorkshopRows(null, PRODUCT, null, null, -1, 0, "COMMERCIAL").getList()).hasSize(20);
  }

  private void assertOriginalResults(String term, Integer year, String month, String unit, int current, int size) {
    var original = new QueryWrapper<CmsWorkshopLaborRaw>()
        .like(term != null && !term.isBlank(), "parent_code", term == null ? null : term.trim())
        .likeRight(year != null && month == null, "period", year + "-")
        .eq(month != null, "period", month)
        .eq(unit != null, "business_unit_type", unit)
        .orderByDesc("id");
    var expected = mapper.selectPage(new Page<>(current, size), original);
    var actual = service.pageWorkshopRows(null, term, year, month, current, size, unit);
    assertThat(actual.getRawType()).isEqualTo("WORKSHOP");
    assertThat(actual.getTotal()).isEqualTo(expected.getTotal());
    assertThat(actual.getList()).usingRecursiveComparison().isEqualTo(expected.getRecords());
  }
}
