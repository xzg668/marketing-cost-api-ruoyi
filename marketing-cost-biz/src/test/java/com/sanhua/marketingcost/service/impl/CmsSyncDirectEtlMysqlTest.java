package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.dto.CmsSyncPublishRunResponse;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsSyncPublishSignalMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** Four ETL-owned formal inputs plus the sole scrap tmp publication, through actual MySQL services. */
@Tag("integration")
class CmsSyncDirectEtlMysqlTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
      .withDatabaseName("cms_direct_etl_test").withUsername("test").withPassword("test")
      .withLabel("sales-cost.task", "cms-direct-etl-20261116");
  private static final List<String> RAW = List.of("cms_plan_cost_raw", "cms_workshop_labor_raw",
      "cms_product_subject_cost_raw", "cms_subject_setting_raw");
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;
  private static SqlSessionTemplate session;
  private CmsSalaryCostSourceEffectiveServiceImpl salary;
  private CmsAuxSubjectSourceEffectiveServiceImpl auxiliary;
  private TechnicalDataPendingState pending;
  private CmsSyncPublishServiceImpl service;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    new ResourceDatabasePopulator(new ClassPathResource("sql/cms-default-source-sync-schema.sql"),
        new ClassPathResource("db/V178__cms_sync_publish_signal.sql")).execute(source);
    String schema = new ClassPathResource("db/V64__cms_cost_source_schema.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    var ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS cms_plan_cost_raw \\(.*?;", Pattern.DOTALL).matcher(schema);
    assertThat(ddl.find()).isTrue();
    jdbc.execute(ddl.group());
    jdbc.execute("ALTER TABLE cms_plan_cost_raw ADD period VARCHAR(255) NULL");
    new ResourceDatabasePopulator(new ClassPathResource("sql/cms-scrap-publish-schema.sql")).execute(source);
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    for (Class<?> mapper : List.of(CmsSyncPublishSignalMapper.class, CmsPlanCostRawMapper.class,
        CmsWorkshopLaborRawMapper.class, CmsProductSubjectCostRawMapper.class,
        CmsSubjectSettingRawMapper.class, CmsCostSourceEffectiveMapper.class, CmsCostSourceEffectiveLogMapper.class)) {
      config.addMapper(mapper);
    }
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setConfiguration(config);
    session = new SqlSessionTemplate(factory.getObject());
  }

  @AfterAll
  static void stop() { MYSQL.stop(); }

  @BeforeEach
  void seed() {
    for (String table : RAW) {
      jdbc.execute("DROP TABLE IF EXISTS tmp_" + table);
      jdbc.update("DELETE FROM " + table);
    }
    for (String table : List.of("cms_sync_publish_signal", "cms_cost_source_effective", "cms_cost_source_effective_log",
        "lp_material_scrap_ref", "tmp_lp_material_scrap_ref")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("""
        INSERT INTO cms_plan_cost_raw
          (import_batch_id,row_no,parent_code,effective_period,business_unit_type,period)
        VALUES (7,1,'A','2026-11','COMMERCIAL','2026-01')
        """);
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw
          (import_batch_id,row_no,period,parent_code,source_row_id,working_cost_cent,business_unit_type)
        VALUES (7,1,'2026-01','A','direct-A',100,'COMMERCIAL')
        """);
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw
          (import_batch_id,row_no,period,parent_code,source_row_id,second_subject_code,material_price,business_unit_type)
        VALUES (7,1,'2026-01','A','indirect-A','0302',200,'COMMERCIAL'),
               (7,2,'2026-01','A','aux-A','0201',300,'COMMERCIAL')
        """);
    jdbc.update("""
        INSERT INTO cms_subject_setting_raw
          (import_batch_id,first_subject_code,first_subject_name,second_subject_code,second_subject_name,third_subject_code,business_unit_type)
        VALUES (7,'03','工资','0302','辅助人员工资','','COMMERCIAL'),
               (7,'02','辅助材料','0201','辅助焊料类','','COMMERCIAL')
        """);
    scrap("tmp_lp_material_scrap_ref", "A", "SCRAP-A", "2026-10", "COMMERCIAL");
    var eligibility = new CmsPlanEligibilityServiceImpl(session.getMapper(CmsPlanCostRawMapper.class));
    var effective = session.getMapper(CmsCostSourceEffectiveMapper.class);
    var logs = session.getMapper(CmsCostSourceEffectiveLogMapper.class);
    salary = new CmsSalaryCostSourceEffectiveServiceImpl(session.getMapper(CmsWorkshopLaborRawMapper.class),
        session.getMapper(CmsProductSubjectCostRawMapper.class), session.getMapper(CmsSubjectSettingRawMapper.class),
        effective, logs, eligibility);
    auxiliary = new CmsAuxSubjectSourceEffectiveServiceImpl(session.getMapper(CmsProductSubjectCostRawMapper.class),
        session.getMapper(CmsSubjectSettingRawMapper.class), effective, logs, eligibility);
    pending = mock(TechnicalDataPendingState.class);
    service = publisher(auxiliary);
  }

  private CmsSyncPublishServiceImpl publisher(CmsAuxSubjectSourceEffectiveService aux) {
    var result = new CmsSyncPublishServiceImpl(session.getMapper(CmsSyncPublishSignalMapper.class),
        jdbc, transaction, salary, aux, 360);
    ReflectionTestUtils.setField(result, "pendingState", pending);
    return result;
  }

  private void ready() {
    jdbc.update("INSERT INTO cms_sync_publish_signal(batch_no,cost_year,business_unit_type,status,ready_at) "
        + "VALUES (?,2026,'COMMERCIAL','READY',NOW())", UUID.randomUUID().toString());
  }

  private CmsSyncPublishRunResponse run() { ready(); return service.runNextReady(); }

  private Map<String,List<Map<String,Object>>> snapshot(List<String> tables) {
    var result = new LinkedHashMap<String,List<Map<String,Object>>>();
    for (String table : tables) result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    return result;
  }

  @Test
  void fourDirectFormalInputsStayIntactWhileScrapPublishesFromTmp() {
    var before = snapshot(RAW);
    var result = run();
    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(result.getEffectiveInsertedCount()).isEqualTo(3);
    assertThat(result.getTmpCounts()).containsOnlyKeys("tmp_lp_material_scrap_ref");
    assertThat(result.getTmpCounts().get("tmp_lp_material_scrap_ref")).isEqualTo(1L);
    assertThat(result.getPublishedCounts()).containsEntry("lp_material_scrap_ref", 1).hasSize(1);
    assertThat(result.getMessage()).contains("ETL", "生效来源").doesNotContain("正式表覆盖完成");
    assertThat(snapshot(RAW)).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT amount_yuan FROM cms_cost_source_effective ORDER BY source_type", java.math.BigDecimal.class))
        .extracting(java.math.BigDecimal::stripTrailingZeros)
        .containsExactly(new java.math.BigDecimal("3"), new java.math.BigDecimal("1"), new java.math.BigDecimal("2"));
    verify(pending).changed(new TechnicalDataPendingState.Change(java.util.Set.of("SALARY","PRICE"),
        "COMMERCIAL",java.util.Set.of(),java.util.Set.of()));
    assertThat(jdbc.queryForObject("SELECT status FROM cms_sync_publish_signal", String.class)).isEqualTo("SUCCESS");
  }

  @Test
  void staleTmpTablesForOtherFourInputsAreNeverReadOrWritten() {
    for (String table : RAW) {
      jdbc.execute("CREATE TABLE tmp_" + table + " (id BIGINT PRIMARY KEY, marker VARCHAR(40))");
      jdbc.update("INSERT INTO tmp_" + table + " VALUES (1,'stale-do-not-publish')");
    }
    var tables = new java.util.ArrayList<>(RAW);
    tables.addAll(RAW.stream().map(t -> "tmp_" + t).toList());
    var before = snapshot(tables);
    assertThat(run().getStatus()).isEqualTo("SUCCESS");
    assertThat(snapshot(tables)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings={"cms_plan_cost_raw","cms_workshop_labor_raw","cms_product_subject_cost_raw",
      "cms_subject_setting_raw"})
  void truncatedFormalInputPreventsRefreshAndKeepsPreviousEffectiveData(String table) {
    assertThat(run().getStatus()).isEqualTo("SUCCESS");
    var effective = snapshot(List.of("cms_cost_source_effective","cms_cost_source_effective_log"));
    jdbc.execute("TRUNCATE TABLE " + table);
    var raw = snapshot(RAW);
    var result = run();
    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getMessage()).contains(table, "ETL");
    assertThat(snapshot(RAW)).isEqualTo(raw);
    assertThat(snapshot(List.of("cms_cost_source_effective","cms_cost_source_effective_log"))).isEqualTo(effective);
  }

  @Test
  void anotherBusinessUnitCannotSatisfyTheFormalInputGuard() {
    jdbc.update("UPDATE cms_plan_cost_raw SET business_unit_type='HOUSEHOLD'");
    assertThat(run().getStatus()).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective", Integer.class)).isZero();
    verify(pending, never()).changed(any());
  }

  @Test
  void effectiveFailureRollsBackDerivedRowsAndLeavesDirectEtlRowsIntact() {
    var failing = mock(CmsAuxSubjectSourceEffectiveService.class);
    when(failing.synchronizeDefaultSources(2026,"SYSTEM_CMS_SYNC","COMMERCIAL"))
        .thenThrow(new IllegalStateException("owned-refresh-failure"));
    service = publisher(failing);
    scrap("lp_material_scrap_ref", "A", "NEWER", "2026-11", "COMMERCIAL");
    var scrapBefore = snapshot(List.of("lp_material_scrap_ref", "tmp_lp_material_scrap_ref"));
    var before = snapshot(RAW);
    var result = run();
    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getMessage()).contains("owned-refresh-failure");
    assertThat(snapshot(List.of("lp_material_scrap_ref", "tmp_lp_material_scrap_ref"))).isEqualTo(scrapBefore);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log", Integer.class)).isZero();
    assertThat(snapshot(RAW)).isEqualTo(before);
    verify(pending, never()).changed(any());
  }

  @Test
  void manualSourceAndApprovalRulesRemainEffectiveAfterDirectExtraction() {
    assertThat(run().getStatus()).isEqualTo("SUCCESS");
    jdbc.update("UPDATE cms_cost_source_effective SET default_flag=0,amount_yuan=42 WHERE source_type='SALARY_DIRECT'");
    var manual = jdbc.queryForMap("SELECT * FROM cms_cost_source_effective WHERE source_type='SALARY_DIRECT'");
    jdbc.update("UPDATE cms_product_subject_cost_raw SET material_price=999");
    jdbc.update("UPDATE cms_plan_cost_raw SET unapproved_items='辅料未审批'");
    var before = snapshot(RAW);
    var result = run();
    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForMap("SELECT * FROM cms_cost_source_effective WHERE source_type='SALARY_DIRECT'"))
        .isEqualTo(manual);
    assertThat(result.getEffectiveBlockedCount()).isEqualTo(2);
    assertThat(result.getEffectiveRemovedCount()).isEqualTo(2);
    assertThat(snapshot(RAW)).isEqualTo(before);
  }

  private void scrap(String table, String material, String scrap, String period, String unit) {
    jdbc.update("INSERT INTO " + table
        + "(material_code,scrap_code,cms_posting_period,business_unit_type,created_at,updated_at)"
        + " VALUES (?,?,?,?,'2026-10-01 00:00:00','2026-10-01 00:00:00')", material,scrap,period,unit);
  }

  @Test
  void duplicateScrapRowsSelectOnlyLatestMappingPerMaterial() {
    jdbc.update("DELETE FROM tmp_lp_material_scrap_ref");
    scrap("tmp_lp_material_scrap_ref","A","OLD","2026-01","COMMERCIAL");
    scrap("tmp_lp_material_scrap_ref","A","OLD","2026-02","COMMERCIAL");
    scrap("tmp_lp_material_scrap_ref","A","NEW","2026-10","COMMERCIAL");
    scrap("tmp_lp_material_scrap_ref","A","NEW","2026-10","COMMERCIAL");
    scrap("tmp_lp_material_scrap_ref"," ","IGNORED","2026-11","COMMERCIAL");
    var before=snapshot(RAW);
    var result=run();
    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(result.getPublishedCounts()).containsEntry("lp_material_scrap_ref",1);
    assertThat(jdbc.queryForList("SELECT scrap_code FROM lp_material_scrap_ref",String.class)).containsExactly("NEW");
    assertThat(snapshot(RAW)).isEqualTo(before);
  }

  @Test
  void newerFormalMappingIsCarriedBackWithoutChangingOtherBusinessUnit() {
    scrap("lp_material_scrap_ref","A","NEWER","2026-11","COMMERCIAL");
    scrap("lp_material_scrap_ref","A","HOUSEHOLD-KEEP","2026-12","HOUSEHOLD");
    var household=jdbc.queryForMap("SELECT * FROM lp_material_scrap_ref WHERE business_unit_type='HOUSEHOLD'");
    assertThat(run().getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT scrap_code FROM lp_material_scrap_ref WHERE business_unit_type='COMMERCIAL'",String.class))
        .isEqualTo("NEWER");
    assertThat(jdbc.queryForMap("SELECT * FROM lp_material_scrap_ref WHERE business_unit_type='HOUSEHOLD'")).isEqualTo(household);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tmp_lp_material_scrap_ref",Integer.class)).isEqualTo(2);
    assertThat(run().getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tmp_lp_material_scrap_ref",Integer.class)).isEqualTo(2);
  }

  @Test
  void emptyScrapTmpPreventsReplacementAndEffectiveRefresh() {
    scrap("lp_material_scrap_ref","A","KEEP","2026-10","COMMERCIAL");
    jdbc.update("DELETE FROM tmp_lp_material_scrap_ref");
    var before=snapshot(List.of("lp_material_scrap_ref"));
    var result=run();
    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getMessage()).contains("tmp_lp_material_scrap_ref");
    assertThat(snapshot(List.of("lp_material_scrap_ref"))).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective",Integer.class)).isZero();
  }

  @Test
  void anotherBusinessUnitsScrapTmpDoesNotReplaceSelectedUnit() {
    scrap("lp_material_scrap_ref","A","KEEP","2026-10","COMMERCIAL");
    jdbc.update("UPDATE tmp_lp_material_scrap_ref SET business_unit_type='HOUSEHOLD'");
    var before=snapshot(List.of("lp_material_scrap_ref","tmp_lp_material_scrap_ref"));
    assertThat(run().getStatus()).isEqualTo("FAILED");
    assertThat(snapshot(List.of("lp_material_scrap_ref","tmp_lp_material_scrap_ref"))).isEqualTo(before);
  }
}
