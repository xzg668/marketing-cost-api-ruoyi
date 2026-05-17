package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CmsSalaryCostSourceEffectiveServiceImplTest {
  private CmsWorkshopLaborRawMapper workshopMapper;
  private CmsProductSubjectCostRawMapper subjectMapper;
  private CmsSubjectSettingRawMapper subjectSettingMapper;
  private CmsCostSourceEffectiveMapper effectiveMapper;
  private CmsCostSourceEffectiveLogMapper logMapper;
  private CmsPlanEligibilityService eligibilityService;
  private CmsSalaryCostSourceEffectiveServiceImpl service;
  private final List<CmsCostSourceEffective> insertedEffective = new ArrayList<>();
  private final List<CmsCostSourceEffectiveLog> insertedLogs = new ArrayList<>();

  @BeforeEach
  void setUp() {
    workshopMapper = mock(CmsWorkshopLaborRawMapper.class);
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    subjectSettingMapper = mock(CmsSubjectSettingRawMapper.class);
    effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    logMapper = mock(CmsCostSourceEffectiveLogMapper.class);
    eligibilityService = mock(CmsPlanEligibilityService.class);
    service =
        new CmsSalaryCostSourceEffectiveServiceImpl(
            workshopMapper,
            subjectMapper,
            subjectSettingMapper,
            effectiveMapper,
            logMapper,
            eligibilityService);
    doAnswer(invocation -> {
          CmsCostSourceEffective row = invocation.getArgument(0);
          row.setId(1000L + insertedEffective.size());
          insertedEffective.add(copyEffective(row));
          return 1;
        })
        .when(effectiveMapper)
        .insert(any(CmsCostSourceEffective.class));
    doAnswer(invocation -> {
          insertedLogs.add(copyLog(invocation.getArgument(0)));
          return 1;
        })
        .when(logMapper)
        .insert(any(CmsCostSourceEffectiveLog.class));
    when(effectiveMapper.selectOne(any())).thenReturn(null);
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirectSubjectSetting("0302")));
    doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> parents = new ArrayList<>((Collection<String>) invocation.getArgument(0));
          @SuppressWarnings("unchecked")
          List<String> periods = new ArrayList<>((Collection<String>) invocation.getArgument(1));
          String parent = parents.get(0);
          String period = periods.get(0);
          return Map.of(parent + "|" + period, PlanEligibility.allowed(parent, period, "允许生效"));
        })
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());
  }

  @Test
  void generateDefaultSourcesCreatesDirectAndIndirectSalarySourcesFromCmsRawCentValues() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(workshop(10L, "1079900000536", "2026-01", "400")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirect(20L, "1079900000536", "2026-01", "22")));

    var response = service.generateDefaultSources(2026, "tester", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(2);
    assertThat(insertedEffective).hasSize(2);
    CmsCostSourceEffective direct = source("SALARY_DIRECT", "1079900000536");
    assertThat(direct.getPeriod()).isEqualTo("2026-01");
    assertThat(direct.getSourceTable()).isEqualTo("cms_workshop_labor_raw");
    assertThat(direct.getSubjectCode()).isEqualTo("0301");
    assertThat(direct.getSubjectName()).isEqualTo("直接人工工资");
    assertThat(direct.getAmountYuan()).isEqualByComparingTo("4.000000");
    CmsCostSourceEffective indirect = source("SALARY_INDIRECT", "1079900000536");
    assertThat(indirect.getPeriod()).isEqualTo("2026-01");
    assertThat(indirect.getSourceTable()).isEqualTo("cms_product_subject_cost_raw");
    assertThat(indirect.getSubjectCode()).isEqualTo("0302");
    assertThat(indirect.getSubjectName()).isEqualTo("辅助人员工资");
    assertThat(indirect.getAmountYuan()).isEqualByComparingTo("0.220000");
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsOnly("DEFAULT");
  }

  @Test
  void indirectSalarySubjectCodeComesFromSubjectSettingDictionary() {
    when(workshopMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirectSubjectSetting("0399")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirectWithCode(20L, "A", "2026-01", "0399", "22")));

    var response = service.generateDefaultSources(2026, "tester", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(1);
    CmsCostSourceEffective indirect = source("SALARY_INDIRECT", "A");
    assertThat(indirect.getSubjectCode()).isEqualTo("0399");
    assertThat(indirect.getSubjectName()).isEqualTo("辅助人员工资");
    assertThat(indirect.getAmountYuan()).isEqualByComparingTo("0.220000");
  }

  @Test
  void generateDefaultSourcesFallsBackToEarliestAllowedPeriodPerParentAndKeepsOthersAtJanuary() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                workshop(10L, "A", "2026-01", "100"),
                workshop(11L, "A", "2026-04", "400"),
                workshop(12L, "B", "2026-01", "200")));
    when(subjectMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> parents = new ArrayList<>((Collection<String>) invocation.getArgument(0));
          @SuppressWarnings("unchecked")
          List<String> periods = new ArrayList<>((Collection<String>) invocation.getArgument(1));
          String parent = parents.get(0);
          String period = periods.get(0);
          if ("A".equals(parent) && "2026-01".equals(period)) {
            return Map.of(parent + "|" + period,
                PlanEligibility.fromPlanRow(parent, period, 1L, null, period, false, true, true, "工时未审批"));
          }
          return Map.of(parent + "|" + period, PlanEligibility.allowed(parent, period, "允许生效"));
        })
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());

    var response = service.generateDefaultSources(2026, "tester", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(2);
    assertThat(response.getBlockedCount()).isEqualTo(1);
    assertThat(source("SALARY_DIRECT", "A").getPeriod()).isEqualTo("2026-04");
    assertThat(source("SALARY_DIRECT", "A").getAmountYuan()).isEqualByComparingTo("4.000000");
    assertThat(source("SALARY_DIRECT", "B").getPeriod()).isEqualTo("2026-01");
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .contains("BLOCKED", "DEFAULT");
  }

  @Test
  void refreshSourceUpdatesCurrentEffectiveSourceAndLogsOldAndNewValues() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(workshop(11L, "A", "2026-04", "400")));
    CmsCostSourceEffective existing = new CmsCostSourceEffective();
    existing.setId(7L);
    existing.setCostYear(2026);
    existing.setSourceType("SALARY_DIRECT");
    existing.setParentCode("A");
    existing.setPeriod("2026-01");
    existing.setSubjectCode("");
    existing.setSourceTable("cms_workshop_labor_raw");
    existing.setSourceRowIds("10");
    existing.setAmountYuan(new BigDecimal("1.000000"));
    existing.setDefaultFlag(1);
    existing.setBusinessUnitType("COMMERCIAL");
    when(effectiveMapper.selectOne(any())).thenReturn(existing);
    List<CmsCostSourceEffective> updated = new ArrayList<>();
    doAnswer(invocation -> {
          updated.add(copyEffective(invocation.getArgument(0)));
          return 1;
        })
        .when(effectiveMapper)
        .updateById(any(CmsCostSourceEffective.class));

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setSourceType("SALARY_DIRECT");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("一月工资异常");

    CmsCostSourceEffective result = service.refreshSource(request, "operator", "COMMERCIAL");

    assertThat(result.getPeriod()).isEqualTo("2026-04");
    assertThat(result.getDefaultFlag()).isZero();
    assertThat(updated).hasSize(1);
    CmsCostSourceEffective updatedSource = updated.get(0);
    assertThat(updatedSource.getAmountYuan()).isEqualByComparingTo("4.000000");
    CmsCostSourceEffectiveLog refreshLog = insertedLogs.get(0);
    assertThat(refreshLog.getActionType()).isEqualTo("REFRESH");
    assertThat(refreshLog.getOldPeriod()).isEqualTo("2026-01");
    assertThat(refreshLog.getNewPeriod()).isEqualTo("2026-04");
    assertThat(refreshLog.getOldAmountYuan()).isEqualByComparingTo("1.000000");
    assertThat(refreshLog.getNewAmountYuan()).isEqualByComparingTo("4.000000");
    assertThat(refreshLog.getOperator()).isEqualTo("operator");
    assertThat(refreshLog.getMessage()).isEqualTo("一月工资异常");
  }

  @Test
  void refreshParentPeriodUpdatesDirectAndIndirectSalaryForOneParentYearPeriod() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(workshop(11L, "A", "2026-04", "400")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirect(21L, "A", "2026-04", "22")));

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("按料号年度更新到四月");

    var response = service.refreshParentPeriod(request, "operator", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(2);
    assertThat(response.getUpdatedCount()).isZero();
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getSourceType)
        .containsExactlyInAnyOrder("SALARY_DIRECT", "SALARY_INDIRECT");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getPeriod)
        .containsOnly("2026-04");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getDefaultFlag)
        .containsOnly(0);
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsOnly("REFRESH");
  }

  @Test
  void refreshSourceWritesBlockedLogAndDoesNotUpdateWhenPlanEligibilityBlocksLabor() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(workshop(11L, "A", "2026-04", "400")));
    doAnswer(invocation -> Map.of("A|2026-04",
            PlanEligibility.fromPlanRow("A", "2026-04", 1L, null, "2026-04", false, true, true, "工时未审批")))
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setSourceType("SALARY_DIRECT");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("尝试刷新到未审批期间");

    assertThatThrownBy(() -> service.refreshSource(request, "operator", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("工时未审批");

    verify(effectiveMapper, never()).updateById(any(CmsCostSourceEffective.class));
    assertThat(insertedLogs).hasSize(1);
    CmsCostSourceEffectiveLog blockedLog = insertedLogs.get(0);
    assertThat(blockedLog.getActionType()).isEqualTo("BLOCKED");
    assertThat(blockedLog.getSourceType()).isEqualTo("SALARY_DIRECT");
    assertThat(blockedLog.getParentCode()).isEqualTo("A");
    assertThat(blockedLog.getNewPeriod()).isEqualTo("2026-04");
    assertThat(blockedLog.getNewAmountYuan()).isEqualByComparingTo("4.000000");
    assertThat(blockedLog.getMessage()).isEqualTo("工时未审批");
  }

  @Test
  void refreshSourceWritesBlockedLogAndDoesNotUpdateIndirectSalaryWhenAuxSubjectUnapproved() {
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(indirect(21L, "A", "2026-04", "22")));
    doAnswer(invocation -> Map.of("A|2026-04",
            PlanEligibility.fromPlanRow("A", "2026-04", 1L, null, "2026-04", true, false, false, "辅料未审批")))
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setSourceType("SALARY_INDIRECT");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("尝试刷新到辅料未审批期间");

    assertThatThrownBy(() -> service.refreshSource(request, "operator", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("辅料未审批");

    verify(effectiveMapper, never()).updateById(any(CmsCostSourceEffective.class));
    assertThat(insertedLogs).hasSize(1);
    CmsCostSourceEffectiveLog blockedLog = insertedLogs.get(0);
    assertThat(blockedLog.getActionType()).isEqualTo("BLOCKED");
    assertThat(blockedLog.getSourceType()).isEqualTo("SALARY_INDIRECT");
    assertThat(blockedLog.getParentCode()).isEqualTo("A");
    assertThat(blockedLog.getNewPeriod()).isEqualTo("2026-04");
    assertThat(blockedLog.getNewAmountYuan()).isEqualByComparingTo("0.220000");
    assertThat(blockedLog.getMessage()).isEqualTo("辅料未审批");
  }

  @Test
  void ensureDefaultSourcesOnlyInsertsMissingAndDoesNotOverwriteManualRefresh() {
    when(workshopMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(workshop(11L, "A", "2026-01", "100")));
    when(subjectMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    CmsCostSourceEffective existing = new CmsCostSourceEffective();
    existing.setId(7L);
    existing.setCostYear(2026);
    existing.setSourceType("SALARY_DIRECT");
    existing.setParentCode("A");
    existing.setPeriod("2026-04");
    existing.setSubjectCode("");
    existing.setAmountYuan(new BigDecimal("4.000000"));
    existing.setDefaultFlag(0);
    existing.setBusinessUnitType("COMMERCIAL");
    when(effectiveMapper.selectOne(any())).thenReturn(existing);

    var response = service.ensureDefaultSources(2026, "system", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isZero();
    assertThat(response.getUpdatedCount()).isZero();
    assertThat(response.getSkippedCount()).isEqualTo(1);
    assertThat(insertedEffective).isEmpty();
    assertThat(insertedLogs).isEmpty();
    verify(effectiveMapper, never()).updateById(any(CmsCostSourceEffective.class));
  }

  private CmsCostSourceEffective source(String sourceType, String parentCode) {
    return insertedEffective.stream()
        .filter(row -> sourceType.equals(row.getSourceType()) && parentCode.equals(row.getParentCode()))
        .findFirst()
        .orElseThrow();
  }

  private CmsWorkshopLaborRaw workshop(Long id, String parentCode, String period, String workingCostCent) {
    CmsWorkshopLaborRaw row = new CmsWorkshopLaborRaw();
    row.setId(id);
    row.setParentCode(parentCode);
    row.setPeriod(period);
    row.setWorkingCostCent(new BigDecimal(workingCostCent));
    return row;
  }

  private CmsProductSubjectCostRaw indirect(Long id, String parentCode, String period, String materialPrice) {
    return indirectWithCode(id, parentCode, period, "0302", materialPrice);
  }

  private CmsProductSubjectCostRaw indirectWithCode(
      Long id, String parentCode, String period, String subjectCode, String materialPrice) {
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setId(id);
    row.setParentCode(parentCode);
    row.setPeriod(period);
    row.setFirstSubjectName("工资");
    row.setSecondSubjectCode(subjectCode);
    row.setSecondSubjectName("辅助人员工资");
    row.setMaterialPrice(new BigDecimal(materialPrice));
    return row;
  }

  private CmsSubjectSettingRaw indirectSubjectSetting(String secondSubjectCode) {
    CmsSubjectSettingRaw row = new CmsSubjectSettingRaw();
    row.setFirstSubjectCode("03");
    row.setFirstSubjectName("工资");
    row.setSecondSubjectCode(secondSubjectCode);
    row.setSecondSubjectName("辅助人员工资");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsCostSourceEffective copyEffective(CmsCostSourceEffective row) {
    CmsCostSourceEffective copy = new CmsCostSourceEffective();
    copy.setId(row.getId());
    copy.setCostYear(row.getCostYear());
    copy.setSourceType(row.getSourceType());
    copy.setParentCode(row.getParentCode());
    copy.setPeriod(row.getPeriod());
    copy.setSubjectCode(row.getSubjectCode());
    copy.setSubjectName(row.getSubjectName());
    copy.setSourceTable(row.getSourceTable());
    copy.setSourceRowIds(row.getSourceRowIds());
    copy.setAmountYuan(row.getAmountYuan());
    copy.setDefaultFlag(row.getDefaultFlag());
    copy.setRefreshReason(row.getRefreshReason());
    copy.setConfirmedBy(row.getConfirmedBy());
    copy.setBusinessUnitType(row.getBusinessUnitType());
    return copy;
  }

  private CmsCostSourceEffectiveLog copyLog(CmsCostSourceEffectiveLog row) {
    CmsCostSourceEffectiveLog copy = new CmsCostSourceEffectiveLog();
    copy.setEffectiveSourceId(row.getEffectiveSourceId());
    copy.setCostYear(row.getCostYear());
    copy.setSourceType(row.getSourceType());
    copy.setParentCode(row.getParentCode());
    copy.setOldPeriod(row.getOldPeriod());
    copy.setNewPeriod(row.getNewPeriod());
    copy.setSubjectCode(row.getSubjectCode());
    copy.setSubjectName(row.getSubjectName());
    copy.setOldAmountYuan(row.getOldAmountYuan());
    copy.setNewAmountYuan(row.getNewAmountYuan());
    copy.setActionType(row.getActionType());
    copy.setMessage(row.getMessage());
    copy.setOperator(row.getOperator());
    copy.setBusinessUnitType(row.getBusinessUnitType());
    return copy;
  }
}
