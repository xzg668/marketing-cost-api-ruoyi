package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QBA-08 人工行和已确认状态保护")
class QuoteBomAlternativeManualChangeGuardTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("存在人工修改时首次请求阻断且不保存选择")
  void manualChangesBlockWithoutExplicitConfirmation() {
    QuoteBomAlternativeRebuildTestSupport support =
        changingSupport();
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(
            List.of(
                support.costingRow(1L, "STD", 1)));

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, false)))
        .isInstanceOf(
            QuoteBomAlternativeSelectionException.class)
        .hasMessageContaining("MANUAL_ROW_CHANGES_EXIST");

    verify(support.selectionService, never())
        .save(any(), any());
    verify(support.buildService, never())
        .buildByOaFormItem(any(), any(), any());
  }

  @Test
  @DisplayName("用户确认清除人工修改后允许重建并写入选择备注")
  void explicitConfirmationAllowsRebuildAndRecordsRemark() {
    QuoteBomAlternativeRebuildTestSupport support =
        changingSupport();
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(
            List.of(
                support.costingRow(1L, "STD", 1)),
            List.of(
                support.costingRow(2L, "ALT", 0)));

    var result =
        support.service.rebuild(
            support.command("ALT", 1, true));

    assertThat(result.manualChangesDiscarded()).isTrue();
    ArgumentCaptor<QuoteBomAlternativeSelectionCommand> captor =
        ArgumentCaptor.forClass(
            QuoteBomAlternativeSelectionCommand.class);
    verify(support.selectionService)
        .save(captor.capture(), any());
    assertThat(captor.getValue().selectionRemark())
        .contains("切换BOM分支")
        .contains("用户已确认清除1条人工修改结算行");
  }

  @Test
  @DisplayName("存在有效报价物料确认时阻断且不自动撤销")
  void activeBomConfirmationBlocksSelectionChange() {
    QuoteBomAlternativeRebuildTestSupport support =
        changingSupport();
    when(support.confirmationService.hasActiveConfirmation(
            any(), any(), any(), any()))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, true)))
        .isInstanceOf(
            QuoteBomAlternativeSelectionException.class)
        .hasMessageContaining("BOM_ALREADY_CONFIRMED");

    verify(support.selectionService, never())
        .save(any(), any());
    verify(support.costingRowMapper, never())
        .selectQuoteCostingSnapshot(
            any(), any(), any(), any());
  }

  private QuoteBomAlternativeRebuildTestSupport
      changingSupport() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    return support;
  }
}
