package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class QuoteBomAlternativeWorkflowInvalidationTest {

  @Test
  void marksCurrentWorkspaceStaleAndOnlyInvalidatesTrialCost() {
    QuoteCostingWorkspaceService workspace = mock(QuoteCostingWorkspaceService.class);
    QuoteCostRunVersionInvalidationService costRun =
        mock(QuoteCostRunVersionInvalidationService.class);
    when(workspace.markItemStale(801L, "2026-07", "BOM_ALTERNATIVE_CHANGED"))
        .thenReturn(1);
    when(costRun.invalidateProduct("OA-QBA-08", 801L, "TOP", "2026-07"))
        .thenReturn(2);
    var service = new QuoteBomAlternativeWorkflowInvalidationServiceImpl(workspace, costRun);

    var result = service.invalidate("OA-QBA-08", 801L, "TOP", "2026-07");

    assertThat(result.priceTypeCount()).isZero();
    assertThat(result.pricePrepareCount()).isZero();
    assertThat(result.costRunCount()).isEqualTo(2);
    InOrder order = inOrder(workspace, costRun);
    order.verify(workspace).markItemStale(801L, "2026-07", "BOM_ALTERNATIVE_CHANGED");
    order.verify(costRun).invalidateProduct("OA-QBA-08", 801L, "TOP", "2026-07");
  }

  @Test
  void staleMarkFailureStopsTrialInvalidation() {
    QuoteCostingWorkspaceService workspace = mock(QuoteCostingWorkspaceService.class);
    QuoteCostRunVersionInvalidationService costRun =
        mock(QuoteCostRunVersionInvalidationService.class);
    when(workspace.markItemStale(801L, "2026-07", "BOM_ALTERNATIVE_CHANGED"))
        .thenThrow(new IllegalStateException("工作区标旧失败"));
    var service = new QuoteBomAlternativeWorkflowInvalidationServiceImpl(workspace, costRun);

    assertThatThrownBy(() -> service.invalidate("OA-QBA-08", 801L, "TOP", "2026-07"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("工作区标旧失败");
  }
}
