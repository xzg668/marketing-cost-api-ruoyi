package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.util.List;
import java.util.Map;
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
class CmsDefaultSourceSyncIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
      .withDatabaseName("cms_source_sync_test").withUsername("test").withPassword("test");
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transaction;
  private static SqlSessionTemplate session;
  private static CmsCostSourceEffectiveMapper effectiveMapper;
  private static CmsCostSourceEffectiveLogMapper logMapper;
  private CmsPlanEligibilityService eligibility;
  private CmsSalaryCostSourceEffectiveServiceImpl salary;
  private CmsAuxSubjectSourceEffectiveServiceImpl auxiliary;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    new ResourceDatabasePopulator(new ClassPathResource("sql/cms-default-source-sync-schema.sql"))
        .execute(source);
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    for (Class<?> mapper : List.of(CmsWorkshopLaborRawMapper.class, CmsProductSubjectCostRawMapper.class,
        CmsSubjectSettingRawMapper.class, CmsCostSourceEffectiveMapper.class, CmsCostSourceEffectiveLogMapper.class)) {
      configuration.addMapper(mapper);
    }
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setConfiguration(configuration);
    session = new SqlSessionTemplate(factory.getObject());
    effectiveMapper = session.getMapper(CmsCostSourceEffectiveMapper.class);
    logMapper = session.getMapper(CmsCostSourceEffectiveLogMapper.class);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void seed() {
    for (String table : List.of("cms_workshop_labor_raw", "cms_product_subject_cost_raw",
        "cms_subject_setting_raw", "cms_cost_source_effective", "cms_cost_source_effective_log")) {
      jdbc.update("DELETE FROM " + table);
    }
    jdbc.update("""
        INSERT INTO cms_subject_setting_raw
        (import_batch_id, first_subject_code, first_subject_name, second_subject_code,
         second_subject_name, third_subject_code, business_unit_type)
        VALUES (1, '03', '工资', '0302', '辅助人员工资', '', 'COMMERCIAL'),
               (1, '02', '辅助材料', '0201', '辅助焊料类', '', 'COMMERCIAL')
        """);
    seedRawRows();
    eligibility = mock(CmsPlanEligibilityService.class);
    when(eligibility.checkEligibility(anyCollection(), anyCollection(), anyString())).thenReturn(Map.of());
    salary = new CmsSalaryCostSourceEffectiveServiceImpl(
        session.getMapper(CmsWorkshopLaborRawMapper.class), session.getMapper(CmsProductSubjectCostRawMapper.class),
        session.getMapper(CmsSubjectSettingRawMapper.class), effectiveMapper, logMapper, eligibility);
    auxiliary = new CmsAuxSubjectSourceEffectiveServiceImpl(
        session.getMapper(CmsProductSubjectCostRawMapper.class), session.getMapper(CmsSubjectSettingRawMapper.class),
        effectiveMapper, logMapper, eligibility);
  }

  private void seedRawRows() {
    jdbc.update("""
        INSERT INTO cms_workshop_labor_raw
        (import_batch_id, row_no, period, parent_code, source_row_id, working_cost_cent, business_unit_type)
        VALUES (1, 1, '2026-01', 'A', 'direct-A', 100, 'COMMERCIAL')
        """);
    jdbc.update("""
        INSERT INTO cms_product_subject_cost_raw
        (import_batch_id, row_no, period, parent_code, source_row_id, second_subject_code,
         material_price, business_unit_type)
        VALUES (1, 1, '2026-01', 'A', 'indirect-A', '0302', 200, 'COMMERCIAL'),
               (1, 2, '2026-01', 'A', 'aux-A', '0201', 300, 'COMMERCIAL')
        """);
  }

  private List<CmsEffectiveSourceGenerateResponse> sync() {
    return transaction.execute(ignored -> List.of(
        salary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL"),
        auxiliary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL")));
  }

  private List<CmsCostSourceEffective> sources() {
    return effectiveMapper.selectList(new QueryWrapper<CmsCostSourceEffective>().orderByAsc("source_type"));
  }

  private List<CmsCostSourceEffectiveLog> logs() {
    return logMapper.selectList(new QueryWrapper<CmsCostSourceEffectiveLog>().orderByAsc("id"));
  }

  @Test
  void repeatedImportsRetainSourceIdsRefreshPhysicalReferencesAndDoNotAppendLogs() {
    var first = sync();
    var before = sources();
    assertThat(first.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getInsertedCount).sum()).isEqualTo(3);
    assertThat(logs()).hasSize(3);
    jdbc.update("DELETE FROM cms_workshop_labor_raw");
    jdbc.update("DELETE FROM cms_product_subject_cost_raw");
    seedRawRows();

    for (int i = 0; i < 15; i++) {
      var result = sync();
      assertThat(result.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getSkippedCount).sum()).isEqualTo(3);
      assertThat(result.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getInsertedCount).sum()).isZero();
      assertThat(result.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getUpdatedCount).sum()).isZero();
      assertThat(logs()).hasSize(3);
    }
    var after = sources();
    assertThat(after).extracting(CmsCostSourceEffective::getId)
        .containsExactlyElementsOf(before.stream().map(CmsCostSourceEffective::getId).toList());
    for (int i = 0; i < after.size(); i++) {
      assertThat(after.get(i).getSourceRowIds()).isNotEqualTo(before.get(i).getSourceRowIds());
      assertThat(after.get(i).getAmountYuan()).isEqualByComparingTo(before.get(i).getAmountYuan());
    }
  }

  @Test
  void logsAmountPeriodAndSubjectChangesOnceWithPreviousValues() {
    sync();
    jdbc.update("UPDATE cms_workshop_labor_raw SET working_cost_cent=250");
    jdbc.update("UPDATE cms_product_subject_cost_raw SET period='2026-02' WHERE second_subject_code='0302'");
    jdbc.update("UPDATE cms_subject_setting_raw SET second_subject_name='新焊料科目' WHERE second_subject_code='0201'");
    var result = sync();
    assertThat(result.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getUpdatedCount).sum()).isEqualTo(3);
    var changes = logs().subList(3, 6);
    var direct = changes.stream().filter(log -> "SALARY_DIRECT".equals(log.getSourceType())).findFirst().orElseThrow();
    assertThat(direct.getOldAmountYuan()).isEqualByComparingTo("1");
    assertThat(direct.getNewAmountYuan()).isEqualByComparingTo("2.5");
    var indirect = changes.stream().filter(log -> "SALARY_INDIRECT".equals(log.getSourceType())).findFirst().orElseThrow();
    assertThat(indirect.getOldPeriod()).isEqualTo("2026-01");
    assertThat(indirect.getNewPeriod()).isEqualTo("2026-02");
    assertThat(changes).extracting(CmsCostSourceEffectiveLog::getSubjectName).contains("新焊料科目");
    sync();
    assertThat(logs()).hasSize(6);
  }

  @Test
  void removesMissingDefaultsOnlyInSelectedYearAndBusinessUnitWhilePreservingManualSources() {
    sync();
    jdbc.update("UPDATE cms_cost_source_effective SET default_flag=0 WHERE source_type='SALARY_DIRECT'");
    jdbc.update("""
        INSERT INTO cms_cost_source_effective
        (cost_year, source_type, parent_code, period, subject_code, source_table, source_row_ids,
         amount_yuan, default_flag, business_unit_type)
        VALUES (2025, 'SALARY_DIRECT', 'A', '2025-01', '0301', 'cms_workshop_labor_raw', '1', 1, 1, 'COMMERCIAL'),
               (2026, 'SALARY_DIRECT', 'A', '2026-01', '0301', 'cms_workshop_labor_raw', '1', 1, 1, 'HOUSEHOLD')
        """);
    jdbc.update("DELETE FROM cms_workshop_labor_raw");
    jdbc.update("DELETE FROM cms_product_subject_cost_raw");
    var result = sync();
    assertThat(result.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getRemovedCount).sum()).isEqualTo(2);
    assertThat(sources()).hasSize(3);
    var cancelled = logs().subList(3, 5);
    assertThat(cancelled).extracting(CmsCostSourceEffectiveLog::getActionType).containsOnly("CANCEL");
    var query = new CmsCostQueryServiceImpl(null, null, null, null, null, effectiveMapper, logMapper);
    var page = query.pageEffectiveSourceLogs(2026, "A", "2026-01", null, null, "CANCEL", 1, 20, "COMMERCIAL");
    assertThat(page.getRecords()).hasSize(2);
    assertThat(cancelled).allSatisfy(log -> {
      assertThat(log.getOldPeriod()).isEqualTo("2026-01");
      assertThat(log.getNewPeriod()).isNull();
      assertThat(log.getNewAmountYuan()).isNull();
    });
    sync();
    assertThat(logs()).hasSize(5);
  }

  @Test
  void repeatedEligibilityRejectionsUseBatchCountsAndCancelPreviousSourcesOnlyOnce() {
    sync();
    PlanEligibility blocked = new PlanEligibility();
    blocked.setReason("计划成本未审批");
    when(eligibility.checkEligibility(anyCollection(), anyCollection(), anyString()))
        .thenReturn(Map.of("A|2026-01", blocked));
    var first = sync();
    assertThat(first.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getBlockedCount).sum()).isEqualTo(3);
    assertThat(first.stream().mapToInt(CmsEffectiveSourceGenerateResponse::getRemovedCount).sum()).isEqualTo(3);
    assertThat(sources()).isEmpty();
    assertThat(logs()).hasSize(6);
    sync();
    assertThat(logs()).hasSize(6);
    assertThat(logs()).extracting(CmsCostSourceEffectiveLog::getActionType).doesNotContain("BLOCKED");
  }

  @Test
  void manualRefreshStillLogsEveryOperationAndAutomaticSyncDoesNotOverwriteIt() {
    sync();
    for (String type : List.of("SALARY_DIRECT", "AUX_SUBJECT")) {
      var request = new CmsEffectiveSourceRefreshRequest();
      request.setCostYear(2026);
      request.setParentCode("A");
      request.setNewPeriod("2026-01");
      request.setSourceType(type);
      request.setSubjectCode("AUX_SUBJECT".equals(type) ? "0201" : "0301");
      request.setRefreshReason("人工确认");
      for (int i = 0; i < 2; i++) {
        transaction.executeWithoutResult(ignored -> {
          if ("AUX_SUBJECT".equals(type)) auxiliary.refreshSource(request, "tester", "COMMERCIAL");
          else salary.refreshSource(request, "tester", "COMMERCIAL");
        });
      }
    }
    assertThat(logs()).hasSize(7);
    jdbc.update("UPDATE cms_workshop_labor_raw SET working_cost_cent=999");
    jdbc.update("UPDATE cms_product_subject_cost_raw SET material_price=999 WHERE second_subject_code='0201'");
    sync();
    assertThat(logs()).hasSize(7);
    assertThat(sources()).filteredOn(source -> source.getDefaultFlag() == 0).hasSize(2)
        .allSatisfy(source -> assertThat(source.getConfirmedBy()).isEqualTo("tester"));
  }

  @Test
  void transactionFailureRollsBackSourceChangesRemovalAndAuditTogether() {
    sync();
    var before = sources();
    assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
      jdbc.update("UPDATE cms_workshop_labor_raw SET working_cost_cent=888");
      jdbc.update("DELETE FROM cms_product_subject_cost_raw");
      sync();
      throw new IllegalStateException("simulate later publish failure");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(logs()).hasSize(3);
    assertThat(sources()).usingRecursiveComparison().isEqualTo(before);
  }
}
