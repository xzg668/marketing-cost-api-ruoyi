package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionServiceImpl;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class QuoteBomAlternativeRebuildTransactionTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  void selectionAndStaleMarkShareOneRollbackBoundary() throws Exception {
    assertRequiredRollbackTransaction(
        QuoteBomAlternativeRebuildServiceImpl.class.getMethod(
            "rebuild", QuoteBomAlternativeRebuildCommand.class));
    assertRequiredRollbackTransaction(
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "save", QuoteBomAlternativeSelectionCommand.class, BomAlternativeGroup.class));
    assertRequiredRollbackTransaction(
        QuoteBomAlternativeWorkflowInvalidationServiceImpl.class.getMethod(
            "invalidate", String.class, Long.class, String.class, String.class));
  }

  @Test
  void savesSelectionBeforeMarkingWorkspaceStale() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));

    support.service.rebuild(support.command("ALT", 1));

    InOrder order = inOrder(support.selectionService, support.invalidationService);
    order.verify(support.selectionService).save(any(), any());
    order.verify(support.invalidationService).invalidate(any(), any(), any(), any());
  }

  @Test
  void selectionFailureDoesNotMarkWorkspaceStale() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenThrow(new IllegalStateException("选择保存失败"));

    assertThatThrownBy(() -> support.service.rebuild(support.command("ALT", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("选择保存失败");
    verify(support.invalidationService, never()).invalidate(any(), any(), any(), any());
  }

  @Test
  void staleMarkFailurePropagatesSoSelectionRollsBack() {
    QuoteBomAlternativeRebuildTestSupport support = new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.invalidationService.invalidate(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("工作区标旧失败"));

    assertThatThrownBy(() -> support.service.rebuild(support.command("ALT", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("工作区标旧失败");
  }

  private void assertRequiredRollbackTransaction(Method method) {
    Transactional transactional = method.getAnnotation(Transactional.class);
    assertThat(transactional).as(method.toGenericString()).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    assertThat(transactional.rollbackFor()).contains(Exception.class);
  }
}
