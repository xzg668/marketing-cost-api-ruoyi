package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionCommand;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QBA-08 报价BOM替代选择原子重建")
class QuoteBomAlternativeRebuildServiceTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("标准切替代后只重建当前产品并失效下游")
  void switchesStandardToAlternativeAndRebuildsCurrentProduct() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(
            List.of(support.costingRow(1L, "STD", 0)),
            List.of(support.costingRow(2L, "ALT", 0)));

    var result =
        support.service.rebuild(
            support.command("ALT", 1, false));

    assertThat(result.rebuilt()).isTrue();
    assertThat(result.idempotent()).isFalse();
    assertThat(result.beforeRowCount()).isEqualTo(1);
    assertThat(result.afterRowCount()).isEqualTo(1);
    assertThat(result.buildBatchId()).isEqualTo("BUILD-NEW");
    assertThat(result.priceTypeInvalidatedCount()).isEqualTo(1);
    assertThat(result.pricePrepareInvalidatedCount()).isEqualTo(2);
    assertThat(result.costRunInvalidatedCount()).isEqualTo(3);
    verify(support.buildService)
        .buildByOaFormItem(
            801L,
            "2026-07",
            java.time.LocalDate.of(2026, 7, 30));
    verify(support.invalidationService)
        .invalidate(
            "OA-QBA-08", 801L, "TOP", "2026-07");
  }

  @Test
  @DisplayName("替代恢复标准使用相同重建链路")
  void restoresAlternativeToStandard() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("STD", 3, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of(), List.of());

    var result =
        support.service.rebuild(
            support.command("STD", 2, false));

    assertThat(result.selection().selectedMaterialCode())
        .isEqualTo("STD");
    assertThat(result.selection().selectionVersion())
        .isEqualTo(3);
    assertThat(result.rebuilt()).isTrue();
  }

  @Test
  @DisplayName("重复保存当前选中料号不重建也不失效")
  void repeatedSelectionIsFullyIdempotent() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, true));

    var result =
        support.service.rebuild(
            support.command("ALT", 1, false));

    assertThat(result.idempotent()).isTrue();
    assertThat(result.rebuilt()).isFalse();
    verifyNoInteractions(support.confirmationService);
    verifyNoInteractions(support.costingRowMapper);
    verify(support.buildService, never())
        .buildByOaFormItem(any(), any(), any());
    verifyNoInteractions(support.invalidationService);
  }

  @Test
  @DisplayName("料号相同但BOM来源批次变化时仍重建并失效下游")
  void sameMaterialWithChangedBomBatchStillRebuilds() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(
            support.selection(
                "ALT", 2, false, "BUILD-OLD"));
    when(support.selectionService.save(any(), any()))
        .thenReturn(
            support.selection("ALT", 2, true));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of(), List.of());

    var result =
        support.service.rebuild(
            support.command("ALT", 2, false));

    assertThat(result.idempotent()).isFalse();
    assertThat(result.rebuilt()).isTrue();
    verify(support.buildService)
        .buildByOaFormItem(
            801L,
            "2026-07",
            java.time.LocalDate.of(2026, 7, 30));
    verify(support.invalidationService)
        .invalidate(
            "OA-QBA-08", 801L, "TOP", "2026-07");
  }

  @Test
  @DisplayName("选择命令完整透传版本、构建批次和报价作用域")
  void passesCompleteSelectionScopeAndConcurrencyFields() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of(), List.of());

    support.service.rebuild(
        support.command("ALT", 1, false));

    ArgumentCaptor<QuoteBomAlternativeSelectionCommand> captor =
        ArgumentCaptor.forClass(
            QuoteBomAlternativeSelectionCommand.class);
    verify(support.selectionService)
        .save(captor.capture(), eq(support.group));
    assertThat(captor.getValue().scope().oaNo())
        .isEqualTo("OA-QBA-08");
    assertThat(captor.getValue().scope().oaFormItemId())
        .isEqualTo(801L);
    assertThat(captor.getValue().scope().periodMonth())
        .isEqualTo("2026-07");
    assertThat(captor.getValue().scope().businessUnitType())
        .isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().expectedSelectionVersion())
        .isEqualTo(1);
    assertThat(captor.getValue().expectedBuildBatchId())
        .isEqualTo("BUILD-1");
  }

  @Test
  @DisplayName("BOM版本失效后允许按历史版本号重新确认当前候选")
  void staleHistoryCanBeReviewedWithItsLatestVersion() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(null);
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 3, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of(), List.of());

    var result =
        support.service.rebuild(
            support.command("ALT", 2, false));

    assertThat(result.rebuilt()).isTrue();
    assertThat(result.selection().selectionVersion()).isEqualTo(3);
    ArgumentCaptor<QuoteBomAlternativeSelectionCommand> captor =
        ArgumentCaptor.forClass(
            QuoteBomAlternativeSelectionCommand.class);
    verify(support.selectionService)
        .save(captor.capture(), eq(support.group));
    assertThat(captor.getValue().expectedSelectionVersion())
        .isEqualTo(2);
  }
}
