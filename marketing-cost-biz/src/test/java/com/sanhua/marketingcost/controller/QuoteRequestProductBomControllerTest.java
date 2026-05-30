package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteProductBomBatchOaTaskResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestProductBomQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestProductBomControllerTest {
  private QuoteBomStatusService quoteBomStatusService;
  private QuoteRequestProductBomQueryService quoteRequestProductBomQueryService;
  private QuoteRequestProductBomController controller;

  @BeforeEach
  void setUp() {
    quoteBomStatusService = mock(QuoteBomStatusService.class);
    quoteRequestProductBomQueryService = mock(QuoteRequestProductBomQueryService.class);
    controller = new QuoteRequestProductBomController(quoteBomStatusService, quoteRequestProductBomQueryService);
  }

  @Test
  void pagePassesFiltersAndPaginationToService() {
    QuoteRequestProductBomListItemResponse row = new QuoteRequestProductBomListItemResponse();
    row.setOaFormItemId(10L);
    row.setBomStatus("REUSED_CURRENT_MONTH");
    PageResult<QuoteRequestProductBomListItemResponse> page = new PageResult<>(List.of(row), 1L);
    when(
            quoteRequestProductBomQueryService.pageProductBomRows(
                2,
                30,
                "OA",
                "MAT",
                "客户",
                "BARE",
                "BOX",
                "COMMERCIAL",
                "张三",
                true,
                "APPROVED",
                List.of("SYNCED", "REUSED_CURRENT_MONTH")))
        .thenReturn(page);

    CommonResult<PageResult<QuoteRequestProductBomListItemResponse>> result =
        controller.page(
            2,
            30,
            "OA",
            "MAT",
            "客户",
            "BARE",
            "BOX",
            "COMMERCIAL",
            "张三",
            true,
            "APPROVED",
            List.of("SYNCED", "REUSED_CURRENT_MONTH"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    assertThat(result.getData().getList().get(0).getBomStatus()).isEqualTo("REUSED_CURRENT_MONTH");
    verify(quoteRequestProductBomQueryService)
        .pageProductBomRows(
            2,
            30,
            "OA",
            "MAT",
            "客户",
            "BARE",
            "BOX",
            "COMMERCIAL",
            "张三",
            true,
            "APPROVED",
            List.of("SYNCED", "REUSED_CURRENT_MONTH"));
  }

  @Test
  void batchSyncUsesProductBomResourcePathService() {
    QuoteBomBatchSyncRequest request = new QuoteBomBatchSyncRequest();
    request.setOaFormItemIds(List.of(1L, 2L));
    QuoteBomBatchSyncResponse response = new QuoteBomBatchSyncResponse();
    response.setSyncedRowCount(2);
    when(quoteBomStatusService.batchSyncFromU9Source(List.of(1L, 2L))).thenReturn(response);

    CommonResult<QuoteBomBatchSyncResponse> result = controller.batchSync(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSyncedRowCount()).isEqualTo(2);
    verify(quoteBomStatusService).batchSyncFromU9Source(List.of(1L, 2L));
  }

  @Test
  void batchSyncExceptionReturnsBadRequest() {
    QuoteBomBatchSyncRequest request = new QuoteBomBatchSyncRequest();
    request.setOaFormItemIds(List.of());
    when(quoteBomStatusService.batchSyncFromU9Source(List.of()))
        .thenThrow(new QuoteIngestException("请选择需要同步的报价单产品行"));

    CommonResult<QuoteBomBatchSyncResponse> result = controller.batchSync(request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("请选择需要同步");
  }

  @Test
  void batchOaTaskOnlyReturnsReservedMessage() {
    QuoteBomBatchSyncRequest request = new QuoteBomBatchSyncRequest();
    request.setOaFormItemIds(List.of(1L, 2L));

    CommonResult<QuoteProductBomBatchOaTaskResponse> result = controller.batchOaTask(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getAcceptedCount()).isZero();
    assertThat(result.getData().getMessage()).isEqualTo("OA待办推送和BOM补录闭环后续阶段对接");
  }
}
