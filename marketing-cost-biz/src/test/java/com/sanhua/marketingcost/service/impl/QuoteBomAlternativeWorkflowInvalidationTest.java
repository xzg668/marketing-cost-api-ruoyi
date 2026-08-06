package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("QBA-08 替代分支变化下游状态失效")
class QuoteBomAlternativeWorkflowInvalidationTest {

  @BeforeAll
  static void initTableInfo() {
    QuoteBomAlternativeRebuildTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("同一产品范围依次失效价格类型、价格准备和成本版本")
  void invalidatesAllDownstreamWorkflowStagesInScope() {
    QuotePriceTypeConfirmationInvalidationService priceType =
        mock(
            QuotePriceTypeConfirmationInvalidationService.class);
    PricePrepareBatchMapper pricePrepare =
        mock(PricePrepareBatchMapper.class);
    QuoteCostRunVersionInvalidationService costRun =
        mock(QuoteCostRunVersionInvalidationService.class);
    when(priceType.invalidateScopeAfterBomChange(
            any(), any(), any(), any()))
        .thenReturn(2);
    when(pricePrepare.update(any(), any())).thenReturn(3);
    when(costRun.invalidateProductAfterBomChange(
            any(), any(), any(), any()))
        .thenReturn(4);
    var service =
        new QuoteBomAlternativeWorkflowInvalidationServiceImpl(
            priceType, pricePrepare, costRun);

    var result =
        service.invalidate(
            "OA-QBA-08", 801L, "TOP", "2026-07");

    assertThat(result.priceTypeCount()).isEqualTo(2);
    assertThat(result.pricePrepareCount()).isEqualTo(3);
    assertThat(result.costRunCount()).isEqualTo(4);
    InOrder order = inOrder(priceType, pricePrepare, costRun);
    order.verify(priceType)
        .invalidateScopeAfterBomChange(
            "OA-QBA-08", 801L, "TOP", "2026-07");
    order.verify(pricePrepare).update(any(), any());
    order.verify(costRun)
        .invalidateProductAfterBomChange(
            "OA-QBA-08", 801L, "TOP", "2026-07");

    ArgumentCaptor<Wrapper> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(pricePrepare).update(any(), captor.capture());
    LambdaUpdateWrapper<?> wrapper =
        (LambdaUpdateWrapper<?>) captor.getValue();
    assertThat(wrapper.getSqlSegment())
        .contains("oa_no")
        .contains("oa_form_item_id")
        .contains("product_code")
        .contains("period_month")
        .contains("status");
    assertThat(
            ((AbstractWrapper<?, ?, ?>) wrapper)
                .getParamNameValuePairs())
        .containsValue("STALE")
        .containsValue("BOM标准/替代分支已变化，请重新确认价格并核算成本")
        .containsValue("OA-QBA-08")
        .containsValue(801L)
        .containsValue("TOP")
        .containsValue("2026-07");
  }

  @Test
  @DisplayName("任一下游失效失败立即抛出并阻止后续提交")
  void propagatesInvalidationFailure() {
    QuotePriceTypeConfirmationInvalidationService priceType =
        mock(
            QuotePriceTypeConfirmationInvalidationService.class);
    PricePrepareBatchMapper pricePrepare =
        mock(PricePrepareBatchMapper.class);
    QuoteCostRunVersionInvalidationService costRun =
        mock(QuoteCostRunVersionInvalidationService.class);
    when(priceType.invalidateScopeAfterBomChange(
            any(), any(), any(), any()))
        .thenReturn(1);
    when(pricePrepare.update(any(), any()))
        .thenThrow(
            new IllegalStateException("失效写入失败"));
    var service =
        new QuoteBomAlternativeWorkflowInvalidationServiceImpl(
            priceType, pricePrepare, costRun);

    assertThatThrownBy(
            () ->
                service.invalidate(
                    "OA-QBA-08",
                    801L,
                    "TOP",
                    "2026-07"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("失效写入失败");
  }
}
