package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomSupplementTaskDetailControllerTest {

  private QuoteBomSupplementCollaborationService service;
  private QuoteProductBomCostingBuildService costingBuildService;
  private QuoteBomSupplementTaskDetailController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteBomSupplementCollaborationService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    controller = new QuoteBomSupplementTaskDetailController(service, costingBuildService);
  }

  @Test
  void listTasksDelegatesQueryFilters() {
    BomSupplementTaskQueryResponse response = new BomSupplementTaskQueryResponse(0, List.of());
    when(service.listTasks(org.mockito.ArgumentMatchers.any())).thenReturn(response);

    CommonResult<BomSupplementTaskQueryResponse> result =
        controller.listTasks("QBP", "OA-001", "FIN-001", "FINANCE_REVIEW", "PENDING", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().total()).isZero();
    verify(service).listTasks(org.mockito.ArgumentMatchers.argThat(
        request ->
            "QBP".equals(request.taskNo())
                && "OA-001".equals(request.oaNo())
                && "FIN-001".equals(request.productCode())
                && "FINANCE_REVIEW".equals(request.taskStatus())
                && "PENDING".equals(request.reviewStatus())));
  }

  @Test
  void reviewAndReturnDelegateToService() {
    BomSupplementTaskReviewRequest request =
        new BomSupplementTaskReviewRequest(1001L, "财务A", "确认");
    when(service.review(501L, request))
        .thenReturn(new BomSupplementTaskReviewResponse(
            501L, "APPROVED", 201L, "READY", "APPROVED", "APPROVED", "APPROVED", LocalDateTime.now()));
    when(service.returnForRevision(501L, request))
        .thenReturn(new BomSupplementTaskReviewResponse(
            501L, "IN_PROGRESS", 201L, "NEED_TECH", "RETURNED", "RETURNED", null, LocalDateTime.now()));

    assertThat(controller.review(501L, request).getData().reviewStatus()).isEqualTo("APPROVED");
    assertThat(controller.returnForRevision(501L, request).getData().reviewStatus()).isEqualTo("RETURNED");
    verify(service).review(501L, request);
    verify(service).returnForRevision(501L, request);
  }

  @Test
  void reviewReturnsBadRequestForBusinessException() {
    BomSupplementTaskReviewRequest request =
        new BomSupplementTaskReviewRequest(1001L, "财务A", "确认");
    when(service.review(501L, request)).thenThrow(new QuoteIngestException("仅财务审核中的任务允许审核或退回"));

    CommonResult<BomSupplementTaskReviewResponse> result = controller.review(501L, request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("仅财务审核");
  }

  @Test
  void buildCostingRowsDelegatesToBuildService() {
    when(costingBuildService.buildByTask(501L))
        .thenReturn(new QuoteBomCostingBuildResponse(
            201L, 501L, 10L, "OA-001", "FIN-001", "NON_BARE", "2026-05",
            "qbp_20260528_abcdef", 2, 2, 0, Map.of("MANUAL_SUPPLEMENT", 2), List.of(),
            LocalDateTime.now()));

    CommonResult<QuoteBomCostingBuildResponse> result = controller.buildCostingRows(501L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().costingRowsWritten()).isEqualTo(2);
    verify(costingBuildService).buildByTask(501L);
  }
}
