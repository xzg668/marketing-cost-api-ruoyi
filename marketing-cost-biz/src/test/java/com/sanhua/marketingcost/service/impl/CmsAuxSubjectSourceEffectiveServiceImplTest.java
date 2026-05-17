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
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CmsAuxSubjectSourceEffectiveServiceImplTest {
  private CmsProductSubjectCostRawMapper subjectMapper;
  private CmsSubjectSettingRawMapper subjectSettingMapper;
  private CmsCostSourceEffectiveMapper effectiveMapper;
  private CmsCostSourceEffectiveLogMapper logMapper;
  private CmsPlanEligibilityService eligibilityService;
  private CmsAuxSubjectSourceEffectiveServiceImpl service;
  private final List<CmsCostSourceEffective> insertedEffective = new ArrayList<>();
  private final List<CmsCostSourceEffectiveLog> insertedLogs = new ArrayList<>();

  @BeforeEach
  void setUp() {
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    subjectSettingMapper = mock(CmsSubjectSettingRawMapper.class);
    effectiveMapper = mock(CmsCostSourceEffectiveMapper.class);
    logMapper = mock(CmsCostSourceEffectiveLogMapper.class);
    eligibilityService = mock(CmsPlanEligibilityService.class);
    service =
        new CmsAuxSubjectSourceEffectiveServiceImpl(
            subjectMapper, subjectSettingMapper, effectiveMapper, logMapper, eligibilityService);
    doAnswer(invocation -> {
          CmsCostSourceEffective row = invocation.getArgument(0);
          row.setId(2000L + insertedEffective.size());
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
  void generateDefaultSourcesCreatesNonPackagingAuxSubjectsAndKeepsCmsCentValues() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                subjectSetting("0201", "辅助焊料类"),
                subjectSetting("0202", "表面处理类"),
                subjectSetting("0205", "气体类"),
                subjectSetting("0208", "油类"),
                subjectSetting("0212", "工量夹切工具"),
                subjectSetting("0215", "包装辅料"),
                subjectSetting("0216", "电费类"),
                subjectSetting("0217", "水费类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                aux(1L, "1079900000536", "2026-01", "0201", "辅助焊料类", "40.00"),
                aux(2L, "1079900000536", "2026-01", "0202", "表面处理类", "60.00"),
                aux(3L, "1079900000536", "2026-01", "0205", "气体类", "52.00"),
                aux(4L, "1079900000536", "2026-01", "0208", "油类", "20.00"),
                aux(5L, "1079900000536", "2026-01", "0212", "工量夹切工具", "3.27"),
                aux(6L, "1079900000536", "2026-01", "0215", "包装辅料", "2.00"),
                aux(7L, "1079900000536", "2026-01", "0216", "电费类", "3.00"),
                aux(8L, "1079900000536", "2026-01", "0217", "水费类", "4.00"),
                aux(9L, "1079900000536", "2026-01", "9999", "禁用或未配置", "999.00"),
                nonAux(10L, "1079900000536", "2026-01", "0201", "40.00")));

    var response = service.generateDefaultSources(2026, "tester", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(7);
    assertThat(insertedEffective).hasSize(7);
    assertThat(source("0201").getAmountYuan()).isEqualByComparingTo("0.800000");
    assertThat(source("0202").getAmountYuan()).isEqualByComparingTo("0.600000");
    assertThat(source("0205").getAmountYuan()).isEqualByComparingTo("0.520000");
    assertThat(source("0208").getAmountYuan()).isEqualByComparingTo("0.200000");
    assertThat(source("0212").getAmountYuan()).isEqualByComparingTo("0.032700");
    assertThat(source("0216").getAmountYuan()).isEqualByComparingTo("0.030000");
    assertThat(source("0217").getAmountYuan()).isEqualByComparingTo("0.040000");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getSubjectCode)
        .doesNotContain("0215", "9999");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getSubjectName)
        .contains("辅助焊料类", "表面处理类")
        .doesNotContain("包装辅料", "禁用或未配置");
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsOnly("DEFAULT");
  }

  @Test
  void generateDefaultSourcesFallsBackWhenJanuaryAuxIsBlockedAndWritesBlockedLog() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(subjectSetting("0201", "辅助焊料类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                aux(1L, "A", "2026-01", "0201", "辅助焊料类", "40.00"),
                aux(2L, "A", "2026-04", "0201", "辅助焊料类", "80.00")));
    doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<String> parents = new ArrayList<>((Collection<String>) invocation.getArgument(0));
          @SuppressWarnings("unchecked")
          List<String> periods = new ArrayList<>((Collection<String>) invocation.getArgument(1));
          String parent = parents.get(0);
          String period = periods.get(0);
          if ("2026-01".equals(period)) {
            return Map.of(parent + "|" + period,
                PlanEligibility.fromPlanRow(parent, period, 1L, null, period, true, false, false, "辅料未审批"));
          }
          return Map.of(parent + "|" + period, PlanEligibility.allowed(parent, period, "允许生效"));
        })
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());

    var response = service.generateDefaultSources(2026, "tester", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(1);
    assertThat(response.getBlockedCount()).isEqualTo(1);
    assertThat(source("0201").getPeriod()).isEqualTo("2026-04");
    assertThat(source("0201").getAmountYuan()).isEqualByComparingTo("0.800000");
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .contains("BLOCKED", "DEFAULT");
  }

  @Test
  void refreshSourceUpdatesSubjectSourceAndLogsOldAndNewValues() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(subjectSetting("0201", "辅助焊料类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(aux(2L, "A", "2026-04", "0201", "辅助焊料类", "80.00")));
    CmsCostSourceEffective existing = existing("A", "0201", "2026-01", "0.400000");
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
    request.setSourceType("AUX_SUBJECT");
    request.setSubjectCode("0201");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("一月辅料异常");

    CmsCostSourceEffective result = service.refreshSource(request, "operator", "COMMERCIAL");

    assertThat(result.getPeriod()).isEqualTo("2026-04");
    assertThat(result.getDefaultFlag()).isZero();
    assertThat(updated).hasSize(1);
    assertThat(updated.get(0).getAmountYuan()).isEqualByComparingTo("0.800000");
    CmsCostSourceEffectiveLog log = insertedLogs.get(0);
    assertThat(log.getActionType()).isEqualTo("REFRESH");
    assertThat(log.getOldPeriod()).isEqualTo("2026-01");
    assertThat(log.getNewPeriod()).isEqualTo("2026-04");
    assertThat(log.getOldAmountYuan()).isEqualByComparingTo("0.400000");
    assertThat(log.getNewAmountYuan()).isEqualByComparingTo("0.800000");
    assertThat(log.getOperator()).isEqualTo("operator");
    assertThat(log.getMessage()).isEqualTo("一月辅料异常");
  }

  @Test
  void refreshParentPeriodUpdatesAllEnabledAuxSubjectsForOneParentYearPeriod() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                subjectSetting("0201", "辅助焊料类"),
                subjectSetting("0202", "表面处理类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                aux(2L, "A", "2026-04", "0201", "辅助焊料类", "80.00"),
                aux(3L, "A", "2026-04", "0202", "表面处理类", "60.00"),
                aux(4L, "B", "2026-04", "0201", "辅助焊料类", "90.00")));

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setNewPeriod("2026-04");
    request.setRefreshReason("按料号年度更新到四月");

    var response = service.refreshParentPeriod(request, "operator", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isEqualTo(2);
    assertThat(response.getUpdatedCount()).isZero();
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getParentCode)
        .containsOnly("A");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getSubjectCode)
        .containsExactlyInAnyOrder("0201", "0202");
    assertThat(insertedEffective).extracting(CmsCostSourceEffective::getPeriod)
        .containsOnly("2026-04");
    assertThat(insertedLogs).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsOnly("REFRESH");
  }

  @Test
  void refreshSourceWritesBlockedLogAndDoesNotUpdateWhenAuxIsNotApproved() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(subjectSetting("0201", "辅助焊料类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(aux(2L, "A", "2026-04", "0201", "辅助焊料类", "80.00")));
    doAnswer(invocation -> Map.of("A|2026-04",
            PlanEligibility.fromPlanRow("A", "2026-04", 1L, null, "2026-04", true, false, false, "辅料未审批")))
        .when(eligibilityService)
        .checkEligibility(anyCollection(), anyCollection(), anyString());

    CmsEffectiveSourceRefreshRequest request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(2026);
    request.setParentCode("A");
    request.setSourceType("AUX_SUBJECT");
    request.setSubjectCode("0201");
    request.setNewPeriod("2026-04");

    assertThatThrownBy(() -> service.refreshSource(request, "operator", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("辅料未审批");

    verify(effectiveMapper, never()).updateById(any(CmsCostSourceEffective.class));
    assertThat(insertedLogs).hasSize(1);
    CmsCostSourceEffectiveLog log = insertedLogs.get(0);
    assertThat(log.getActionType()).isEqualTo("BLOCKED");
    assertThat(log.getSubjectCode()).isEqualTo("0201");
    assertThat(log.getNewPeriod()).isEqualTo("2026-04");
    assertThat(log.getNewAmountYuan()).isEqualByComparingTo("0.800000");
    assertThat(log.getMessage()).isEqualTo("辅料未审批");
  }

  @Test
  void ensureDefaultSourcesOnlyInsertsMissingAndDoesNotOverwriteManualRefresh() {
    when(subjectSettingMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(subjectSetting("0201", "辅助焊料类")));
    when(subjectMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(aux(1L, "A", "2026-01", "0201", "辅助焊料类", "40.00")));
    CmsCostSourceEffective existing = existing("A", "0201", "2026-04", "0.800000");
    existing.setDefaultFlag(0);
    when(effectiveMapper.selectOne(any())).thenReturn(existing);

    var response = service.ensureDefaultSources(2026, "system", "COMMERCIAL");

    assertThat(response.getInsertedCount()).isZero();
    assertThat(response.getUpdatedCount()).isZero();
    assertThat(response.getSkippedCount()).isEqualTo(1);
    assertThat(insertedEffective).isEmpty();
    assertThat(insertedLogs).isEmpty();
    verify(effectiveMapper, never()).updateById(any(CmsCostSourceEffective.class));
  }

  private CmsCostSourceEffective source(String subjectCode) {
    return insertedEffective.stream()
        .filter(row -> subjectCode.equals(row.getSubjectCode()))
        .findFirst()
        .orElseThrow();
  }

  private CmsSubjectSettingRaw subjectSetting(String code, String name) {
    CmsSubjectSettingRaw setting = new CmsSubjectSettingRaw();
    setting.setFirstSubjectCode("02");
    setting.setFirstSubjectName("辅助材料");
    setting.setSecondSubjectCode(code);
    setting.setSecondSubjectName(name);
    setting.setBusinessUnitType("COMMERCIAL");
    return setting;
  }

  private CmsProductSubjectCostRaw aux(
      Long id, String parentCode, String period, String subjectCode, String subjectName, String materialPrice) {
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setId(id);
    row.setParentCode(parentCode);
    row.setPeriod(period);
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode(subjectCode);
    row.setSecondSubjectName(subjectName);
    row.setMaterialPrice(new BigDecimal(materialPrice));
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsProductSubjectCostRaw nonAux(
      Long id, String parentCode, String period, String subjectCode, String materialPrice) {
    CmsProductSubjectCostRaw row = aux(id, parentCode, period, subjectCode, "不是辅料", materialPrice);
    row.setFirstSubjectName("其他科目");
    return row;
  }

  private CmsCostSourceEffective existing(String parentCode, String subjectCode, String period, String amount) {
    CmsCostSourceEffective row = new CmsCostSourceEffective();
    row.setId(7L);
    row.setCostYear(2026);
    row.setSourceType("AUX_SUBJECT");
    row.setParentCode(parentCode);
    row.setPeriod(period);
    row.setSubjectCode(subjectCode);
    row.setSourceTable("cms_product_subject_cost_raw");
    row.setSourceRowIds("1");
    row.setAmountYuan(new BigDecimal(amount));
    row.setDefaultFlag(1);
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
