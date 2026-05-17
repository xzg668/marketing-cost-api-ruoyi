package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsCostQueryServiceImplTest {

  private CmsCostImportBatchMapper batchMapper;
  private CmsPlanCostRawMapper planMapper;
  private CmsWorkshopLaborRawMapper workshopMapper;
  private CmsProductSubjectCostRawMapper subjectMapper;
  private CmsSubjectSettingRawMapper subjectSettingMapper;
  private CmsCostSourceEffectiveMapper effectiveMapper;
  private CmsCostSourceEffectiveLogMapper effectiveLogMapper;
  private CmsCostEffectiveSourceEnsureService ensureService;
  private CmsCostQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    batchMapper = mock(CmsCostImportBatchMapper.class);
    planMapper = mock(CmsPlanCostRawMapper.class);
    workshopMapper = mock(CmsWorkshopLaborRawMapper.class);
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    subjectSettingMapper = mock(CmsSubjectSettingRawMapper.class);
    effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    effectiveLogMapper = mock(CmsCostSourceEffectiveLogMapper.class);
    ensureService = mock(CmsCostEffectiveSourceEnsureService.class);
    service =
        new CmsCostQueryServiceImpl(
            batchMapper,
            planMapper,
            workshopMapper,
            subjectMapper,
            subjectSettingMapper,
            effectiveMapper,
            effectiveLogMapper,
            ensureService);
  }

  @Test
  @DisplayName("T11 批次分页：按 batchNo/status 查询且分页稳定")
  void pageBatchesFiltersByBatchNoAndStatus() {
    Page<CmsCostImportBatch> returned = new Page<>(1, 20);
    returned.setTotal(1);
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setBatchNo("CMS001");
    returned.setRecords(List.of(batch));
    when(batchMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response = service.pageBatches("CMS", "DERIVED", 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).extracting(CmsCostImportBatch::getBatchNo).containsExactly("CMS001");
    String sql = capturedSql(batchMapper, CmsCostImportBatch.class);
    assertThat(sql).contains("batch_no").contains("status").contains("business_unit_type").contains("ORDER BY id DESC");
  }

  @Test
  @DisplayName("T11 原始计划行：按 batchNo 和 parentCode 查询")
  void pagePlanRowsFiltersByBatchAndParentCode() {
    when(batchMapper.selectList(any(Wrapper.class))).thenReturn(List.of(batch(10L)));
    Page<CmsPlanCostRaw> returned = new Page<>(2, 10);
    returned.setTotal(1);
    CmsPlanCostRaw row = new CmsPlanCostRaw();
    row.setParentCode("P-100");
    returned.setRecords(List.of(row));
    when(planMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response = service.pagePlanRows("CMS001", "P-100", 2026, null, 2, 10, "COMMERCIAL");

    assertThat(response.getRawType()).isEqualTo("PLAN");
    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).extracting(CmsPlanCostRaw::getParentCode).containsExactly("P-100");
    String sql = capturedSql(planMapper, CmsPlanCostRaw.class);
    assertThat(sql)
        .contains("import_batch_id")
        .contains("parent_code")
        .contains("effective_period")
        .contains("business_unit_type");
  }

  @Test
  @DisplayName("T11 科目原始行：按父件、期间、科目名称查询")
  void pageSubjectRowsFiltersByParentPeriodAndSubjectName() {
    Page<CmsProductSubjectCostRaw> returned = new Page<>(1, 20);
    returned.setTotal(1);
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setSecondSubjectName("辅助焊料类");
    returned.setRecords(List.of(row));
    when(subjectMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response =
        service.pageSubjectRows(
            null, "P-100", 2026, "2026-01", "0201", "辅助焊料", 1, 20, "COMMERCIAL");

    assertThat(response.getRawType()).isEqualTo("SUBJECT");
    assertThat(response.getList()).extracting(CmsProductSubjectCostRaw::getSecondSubjectName)
        .containsExactly("辅助焊料类");
    String sql = capturedSql(subjectMapper, CmsProductSubjectCostRaw.class);
    assertThat(sql)
        .contains("parent_code")
        .contains("period")
        .contains("second_subject_code")
        .contains("first_subject_name")
        .contains("second_subject_name")
        .contains("third_subject_name");
  }

  @Test
  @DisplayName("T11 科目设置：按一级科目、二级编码和二级科目查询")
  void pageSubjectSettingsFiltersBySubjectDictionaryFields() {
    Page<CmsSubjectSettingRaw> returned = new Page<>(1, 20);
    returned.setTotal(1);
    CmsSubjectSettingRaw row = new CmsSubjectSettingRaw();
    row.setFirstSubjectName("工资");
    row.setSecondSubjectCode("0302");
    row.setSecondSubjectName("辅助人员工资");
    returned.setRecords(List.of(row));
    when(subjectSettingMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response =
        service.pageSubjectSettings(null, "工资", "0302", "辅助人员", 1, 20, "COMMERCIAL");

    assertThat(response.getRawType()).isEqualTo("SUBJECT_SETTING");
    assertThat(response.getList()).extracting(CmsSubjectSettingRaw::getSecondSubjectName)
        .containsExactly("辅助人员工资");
    String sql = capturedSql(subjectSettingMapper, CmsSubjectSettingRaw.class);
    assertThat(sql)
        .contains("first_subject_name")
        .contains("second_subject_code")
        .contains("second_subject_name")
        .contains("business_unit_type");
  }

  @Test
  @DisplayName("T11 公共生效来源日志：按操作类型和来源类型查询")
  void pageEffectiveSourceLogsFiltersByActionAndSourceType() {
    Page<CmsCostSourceEffectiveLog> returned = new Page<>(1, 20);
    returned.setTotal(1);
    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setActionType("BLOCKED");
    returned.setRecords(List.of(log));
    when(effectiveLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response =
        service.pageEffectiveSourceLogs(
            2026, "P-100", "2026-01", "AUX_SUBJECT", "0201", "BLOCKED", 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsExactly("BLOCKED");
    String sql = capturedSql(effectiveLogMapper, CmsCostSourceEffectiveLog.class);
    assertThat(sql)
        .contains("cost_year")
        .contains("parent_code")
        .contains("new_period")
        .contains("source_type")
        .contains("subject_code")
        .contains("action_type")
        .contains("business_unit_type");
  }

  @Test
  @DisplayName("T11 公共生效来源：按年度、父件、来源类型查询")
  void pageEffectiveSourcesFiltersByYearParentAndSourceType() {
    Page<CmsCostSourceEffective> returned = new Page<>(1, 20);
    returned.setTotal(1);
    CmsCostSourceEffective row = new CmsCostSourceEffective();
    row.setParentCode("P-100");
    returned.setRecords(List.of(row));
    when(effectiveMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response =
        service.pageEffectiveSources(2026, "P-100", "2026-01", "AUX_SUBJECT", "0201", 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffective::getParentCode)
        .containsExactly("P-100");
    String sql = capturedSql(effectiveMapper, CmsCostSourceEffective.class);
    assertThat(sql)
        .contains("cost_year")
        .contains("parent_code")
        .contains("period")
        .contains("source_type")
        .contains("subject_code")
        .contains("business_unit_type");
    verify(ensureService).ensureDefaultSources(2026, "SYSTEM_AUTO", "COMMERCIAL");
  }

  @Test
  @DisplayName("T11 公共生效来源：成品料号支持多值精确查询")
  void pageEffectiveSourcesFiltersByMultipleParentCodes() {
    Page<CmsCostSourceEffective> returned = new Page<>(1, 20);
    returned.setTotal(2);
    CmsCostSourceEffective first = new CmsCostSourceEffective();
    first.setParentCode("P-100");
    CmsCostSourceEffective second = new CmsCostSourceEffective();
    second.setParentCode("P-200");
    returned.setRecords(List.of(first, second));
    when(effectiveMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response =
        service.pageEffectiveSources(2026, "P-100\nP-200，P-100", null, null, null, 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(2);
    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffective::getParentCode)
        .containsExactly("P-100", "P-200");
    String sql = capturedSql(effectiveMapper, CmsCostSourceEffective.class);
    assertThat(sql).contains("parent_code").contains("IN");
  }

  @Test
  @DisplayName("T11 公共生效来源：关联计划成本未审批项并保留原值")
  void pageEffectiveSourcesFillsUnapprovedItemsFromPlanRows() {
    Page<CmsCostSourceEffective> returned = new Page<>(1, 20);
    returned.setTotal(2);
    CmsCostSourceEffective first = new CmsCostSourceEffective();
    first.setParentCode("P-100");
    first.setPeriod("2026-04");
    CmsCostSourceEffective second = new CmsCostSourceEffective();
    second.setParentCode("P-200");
    second.setPeriod("2026-04");
    returned.setRecords(List.of(first, second));
    when(effectiveMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    CmsPlanCostRaw firstPlan = new CmsPlanCostRaw();
    firstPlan.setParentCode("P-100");
    firstPlan.setEffectivePeriod("2026-04");
    firstPlan.setUnapprovedItems("工时");
    CmsPlanCostRaw duplicatedPlan = new CmsPlanCostRaw();
    duplicatedPlan.setParentCode("P-100");
    duplicatedPlan.setEffectivePeriod("2026-04");
    duplicatedPlan.setUnapprovedItems("工时+辅料");
    CmsPlanCostRaw secondPlan = new CmsPlanCostRaw();
    secondPlan.setParentCode("P-200");
    secondPlan.setEffectivePeriod("2026-04");
    secondPlan.setUnapprovedItems("辅料");
    when(planMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(firstPlan, duplicatedPlan, secondPlan));

    var response =
        service.pageEffectiveSources(2026, "P-100 P-200", null, null, null, 1, 20, "COMMERCIAL");

    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffective::getUnapprovedItems)
        .containsExactly("工时;工时+辅料", "辅料");
    String sql = capturedSelectListSql(planMapper);
    assertThat(sql)
        .contains("parent_code")
        .contains("effective_period")
        .contains("business_unit_type");
  }

  @Test
  @DisplayName("T11 公共生效来源：工资科目展示直接人工写死，辅助员工取科目成本原始行")
  void pageEffectiveSourcesFillsSalarySubjectDisplay() {
    Page<CmsCostSourceEffective> returned = new Page<>(1, 20);
    returned.setTotal(2);
    CmsCostSourceEffective direct = new CmsCostSourceEffective();
    direct.setParentCode("P-100");
    direct.setPeriod("2026-04");
    direct.setSourceType("SALARY_DIRECT");
    direct.setSourceRowIds("11");
    CmsCostSourceEffective indirect = new CmsCostSourceEffective();
    indirect.setParentCode("P-100");
    indirect.setPeriod("2026-04");
    indirect.setSourceType("SALARY_INDIRECT");
    indirect.setSourceRowIds("21");
    returned.setRecords(List.of(direct, indirect));
    when(effectiveMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    CmsProductSubjectCostRaw indirectRaw = new CmsProductSubjectCostRaw();
    indirectRaw.setId(21L);
    indirectRaw.setSecondSubjectCode("0302");
    indirectRaw.setSecondSubjectName("辅助人员工资");
    when(subjectMapper.selectBatchIds(any())).thenReturn(List.of(indirectRaw));

    var response =
        service.pageEffectiveSources(2026, "P-100", null, null, null, 1, 20, "COMMERCIAL");

    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffective::getSubjectCode)
        .containsExactly("0301", "0302");
    assertThat(response.getRecords())
        .extracting(CmsCostSourceEffective::getSubjectName)
        .containsExactly("直接人工工资", "辅助人员工资");
  }

  @Test
  @DisplayName("T11 batchNo 查无批次：原始行分页直接返回空结果")
  void pageRowsReturnsEmptyWhenBatchNoNotFound() {
    when(batchMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    var response = service.pagePlanRows("NOT_FOUND", null, null, null, 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isZero();
    assertThat(response.getList()).isEmpty();
  }

  private CmsCostImportBatch batch(Long id) {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setId(id);
    batch.setBatchNo("CMS001");
    return batch;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T> String capturedSql(Object mapper, Class<T> entityType) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    if (mapper == batchMapper) {
      verify(batchMapper).selectPage(any(Page.class), captor.capture());
    } else if (mapper == planMapper) {
      verify(planMapper).selectPage(any(Page.class), captor.capture());
    } else if (mapper == subjectMapper) {
      verify(subjectMapper).selectPage(any(Page.class), captor.capture());
    } else if (mapper == subjectSettingMapper) {
      verify(subjectSettingMapper).selectPage(any(Page.class), captor.capture());
    } else if (mapper == effectiveMapper) {
      verify(effectiveMapper).selectPage(any(Page.class), captor.capture());
    } else if (mapper == effectiveLogMapper) {
      verify(effectiveLogMapper).selectPage(any(Page.class), captor.capture());
    }
    return captor.getValue().getCustomSqlSegment();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private String capturedSelectListSql(CmsPlanCostRawMapper mapper) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }
}
