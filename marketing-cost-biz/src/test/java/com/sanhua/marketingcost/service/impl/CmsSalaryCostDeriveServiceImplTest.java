package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CmsSalaryCostDeriveResponse;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsCostDeriveLog;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.mapper.CmsCostDeriveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.service.CmsPlanEligibilityService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsSalaryCostDeriveServiceImplTest {
  private CmsCostImportBatchMapper batchMapper;
  private CmsWorkshopLaborRawMapper workshopMapper;
  private CmsProductSubjectCostRawMapper subjectMapper;
  private SalaryCostMapper salaryCostMapper;
  private CmsCostDeriveLogMapper deriveLogMapper;
  private CmsPlanEligibilityService eligibilityService;
  private CmsSalaryCostDeriveServiceImpl service;

  @BeforeEach
  void setUp() {
    batchMapper = mock(CmsCostImportBatchMapper.class);
    workshopMapper = mock(CmsWorkshopLaborRawMapper.class);
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    salaryCostMapper = mock(SalaryCostMapper.class);
    deriveLogMapper = mock(CmsCostDeriveLogMapper.class);
    eligibilityService = mock(CmsPlanEligibilityService.class);
    service =
        new CmsSalaryCostDeriveServiceImpl(
            batchMapper, workshopMapper, subjectMapper, salaryCostMapper, deriveLogMapper, eligibilityService);
    when(batchMapper.selectById(10L)).thenReturn(batch(10L));
  }

  @Test
  void derivesSalaryFromEarliestPeriodAndConvertsIndirectLaborCentToYuan() {
    when(workshopMapper.selectList(any()))
        .thenReturn(
            List.of(
                workshop("1079900000536", "2026-01", "200.000000", "2.000000"),
                workshop("1079900000536", "2026-01", "200.000000", "2.000000"),
                workshop("1079900000536", "2026-03", "900.000000", "9.000000")));
    when(subjectMapper.selectList(any()))
        .thenReturn(List.of(subject("1079900000536", "2026-04", "22.000000")));
    when(eligibilityService.checkDirectLaborEligibility(any(), any()))
        .thenReturn(Map.of("1079900000536", PlanEligibility.allowed("1079900000536", "允许")));

    CmsSalaryCostDeriveResponse response = service.deriveSalaryCosts(10L);

    assertThat(response.getSalaryInsertCount()).isEqualTo(1);
    assertThat(response.getSalarySkipCount()).isZero();
    assertThat(response.getSalaryBlockedCount()).isZero();

    ArgumentCaptor<SalaryCost> salaryCaptor = ArgumentCaptor.forClass(SalaryCost.class);
    verify(salaryCostMapper).insert(salaryCaptor.capture());
    SalaryCost salary = salaryCaptor.getValue();
    assertThat(salary.getMaterialCode()).isEqualTo("1079900000536");
    assertThat(salary.getDirectLaborCost()).isEqualByComparingTo("4.000000");
    assertThat(salary.getIndirectLaborCost()).isEqualByComparingTo("0.220000");
    assertThat(salary.getPeriod()).isEqualTo("2026-01");
    assertThat(salary.getSource()).isEqualTo("CMS");
    assertThat(salary.getLockStatus()).isEqualTo("LOCKED");
    assertThat(salary.getBusinessUnitType()).isEqualTo("COMMERCIAL");

    ArgumentCaptor<CmsCostImportBatch> batchCaptor = ArgumentCaptor.forClass(CmsCostImportBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getSalaryInsertCount()).isEqualTo(1);

    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("INSERTED");
    assertThat(logCaptor.getValue().getAmount()).isEqualByComparingTo("4.220000");
  }

  @Test
  void skipsWhenCmsSalaryAlreadyLockedAndDoesNotOverwriteLaterMonthChange() {
    when(workshopMapper.selectList(any()))
        .thenReturn(
            List.of(
                workshop("A", "2026-01", "200.000000", "2.000000"),
                workshop("A", "2026-03", "900.000000", "9.000000")));
    when(subjectMapper.selectList(any())).thenReturn(List.of());
    when(eligibilityService.checkDirectLaborEligibility(any(), any()))
        .thenReturn(Map.of("A", PlanEligibility.allowed("A", "允许")));
    SalaryCost existing = new SalaryCost();
    existing.setId(99L);
    existing.setMaterialCode("A");
    existing.setSource("CMS");
    existing.setDirectLaborCost(new BigDecimal("88.000000"));
    when(salaryCostMapper.selectOne(any())).thenReturn(existing);

    CmsSalaryCostDeriveResponse response = service.deriveSalaryCosts(10L);

    assertThat(response.getSalaryInsertCount()).isZero();
    assertThat(response.getSalarySkipCount()).isEqualTo(1);
    verify(salaryCostMapper, never()).insert(any(SalaryCost.class));
    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("SKIPPED");
    assertThat(logCaptor.getValue().getPeriod()).isEqualTo("2026-01");
    assertThat(logCaptor.getValue().getAmount()).isEqualByComparingTo("2.000000");
  }

  @Test
  void blocksDirectLaborWhenPlanEligibilityRejectedButStillWritesIndirectLabor() {
    when(workshopMapper.selectList(any()))
        .thenReturn(List.of(workshop("A", "2026-01", "400.000000", "4.000000")));
    when(subjectMapper.selectList(any())).thenReturn(List.of(subject("A", "2026-01", "22.000000")));
    PlanEligibility blocked =
        PlanEligibility.fromPlanRow("A", "2026-01", 1L, null, "2026-01", false, true, true, "工时未审批");
    when(eligibilityService.checkDirectLaborEligibility(any(), any())).thenReturn(Map.of("A", blocked));

    CmsSalaryCostDeriveResponse response = service.deriveSalaryCosts(10L);

    assertThat(response.getSalaryInsertCount()).isEqualTo(1);
    assertThat(response.getSalaryBlockedCount()).isEqualTo(1);
    ArgumentCaptor<SalaryCost> salaryCaptor = ArgumentCaptor.forClass(SalaryCost.class);
    verify(salaryCostMapper).insert(salaryCaptor.capture());
    assertThat(salaryCaptor.getValue().getDirectLaborCost()).isEqualByComparingTo("0.000000");
    assertThat(salaryCaptor.getValue().getIndirectLaborCost()).isEqualByComparingTo("0.220000");

    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
    assertThat(logCaptor.getAllValues()).extracting(CmsCostDeriveLog::getStatus)
        .containsExactly("BLOCKED", "INSERTED");
    assertThat(logCaptor.getAllValues().get(0).getDeriveType()).isEqualTo("SALARY_DIRECT");
  }

  @Test
  void blocksIndirectLaborWhenAuxSubjectUnapprovedButStillWritesDirectLabor() {
    when(workshopMapper.selectList(any()))
        .thenReturn(List.of(workshop("A", "2026-01", "400.000000", "4.000000")));
    when(subjectMapper.selectList(any())).thenReturn(List.of(subject("A", "2026-01", "22.000000")));
    PlanEligibility blocked =
        PlanEligibility.fromPlanRow("A", "2026-01", 1L, null, "2026-01", true, false, false, "辅料未审批");
    when(eligibilityService.checkDirectLaborEligibility(any(), any())).thenReturn(Map.of("A", blocked));

    CmsSalaryCostDeriveResponse response = service.deriveSalaryCosts(10L);

    assertThat(response.getSalaryInsertCount()).isEqualTo(1);
    assertThat(response.getSalaryBlockedCount()).isEqualTo(1);
    ArgumentCaptor<SalaryCost> salaryCaptor = ArgumentCaptor.forClass(SalaryCost.class);
    verify(salaryCostMapper).insert(salaryCaptor.capture());
    assertThat(salaryCaptor.getValue().getDirectLaborCost()).isEqualByComparingTo("4.000000");
    assertThat(salaryCaptor.getValue().getIndirectLaborCost()).isEqualByComparingTo("0.000000");

    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
    assertThat(logCaptor.getAllValues()).extracting(CmsCostDeriveLog::getStatus)
        .containsExactly("BLOCKED", "INSERTED");
    assertThat(logCaptor.getAllValues().get(0).getDeriveType()).isEqualTo("SALARY_INDIRECT");
    assertThat(logCaptor.getAllValues().get(0).getMessage()).isEqualTo("辅料未审批");
  }

  @Test
  void rejectsMissingBatch() {
    when(batchMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> service.deriveSalaryCosts(99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");
  }

  private CmsCostImportBatch batch(Long id) {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setId(id);
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private CmsWorkshopLaborRaw workshop(
      String parentCode, String period, String workingCostCent, String workingCostYuan) {
    CmsWorkshopLaborRaw row = new CmsWorkshopLaborRaw();
    row.setImportBatchId(10L);
    row.setRowNo(4);
    row.setPeriod(period);
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setFirstUnitName("商用四通阀事业部");
    row.setWorkingCostCent(new BigDecimal(workingCostCent));
    row.setWorkingCostYuan(new BigDecimal(workingCostYuan));
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private CmsProductSubjectCostRaw subject(String parentCode, String period, String materialPrice) {
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setImportBatchId(10L);
    row.setRowNo(4);
    row.setPeriod(period);
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setFirstUnitName("商用部品事业部");
    row.setFirstSubjectName("工资");
    row.setSecondSubjectName("辅助人员工资");
    row.setMaterialPrice(new BigDecimal(materialPrice));
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }
}
