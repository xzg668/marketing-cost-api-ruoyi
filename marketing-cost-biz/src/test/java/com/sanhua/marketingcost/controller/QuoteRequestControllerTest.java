package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestControllerTest {
  private QuoteRequestQueryService quoteRequestQueryService;
  private QuoteRequestController controller;

  @BeforeEach
  void setUp() {
    quoteRequestQueryService = mock(QuoteRequestQueryService.class);
    controller = new QuoteRequestController(quoteRequestQueryService);
  }

  @Test
  void pageReturnsServiceResponse() {
    QuoteRequestListItemResponse row = new QuoteRequestListItemResponse();
    row.setOaNo("OA-T8-001");
    when(quoteRequestQueryService.pageRequests(1, 20, "OA-T8", "FI-SC-020", "CONFIRMED"))
        .thenReturn(new PageResult<>(List.of(row), 1L));

    CommonResult<PageResult<QuoteRequestListItemResponse>> result =
        controller.page(1, 20, "OA-T8", "FI-SC-020", "CONFIRMED");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    assertThat(result.getData().getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
  }

  @Test
  void detailReturnsServiceResponse() {
    QuoteRequestDetailResponse response = new QuoteRequestDetailResponse();
    response.setOaNo("OA-T8-001");
    when(quoteRequestQueryService.getRequestDetail("OA-T8-001")).thenReturn(response);

    CommonResult<QuoteRequestDetailResponse> result = controller.detail("OA-T8-001");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getOaNo()).isEqualTo("OA-T8-001");
  }

  @Test
  void detailExceptionReturnsBadRequest() {
    when(quoteRequestQueryService.getRequestDetail("MISSING"))
        .thenThrow(new QuoteIngestException("报价单不存在"));

    CommonResult<QuoteRequestDetailResponse> result = controller.detail("MISSING");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("报价单不存在");
  }

  @Test
  void confirmClassificationReturnsConfirmedDetail() {
    QuoteRequestConfirmClassificationRequest request = new QuoteRequestConfirmClassificationRequest();
    request.setQuoteScenario("NEW_PRODUCT");
    QuoteRequestDetailResponse response = new QuoteRequestDetailResponse();
    response.setClassificationStatus("CONFIRMED");
    when(quoteRequestQueryService.confirmClassification("OA-T8-001", request)).thenReturn(response);

    CommonResult<QuoteRequestDetailResponse> result =
        controller.confirmClassification("OA-T8-001", request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getClassificationStatus()).isEqualTo("CONFIRMED");
    verify(quoteRequestQueryService).confirmClassification("OA-T8-001", request);
  }
}
