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
import java.time.LocalDate;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("QBA-08 替代选择重建事务边界")
class QuoteBomAlternativeRebuildTransactionTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("公开重建入口声明任意异常回滚")
  void rebuildEntryRollsBackForAnyException()
      throws Exception {
    Method method =
        QuoteBomAlternativeRebuildServiceImpl.class.getMethod(
            "rebuild",
            QuoteBomAlternativeRebuildCommand.class);
    Transactional transactional =
        method.getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.rollbackFor())
        .contains(Exception.class);
  }

  @Test
  @DisplayName("选择、明细重建和下游失效均以REQUIRED加入同一外层事务")
  void allMutationStagesJoinTheOuterTransaction()
      throws Exception {
    assertRequiredRollbackTransaction(
        QuoteBomAlternativeSelectionServiceImpl.class.getMethod(
            "save",
            QuoteBomAlternativeSelectionCommand.class,
            BomAlternativeGroup.class));
    assertRequiredRollbackTransaction(
        QuoteProductBomCostingBuildServiceImpl.class.getMethod(
            "buildByOaFormItem",
            Long.class,
            String.class,
            LocalDate.class));
    assertRequiredRollbackTransaction(
        QuoteBomAlternativeWorkflowInvalidationServiceImpl.class
            .getMethod(
                "invalidate",
                String.class,
                Long.class,
                String.class,
                String.class));
  }

  @Test
  @DisplayName("顺序固定为保存选择、重建明细、失效下游")
  void followsAtomicOperationOrder() {
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

    InOrder order =
        inOrder(
            support.selectionService,
            support.buildService,
            support.invalidationService);
    order.verify(support.selectionService).save(any(), any());
    order.verify(support.buildService)
        .buildByOaFormItem(any(), any(), any());
    order.verify(support.invalidationService)
        .invalidate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("选择保存失败时不重建明细也不失效下游")
  void selectionFailureStopsRebuildAndInvalidation() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenThrow(
            new IllegalStateException("选择保存失败"));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("选择保存失败");

    verify(support.buildService, never())
        .buildByOaFormItem(any(), any(), any());
    verify(support.invalidationService, never())
        .invalidate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("构建失败时不执行下游失效并把异常交给事务回滚")
  void buildFailureStopsInvalidationAndPropagates() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of());
    when(support.buildService.buildByOaFormItem(
            any(), any(), any()))
        .thenThrow(
            new IllegalStateException("重建失败"));

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("重建失败");

    verify(support.invalidationService, never())
        .invalidate(any(), any(), any(), any());
  }

  @Test
  @DisplayName("状态失效失败向外抛出以触发选择和明细整体回滚")
  void invalidationFailurePropagatesForFullRollback() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of());
    when(support.invalidationService.invalidate(
            any(), any(), any(), any()))
        .thenThrow(
            new IllegalStateException("状态失效失败"));

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("状态失效失败");
  }

  @Test
  @DisplayName("构建没有返回完整结果时阻断状态失效并触发整体回滚")
  void missingBuildResultTriggersFullRollback() {
    QuoteBomAlternativeRebuildTestSupport support =
        new QuoteBomAlternativeRebuildTestSupport();
    support.stubBase();
    when(support.selectionService.findCurrent(any(), any()))
        .thenReturn(support.selection("STD", 1, false));
    when(support.selectionService.save(any(), any()))
        .thenReturn(support.selection("ALT", 2, false));
    when(support.costingRowMapper.selectQuoteCostingSnapshot(
            any(), any(), any(), any()))
        .thenReturn(List.of());
    when(support.buildService.buildByOaFormItem(
            any(), any(), any()))
        .thenReturn(null);

    assertThatThrownBy(
            () ->
                support.service.rebuild(
                    support.command("ALT", 1, false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("重建未返回结果");

    verify(support.invalidationService, never())
        .invalidate(any(), any(), any(), any());
  }

  private void assertRequiredRollbackTransaction(Method method) {
    Transactional transactional =
        method.getAnnotation(Transactional.class);
    assertThat(transactional)
        .as(method.toGenericString())
        .isNotNull();
    assertThat(transactional.propagation())
        .isEqualTo(Propagation.REQUIRED);
    assertThat(transactional.rollbackFor())
        .contains(Exception.class);
  }
}
