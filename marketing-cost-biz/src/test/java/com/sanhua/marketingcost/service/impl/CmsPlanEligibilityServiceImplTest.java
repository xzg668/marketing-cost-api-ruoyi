package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.PlanEligibility;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CmsPlanEligibilityServiceImplTest {
  private CmsPlanCostRawMapper planCostRawMapper;
  private CmsPlanEligibilityServiceImpl service;

  @BeforeEach
  void setUp() {
    planCostRawMapper = mock(CmsPlanCostRawMapper.class);
    service = new CmsPlanEligibilityServiceImpl(planCostRawMapper);
  }

  @Test
  void checkEligibilityBlocksDirectLaborOnlyWhenUnapprovedItemsContainsWorkingHour() {
    CmsPlanCostRaw row = plan(1L, "A", LocalDate.of(2026, 1, 1), "工时");
    row.setEffectivePeriod("2026-01");
    when(planCostRawMapper.selectList(any())).thenReturn(List.of(row));

    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of("A"), List.of("2026-01"), "COMMERCIAL");

    PlanEligibility eligibility = result.get("A|2026-01");
    assertThat(eligibility.isDirectLaborAllowed()).isFalse();
    assertThat(eligibility.isIndirectLaborAllowed()).isTrue();
    assertThat(eligibility.isAuxSubjectAllowed()).isTrue();
    assertThat(eligibility.getReason()).isEqualTo("工时未审批");
    assertThat(eligibility.getEffectivePeriod()).isEqualTo("2026-01");
  }

  @Test
  void checkEligibilityBlocksIndirectLaborAndAuxWhenUnapprovedItemsContainsAuxSubject() {
    CmsPlanCostRaw row = plan(1L, "A", LocalDate.of(2026, 1, 1), "辅料");
    row.setEffectivePeriod("2026-01");
    when(planCostRawMapper.selectList(any())).thenReturn(List.of(row));

    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of("A"), List.of("2026-01"), "COMMERCIAL");

    PlanEligibility eligibility = result.get("A|2026-01");
    assertThat(eligibility.isDirectLaborAllowed()).isTrue();
    assertThat(eligibility.isIndirectLaborAllowed()).isFalse();
    assertThat(eligibility.isAuxSubjectAllowed()).isFalse();
    assertThat(eligibility.getReason()).isEqualTo("辅料未审批");
  }

  @Test
  void checkEligibilityBlocksAllWhenUnapprovedItemsContainsWorkingHourAndAuxSubject() {
    CmsPlanCostRaw row = plan(1L, "A", LocalDate.of(2026, 1, 1), "工时;辅料");
    row.setEffectivePeriod("2026-01");
    when(planCostRawMapper.selectList(any())).thenReturn(List.of(row));

    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of("A"), List.of("2026-01"), "COMMERCIAL");

    PlanEligibility eligibility = result.get("A|2026-01");
    assertThat(eligibility.isDirectLaborAllowed()).isFalse();
    assertThat(eligibility.isIndirectLaborAllowed()).isFalse();
    assertThat(eligibility.isAuxSubjectAllowed()).isFalse();
    assertThat(eligibility.getReason()).isEqualTo("工时、辅料未审批");
  }

  @Test
  void checkEligibilityAllowsAllWhenUnapprovedItemsBlankOrPlanRowMissing() {
    CmsPlanCostRaw blank = plan(1L, "A", LocalDate.of(2026, 1, 1), "");
    blank.setEffectivePeriod("2026-01");
    CmsPlanCostRaw nullValue = plan(2L, "B", LocalDate.of(2026, 1, 1), null);
    nullValue.setEffectivePeriod("2026-01");
    when(planCostRawMapper.selectList(any())).thenReturn(List.of(blank, nullValue));

    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of("A", "B", "C"), List.of("2026-01"), "COMMERCIAL");

    assertAllowed(result.get("A|2026-01"));
    assertAllowed(result.get("B|2026-01"));
    assertAllowed(result.get("C|2026-01"));
    assertThat(result.get("C|2026-01").getReason()).contains("计划成本未找到");
  }

  @Test
  void checkEligibilityAggregatesMultiplePlanRowsInSamePeriod() {
    CmsPlanCostRaw auxBlocked = plan(1L, "A", LocalDate.of(2026, 1, 1), "辅料");
    auxBlocked.setEffectivePeriod("2026-01");
    CmsPlanCostRaw laborBlocked = plan(2L, "A", LocalDate.of(2026, 1, 1), "工时");
    laborBlocked.setEffectivePeriod("2026-01");
    when(planCostRawMapper.selectList(any())).thenReturn(List.of(auxBlocked, laborBlocked));

    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of("A"), List.of("2026-01"), "COMMERCIAL");

    PlanEligibility eligibility = result.get("A|2026-01");
    assertThat(eligibility.isDirectLaborAllowed()).isFalse();
    assertThat(eligibility.isIndirectLaborAllowed()).isFalse();
    assertThat(eligibility.isAuxSubjectAllowed()).isFalse();
    assertThat(eligibility.getSourcePlanRawId()).isEqualTo(1L);
  }

  @Test
  void checkEligibilityBuildsParentPeriodScopedQueryWithoutBusinessUnitFilter() {
    when(planCostRawMapper.selectList(any())).thenReturn(List.of());

    service.checkEligibility(List.of(" A ", "A", "", "B"), List.of(" 2026-01 ", "2026-01"), "COMMERCIAL");

    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
    verify(planCostRawMapper).selectList(captor.capture());
    String sqlSegment = captor.getValue().getSqlSegment();
    assertThat(sqlSegment).contains("parent_code");
    assertThat(sqlSegment).contains("effective_period");
    assertThat(sqlSegment).doesNotContain("business_unit_type");
  }

  @Test
  void checkEligibilityWithEmptyInputReturnsEmptyMapWithoutQuery() {
    Map<String, PlanEligibility> result =
        service.checkEligibility(List.of(""), List.of("2026-01"), "COMMERCIAL");

    assertThat(result).isEmpty();
    verify(planCostRawMapper, never()).selectList(any());
  }

  @Test
  void blocksDirectLaborWhenUnapprovedItemsContainsWorkingHour() {
    when(planCostRawMapper.selectList(any()))
        .thenReturn(
            List.of(
                plan(1L, "A", "工时"),
                plan(2L, "B", "工时;辅料"),
                plan(3L, "C", "辅料;工时")));

    Map<String, PlanEligibility> result =
        service.checkDirectLaborEligibility(100L, List.of("A", "B", "C"));

    assertThat(result.get("A").isDirectLaborAllowed()).isFalse();
    assertThat(result.get("B").isDirectLaborAllowed()).isFalse();
    assertThat(result.get("C").isDirectLaborAllowed()).isFalse();
    assertThat(result.get("A").getReason()).isEqualTo("工时未审批");
  }

  @Test
  void allowsDirectLaborWhenUnapprovedItemsDoesNotContainWorkingHour() {
    when(planCostRawMapper.selectList(any()))
        .thenReturn(
            List.of(
                plan(1L, "A", "辅料"),
                plan(2L, "B", null),
                plan(3L, "C", "")));

    Map<String, PlanEligibility> result =
        service.checkDirectLaborEligibility(100L, List.of("A", "B", "C", "D"));

    assertThat(result.get("A").isDirectLaborAllowed()).isTrue();
    assertThat(result.get("B").isDirectLaborAllowed()).isTrue();
    assertThat(result.get("C").isDirectLaborAllowed()).isTrue();
    assertThat(result.get("D").isDirectLaborAllowed()).isTrue();
    assertThat(result.get("D").getReason()).contains("允许直接人工取数");
  }

  @Test
  void usesEarliestEffectiveDateForEligibility() {
    when(planCostRawMapper.selectList(any()))
        .thenReturn(
            List.of(
                plan(1L, "A", LocalDate.of(2026, 3, 1), "工时"),
                plan(2L, "A", LocalDate.of(2026, 1, 1), "辅料"),
                plan(3L, "B", LocalDate.of(2026, 1, 1), "工时"),
                plan(4L, "B", LocalDate.of(2026, 3, 1), "辅料")));

    Map<String, PlanEligibility> result =
        service.checkDirectLaborEligibility(100L, List.of("A", "B"));

    assertThat(result.get("A").isDirectLaborAllowed()).isTrue();
    assertThat(result.get("A").getSourcePlanRawId()).isEqualTo(2L);
    assertThat(result.get("A").getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(result.get("B").isDirectLaborAllowed()).isFalse();
    assertThat(result.get("B").getSourcePlanRawId()).isEqualTo(3L);
  }

  @Test
  void blocksWhenAnyRowInEarliestEffectiveDateContainsWorkingHour() {
    when(planCostRawMapper.selectList(any()))
        .thenReturn(
            List.of(
                plan(1L, "A", LocalDate.of(2026, 1, 1), "辅料"),
                plan(2L, "A", LocalDate.of(2026, 1, 1), "工时"),
                plan(3L, "A", LocalDate.of(2026, 3, 1), null)));

    Map<String, PlanEligibility> result =
        service.checkDirectLaborEligibility(100L, List.of("A"));

    assertThat(result.get("A").isDirectLaborAllowed()).isFalse();
    assertThat(result.get("A").getReason()).isEqualTo("工时、辅料未审批");
  }

  @Test
  void buildsBatchAndParentScopedQuery() {
    when(planCostRawMapper.selectList(any())).thenReturn(List.of());

    service.checkDirectLaborEligibility(100L, List.of(" A ", "A", "", "B"));

    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
    verify(planCostRawMapper).selectList(captor.capture());
    String sqlSegment = captor.getValue().getSqlSegment();
    assertThat(sqlSegment).contains("import_batch_id");
    assertThat(sqlSegment).contains("parent_code");
  }

  @Test
  void emptyParentCodesReturnsEmptyMapWithoutQuery() {
    Map<String, PlanEligibility> result =
        service.checkDirectLaborEligibility(100L, List.of("", " "));

    assertThat(result).isEmpty();
  }

  @Test
  void nullImportBatchIdIsRejected() {
    assertThatThrownBy(() -> service.checkDirectLaborEligibility(null, List.of("A")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("importBatchId");
  }

  private CmsPlanCostRaw plan(Long id, String parentCode, String unapprovedItems) {
    return plan(id, parentCode, LocalDate.of(2026, 1, 1), unapprovedItems);
  }

  private CmsPlanCostRaw plan(
      Long id, String parentCode, LocalDate effectiveDate, String unapprovedItems) {
    CmsPlanCostRaw row = new CmsPlanCostRaw();
    row.setId(id);
    row.setImportBatchId(100L);
    row.setRowNo(id.intValue() + 1);
    row.setParentCode(parentCode);
    row.setEffectiveDate(effectiveDate);
    row.setUnapprovedItems(unapprovedItems);
    return row;
  }

  private void assertAllowed(PlanEligibility eligibility) {
    assertThat(eligibility.isDirectLaborAllowed()).isTrue();
    assertThat(eligibility.isIndirectLaborAllowed()).isTrue();
    assertThat(eligibility.isAuxSubjectAllowed()).isTrue();
  }
}
