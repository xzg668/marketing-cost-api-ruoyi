package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("报价 BOM 替代选择保存与待重算标记")
class QuoteBomAlternativeRebuildServiceTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  void changedSelectionIsSavedAndMarksWorkspaceForRecalculation() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));

    var result = support.service.rebuild(support.command("ALT", 1));

    assertThat(result.idempotent()).isFalse();
    assertThat(result.recalculationRequired()).isTrue();
    assertThat(result.selection().selectedMaterialCode()).isEqualTo("ALT");
    verify(support.invalidationService).invalidate("OA-QBA-08", 801L, "TOP", "2026-07");
  }

  @Test
  void repeatedSelectionIsIdempotentAndDoesNotMarkWorkspaceStale() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, true));

    var result = support.service.rebuild(support.command("ALT", 2));

    assertThat(result.idempotent()).isTrue();
    assertThat(result.recalculationRequired()).isFalse();
    verify(support.invalidationService, never()).invalidate(any(), any(), any(), any());
  }

  @Test
  void changedSourceBuildIsNotTreatedAsIdempotent() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("ALT", 2, false, "BUILD-OLD"));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 3, false));

    var result = support.service.rebuild(support.command("ALT", 2));

    assertThat(result.idempotent()).isFalse();
    assertThat(result.recalculationRequired()).isTrue();
  }

  @Test
  void selectionCommandUsesAuthoritativeQuoteScopeAndVersion() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));

    support.service.rebuild(support.command("ALT", 1));

    ArgumentCaptor<QuoteBomAlternativeSelectionCommand> captor =
        ArgumentCaptor.forClass(QuoteBomAlternativeSelectionCommand.class);
    verify(support.selectionService).save(captor.capture(), eq(support.group));
    assertThat(captor.getValue().scope().oaNo()).isEqualTo("OA-QBA-08");
    assertThat(captor.getValue().scope().oaFormItemId()).isEqualTo(801L);
    assertThat(captor.getValue().scope().periodMonth()).isEqualTo("2026-07");
    assertThat(captor.getValue().expectedSelectionVersion()).isEqualTo(1);
    assertThat(captor.getValue().expectedBuildBatchId()).isEqualTo("BUILD-1");
  }
}
