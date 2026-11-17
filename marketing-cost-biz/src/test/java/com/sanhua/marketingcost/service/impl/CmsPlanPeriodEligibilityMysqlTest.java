package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class CmsPlanPeriodEligibilityMysqlTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
      .withDatabaseName("cms_plan_period_test").withUsername("test").withPassword("test");
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;
  private static SqlSessionTemplate session;
  private CmsSalaryCostSourceEffectiveServiceImpl salary;
  private CmsAuxSubjectSourceEffectiveServiceImpl auxiliary;
  private CmsPlanEligibilityServiceImpl eligibility;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    new ResourceDatabasePopulator(new ClassPathResource("sql/cms-default-source-sync-schema.sql"))
        .execute(source);
    String schema = new ClassPathResource("db/V64__cms_cost_source_schema.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    var ddl = Pattern.compile("CREATE TABLE IF NOT EXISTS cms_plan_cost_raw \\(.*?;", Pattern.DOTALL)
        .matcher(schema);
    assertThat(ddl.find()).isTrue();
    jdbc.execute(ddl.group());
    // Reproduce the existing ETL source-month column only in this disposable database.
    jdbc.execute("ALTER TABLE cms_plan_cost_raw ADD period VARCHAR(255) NULL");
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    for (Class<?> mapper : List.of(CmsPlanCostRawMapper.class, CmsWorkshopLaborRawMapper.class,
        CmsProductSubjectCostRawMapper.class, CmsSubjectSettingRawMapper.class,
        CmsCostSourceEffectiveMapper.class, CmsCostSourceEffectiveLogMapper.class)) {
      configuration.addMapper(mapper);
    }
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setConfiguration(configuration);
    session = new SqlSessionTemplate(factory.getObject());
  }

  @AfterAll
  static void stop() { MYSQL.stop(); }

  @BeforeEach
  void prepare() {
    for (String table : List.of("cms_plan_cost_raw", "cms_workshop_labor_raw", "cms_product_subject_cost_raw",
        "cms_subject_setting_raw", "cms_cost_source_effective", "cms_cost_source_effective_log")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("""
        INSERT INTO cms_subject_setting_raw
          (import_batch_id,first_subject_code,first_subject_name,second_subject_code,
           second_subject_name,third_subject_code,business_unit_type)
        VALUES (1,'03','工资','0399','辅助人员工资','','COMMERCIAL'),
               (1,'02','辅助材料','0201','辅助焊料类','','COMMERCIAL')
        """);
    eligibility = new CmsPlanEligibilityServiceImpl(session.getMapper(CmsPlanCostRawMapper.class));
    var effective = session.getMapper(CmsCostSourceEffectiveMapper.class);
    var logs = session.getMapper(CmsCostSourceEffectiveLogMapper.class);
    salary = new CmsSalaryCostSourceEffectiveServiceImpl(session.getMapper(CmsWorkshopLaborRawMapper.class),
        session.getMapper(CmsProductSubjectCostRawMapper.class), session.getMapper(CmsSubjectSettingRawMapper.class),
        effective, logs, eligibility);
    auxiliary = new CmsAuxSubjectSourceEffectiveServiceImpl(session.getMapper(CmsProductSubjectCostRawMapper.class),
        session.getMapper(CmsSubjectSettingRawMapper.class), effective, logs, eligibility);
  }

  @Test
  void skipsMissingJanuaryAndUnapprovedFebruaryThenAdoptsMarchAmountsAndRawIdentities() {
    raw("A", "2026-12", 1200);
    raw("A", "2026-03", 300);
    raw("A", "2026-01", 100);
    raw("A", "2026-02", 200);
    plan("A", "2026-02", "2026-11", "工时;辅料", "COMMERCIAL");
    plan("A", "2026-03", "2026-11", "", "COMMERCIAL");
    plan("A", "2026-12", "2026-11", null, "COMMERCIAL");
    var rawBefore = rawSnapshot();

    sync();

    assertSource("A", "SALARY_DIRECT", "2026-03", "3");
    assertSource("A", "SALARY_INDIRECT", "2026-03", "6");
    assertSource("A", "AUX_SUBJECT", "2026-03", "9");
    String rawId = jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM cms_workshop_labor_raw "
        + "WHERE parent_code='A' AND period='2026-03'", String.class);
    assertThat(source("A", "SALARY_DIRECT")).containsEntry("source_row_ids", rawId);
    assertThat(rawSnapshot()).isEqualTo(rawBefore);
  }

  @Test
  void matchesPeriodEvenWhenEffectivePeriodPointsAtAnotherCandidateMonth() {
    raw("A", "2026-01", 100);
    raw("A", "2026-02", 200);
    plan("A", "2026-02", "2026-01", null, "COMMERCIAL");

    sync();

    assertSource("A", "SALARY_DIRECT", "2026-02", "2");
    assertSource("A", "SALARY_INDIRECT", "2026-02", "4");
    assertSource("A", "AUX_SUBJECT", "2026-02", "6");
  }

  @Test
  void workingHourAndAuxiliaryFlagsChooseTheirOwnFirstAllowedMonthAndKeepZeroAmounts() {
    raw("A", "2026-01", 0);
    raw("A", "2026-02", 200);
    plan("A", "2026-01", "2026-11", "工时", "COMMERCIAL");
    plan("A", "2026-02", "2026-11", "辅料", "COMMERCIAL");

    sync();

    assertSource("A", "SALARY_DIRECT", "2026-02", "2");
    assertSource("A", "SALARY_INDIRECT", "2026-01", "0");
    assertSource("A", "AUX_SUBJECT", "2026-01", "0");
  }

  @Test
  void anyUnapprovedRowInTheSameMonthBlocksThatTypeAndSearchContinues() {
    raw("A", "2026-01", 100);
    raw("A", "2026-02", 200);
    plan("A", "2026-01", "2026-11", null, "COMMERCIAL");
    plan("A", "2026-01", "2026-10", "工时", "COMMERCIAL");
    plan("A", "2026-01", "2026-09", "辅料", "COMMERCIAL");
    plan("A", "2026-02", "2026-11", null, "COMMERCIAL");

    sync();

    assertThat(jdbc.queryForList("SELECT period FROM cms_cost_source_effective", String.class))
        .hasSize(3).containsOnly("2026-02");
  }

  @Test
  void anEmptyPlanTableNeverGeneratesDefaultsOrManualSources() {
    raw("A", "2026-01", 100);
    raw("A", "2026-12", 1200);

    sync();

    assertThat(effectiveCount()).isZero();
    assertThatThrownBy(() -> transaction.execute(ignored -> salary.refreshSource(request("SALARY_DIRECT"), "tester", "COMMERCIAL")))
        .hasMessageContaining("未找到计划成本记录");
    assertThatThrownBy(() -> transaction.execute(ignored -> auxiliary.refreshSource(request("AUX_SUBJECT"), "tester", "COMMERCIAL")))
        .hasMessageContaining("未找到计划成本记录");
    assertThat(effectiveCount()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log", Integer.class)).isZero();
  }

  @Test
  void allRejectedOrMissingMonthsDoNotBorrowAnotherYearOrAnEmptyPeriod() {
    raw("A", "2026-01", 100);
    raw("A", "2026-12", 1200);
    raw("A", "2027-01", 1300);
    plan("A", "2026-01", "2026-11", "工时;辅料", "COMMERCIAL");
    plan("A", null, "2026-12", null, "COMMERCIAL");
    plan("A", "2025-12", "2026-12", null, "COMMERCIAL");
    plan("A", "2027-01", "2026-12", null, "COMMERCIAL");

    sync();

    assertThat(effectiveCount()).isZero();
  }

  @Test
  void eachProductAndBusinessUnitRequiresItsOwnPlanWhileUnusedPlanMonthsCannotSupplyMoney() {
    raw("A", "2026-01", 100);
    raw("A", "2026-03", 300);
    raw("B", "2026-01", 400);
    plan("A", "2026-01", "2026-11", null, "HOUSEHOLD");
    plan("A", "2026-02", "2026-11", null, "COMMERCIAL");
    plan("A", "2026-03", "2026-11", null, "COMMERCIAL");
    plan("B", "2026-01", "2026-11", null, "COMMERCIAL");

    sync();

    assertSource("A", "SALARY_DIRECT", "2026-03", "3");
    assertSource("B", "SALARY_DIRECT", "2026-01", "4");
    assertThat(effectiveCount()).isEqualTo(6);
  }

  @Test
  void synchronizeAdvancesExistingDefaultsPreservesManualSelectionAndCancelsUnusableDefaultsOnce() {
    raw("A", "2026-01", 100);
    raw("A", "2026-02", 200);
    plan("A", "2026-01", "2026-11", null, "COMMERCIAL");
    sync();
    var indirectId = source("A", "SALARY_INDIRECT").get("id");
    transaction.execute(ignored -> salary.refreshSource(request("SALARY_DIRECT"), "tester", "COMMERCIAL"));
    var manual = source("A", "SALARY_DIRECT");
    jdbc.update("UPDATE cms_plan_cost_raw SET unapproved_items='工时;辅料'");
    plan("A", "2026-02", "2026-11", null, "COMMERCIAL");

    sync();

    assertThat(source("A", "SALARY_DIRECT")).isEqualTo(manual);
    assertSource("A", "SALARY_INDIRECT", "2026-02", "4");
    assertThat(source("A", "SALARY_INDIRECT")).containsEntry("id", indirectId);
    assertSource("A", "AUX_SUBJECT", "2026-02", "6");
    int logs = jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log", Integer.class);
    sync();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log", Integer.class)).isEqualTo(logs);

    jdbc.update("DELETE FROM cms_plan_cost_raw");
    sync();
    assertThat(effectiveCount()).isEqualTo(1);
    assertThat(source("A", "SALARY_DIRECT")).isEqualTo(manual);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log WHERE action_type='CANCEL'", Integer.class))
        .isEqualTo(2);
    sync();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective_log WHERE action_type='CANCEL'", Integer.class))
        .isEqualTo(2);
  }

  private void sync() {
    transaction.executeWithoutResult(ignored -> {
      salary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
      auxiliary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
    });
  }

  private void raw(String parent, String period, int cents) {
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw
          (import_batch_id,row_no,period,parent_code,source_row_id,working_cost_cent,business_unit_type)
        VALUES (1,1,?,?,?,?,'COMMERCIAL')
        """, period, parent, parent + "-" + period + "-direct", cents);
    for (String subject : List.of("0399", "0201")) {
      jdbc.update("""
          INSERT INTO cms_product_subject_cost_raw
            (import_batch_id,row_no,period,parent_code,source_row_id,second_subject_code,material_price,business_unit_type)
          VALUES (1,1,?,?,?,?,?,'COMMERCIAL')
          """, period, parent, parent + "-" + period + "-" + subject, subject,
          cents * ("0399".equals(subject) ? 2 : 3));
    }
  }

  private void plan(String parent, String period, String effectivePeriod, String unapproved, String unit) {
    jdbc.update("""
        INSERT INTO cms_plan_cost_raw
          (import_batch_id,row_no,parent_code,period,effective_period,unapproved_items,business_unit_type)
        VALUES (1,1,?,?,?,?,?)
        """, parent, period, effectivePeriod, unapproved, unit);
  }

  private Map<String, Object> source(String parent, String type) {
    return jdbc.queryForMap("SELECT * FROM cms_cost_source_effective WHERE parent_code=? AND source_type=?",
        parent, type);
  }

  private void assertSource(String parent, String type, String period, String amount) {
    var row = source(parent, type);
    assertThat(row).containsEntry("period", period);
    assertThat((BigDecimal) row.get("amount_yuan")).isEqualByComparingTo(amount);
  }

  private int effectiveCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective", Integer.class);
  }

  private List<List<Map<String, Object>>> rawSnapshot() {
    return List.of("cms_plan_cost_raw", "cms_workshop_labor_raw", "cms_product_subject_cost_raw",
        "cms_subject_setting_raw").stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
  }

  private CmsEffectiveSourceRefreshRequest request(String type) {
    var request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setNewPeriod("2026-01");
    request.setSourceType(type);
    request.setSubjectCode("0201");
    request.setRefreshReason("test manual selection");
    return request;
  }
}
