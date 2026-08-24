package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCuMaterialDifferenceResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteProductCostRunRequest;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QuoteBatchCostRunService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;

class QuoteRequestControllerTest {
  private QuoteRequestQueryService quoteRequestQueryService;
  private QuoteCostingWorkbenchService quoteCostingWorkbenchService;
  private QuotePriceTypeRecognitionService quotePriceTypeRecognitionService;
  private QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService;
  private QuoteCostRunWorkbenchService quoteCostRunWorkbenchService;
  private ProductCostingPipeline productCostingPipeline;
  private QuoteBatchCostRunService quoteBatchCostRunService;
  private BusinessUnitRepriceLockGuard repriceLockGuard;
  private QuoteRequestController controller;

  @BeforeEach
  void setUp() {
    quoteRequestQueryService = mock(QuoteRequestQueryService.class);
    quoteCostingWorkbenchService = mock(QuoteCostingWorkbenchService.class);
    quotePriceTypeRecognitionService = mock(QuotePriceTypeRecognitionService.class);
    quotePricePrepareWorkbenchService = mock(QuotePricePrepareWorkbenchService.class);
    quoteCostRunWorkbenchService = mock(QuoteCostRunWorkbenchService.class);
    productCostingPipeline = mock(ProductCostingPipeline.class);
    quoteBatchCostRunService = mock(QuoteBatchCostRunService.class);
    repriceLockGuard = mock(BusinessUnitRepriceLockGuard.class);
    controller =
        new QuoteRequestController(
            quoteRequestQueryService,
            quoteCostingWorkbenchService,
            quotePriceTypeRecognitionService,
            quotePricePrepareWorkbenchService,
            quoteCostRunWorkbenchService,
            productCostingPipeline,
            quoteBatchCostRunService,
            repriceLockGuard);
  }

  @Test
  void pageReturnsServiceResponse() {
    QuoteRequestListItemResponse row = new QuoteRequestListItemResponse();
    row.setOaNo("OA-T8-001");
    row.setSourceType("EXCEL");
    row.setApplicantUnit("申请单位A");
    when(quoteRequestQueryService.pageRequests(1, 20, "OA-T8", "FI-SC-020", "EXCEL", "CONFIRMED"))
        .thenReturn(new PageResult<>(List.of(row), 1L));

    CommonResult<PageResult<QuoteRequestListItemResponse>> result =
        controller.page(1, 20, "OA-T8", "FI-SC-020", "EXCEL", "CONFIRMED");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    assertThat(result.getData().getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
    assertThat(result.getData().getList().get(0).getSourceType()).isEqualTo("EXCEL");
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
  void submitBatchCostRunReturnsAsyncProgress() {
    QuoteBatchCostRunRequest request = new QuoteBatchCostRunRequest();
    QuoteBatchCostRunResponse response = new QuoteBatchCostRunResponse();
    response.setBatchNo("CRQ-1");
    response.setTotalCount(47);
    response.setQueuedCount(30);
    when(quoteBatchCostRunService.submit("OA-T8-001", request, "system"))
        .thenReturn(response);

    CommonResult<QuoteBatchCostRunResponse> result =
        controller.submitBatchCostRun("OA-T8-001", request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getBatchNo()).isEqualTo("CRQ-1");
    assertThat(result.getData().getQueuedCount()).isEqualTo(30);
  }

  @Test
  void currentBatchCostRunReturnsReadOnlyProgress() {
    QuoteBatchCostRunResponse response = new QuoteBatchCostRunResponse();
    response.setBatchNo("CRQ-2");
    response.setStatus("RUNNING");
    when(quoteBatchCostRunService.getCurrent("OA-T8-001", "2026-08"))
        .thenReturn(response);

    CommonResult<QuoteBatchCostRunResponse> result =
        controller.currentBatchCostRun("OA-T8-001", "2026-08");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getStatus()).isEqualTo("RUNNING");
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

  @Test
  void costingWorkbenchReturnsServiceResponse() {
    QuoteCostingWorkbenchResponse response = new QuoteCostingWorkbenchResponse();
    response.setPeriodMonth("2026-06");
    when(quoteCostingWorkbenchService.getWorkbench("OA-T8-001", 101L)).thenReturn(response);

    CommonResult<QuoteCostingWorkbenchResponse> result =
        controller.costingWorkbench("OA-T8-001", 101L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPeriodMonth()).isEqualTo("2026-06");
    verify(quoteCostingWorkbenchService).getWorkbench("OA-T8-001", 101L);
  }

  @Test
  void costRunReturnsServiceResponse() {
    QuoteCostRunWorkbenchResponse response = new QuoteCostRunWorkbenchResponse();
    response.setProductCode("TOP-A");
    when(quoteCostRunWorkbenchService.getCostRun("OA-T8-001", 101L, "2026-06", null))
        .thenReturn(response);

    CommonResult<QuoteCostRunWorkbenchResponse> result =
        controller.costRun("OA-T8-001", 101L, "2026-06", null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getProductCode()).isEqualTo("TOP-A");
    verify(quoteCostRunWorkbenchService).getCostRun("OA-T8-001", 101L, "2026-06", null);
  }

  @Test
  void costRunCanSelectHistoricalVersion() {
    QuoteCostRunWorkbenchResponse response = new QuoteCostRunWorkbenchResponse();
    QuoteCostRunSummaryResponse selected = new QuoteCostRunSummaryResponse();
    selected.setId(88L);
    response.setCurrentDisplayVersion(selected);
    when(quoteCostRunWorkbenchService.getCostRun("OA-T8-001", 101L, null, 88L))
        .thenReturn(response);

    CommonResult<QuoteCostRunWorkbenchResponse> result =
        controller.costRun("OA-T8-001", 101L, null, 88L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getCurrentDisplayVersion().getId()).isEqualTo(88L);
    verify(quoteCostRunWorkbenchService).getCostRun("OA-T8-001", 101L, null, 88L);
  }

  @Test
  void cuMaterialDifferencesReturnsPagedRows() {
    QuoteCuMaterialDifferenceResponse row = new QuoteCuMaterialDifferenceResponse();
    row.setMaterialCode("RAW-CU-1");
    when(
            quoteCostRunWorkbenchService.pageCuMaterialDifferences(
                "OA-T8-001",
                101L,
                "TRIAL-1",
                1,
                20,
                "MAKE-1",
                "RAW-CU-1",
                true,
                "POSITIVE"))
        .thenReturn(new PageResult<>(List.of(row), 1L));

    CommonResult<PageResult<QuoteCuMaterialDifferenceResponse>> result =
        controller.cuMaterialDifferences(
            "OA-T8-001",
            101L,
            "TRIAL-1",
            1,
            20,
            "MAKE-1",
            "RAW-CU-1",
            true,
            "POSITIVE");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1L);
    assertThat(result.getData().getList().get(0).getMaterialCode()).isEqualTo("RAW-CU-1");
  }

  @Test
  void exportCostRunVersionUsesExcelContract() throws Exception {
    when(quoteCostRunWorkbenchService.exportVersion(
            org.mockito.ArgumentMatchers.eq("OA-T8-001"),
            org.mockito.ArgumentMatchers.eq(101L),
            org.mockito.ArgumentMatchers.eq(88L),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              java.io.OutputStream output = invocation.getArgument(3);
              output.write(new byte[] {1, 2, 3});
              return 1;
            });
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.exportCostRunVersion("OA-T8-001", 101L, 88L, response);

    assertThat(response.getContentType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition")).contains(".xlsx");
    assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
  }

  @Test
  void submitProductCostRunCallsUnifiedPipeline() {
    QuoteProductCostRunRequest request = new QuoteProductCostRunRequest();
    request.setPeriodMonth("2026-06");
    request.setReason("USER_REQUEST");
    ProductCostingResult response = new ProductCostingResult();
    response.setPipelineStatus("SUCCESS");
    when(productCostingPipeline.execute(org.mockito.ArgumentMatchers.any(ProductCostingRequest.class)))
        .thenReturn(response);

    CommonResult<ProductCostingResult> result =
        controller.submitProductCostRun("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPipelineStatus()).isEqualTo("SUCCESS");
    verify(repriceLockGuard).assertCostRunAllowed("OA-T8-001");
    ArgumentCaptor<ProductCostingRequest> captor =
        ArgumentCaptor.forClass(ProductCostingRequest.class);
    verify(productCostingPipeline).execute(captor.capture());
    assertThat(captor.getValue().oaNo()).isEqualTo("OA-T8-001");
    assertThat(captor.getValue().oaFormItemId()).isEqualTo(101L);
    assertThat(captor.getValue().periodMonth()).isEqualTo("2026-06");
    assertThat(captor.getValue().initiatedBy()).isEqualTo("system");
    assertThat(captor.getValue().force()).isFalse();
  }

  @Test
  void submitProductCostRunRejectsMonthlyRepriceLockBeforePipeline() {
    QuoteProductCostRunRequest request = new QuoteProductCostRunRequest();
    request.setPeriodMonth("2026-06");
    doThrow(new IllegalStateException("当前业务单元正在月度调价，暂不能发起普通 OA 成本核算：COMMERCIAL"))
        .when(repriceLockGuard)
        .assertCostRunAllowed("OA-T8-001");

    CommonResult<ProductCostingResult> result =
        controller.submitProductCostRun("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("正在月度调价");
    verify(productCostingPipeline, never())
        .execute(org.mockito.ArgumentMatchers.any(ProductCostingRequest.class));
  }

  @Test
  void costingWorkbenchExceptionReturnsBadRequest() {
    when(quoteCostingWorkbenchService.getWorkbench("OA-T8-001", 101L))
        .thenThrow(new QuoteIngestException("报价产品行不存在"));

    CommonResult<QuoteCostingWorkbenchResponse> result =
        controller.costingWorkbench("OA-T8-001", 101L);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("报价产品行不存在");
  }

  @Test
  void pricePrepareReturnsServiceResponse() {
    QuotePricePrepareWorkbenchResponse response = new QuotePricePrepareWorkbenchResponse();
    response.setTopProductCode("TOP-A");
    when(quotePricePrepareWorkbenchService.getPricePrepare("OA-T8-001", 101L, "2026-06"))
        .thenReturn(response);

    CommonResult<QuotePricePrepareWorkbenchResponse> result =
        controller.pricePrepare("OA-T8-001", 101L, "2026-06");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTopProductCode()).isEqualTo("TOP-A");
    verify(quotePricePrepareWorkbenchService).getPricePrepare("OA-T8-001", 101L, "2026-06");
  }

  @Test
  void generatePricePrepareReturnsServiceResponse() {
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    QuotePricePrepareWorkbenchResponse response = new QuotePricePrepareWorkbenchResponse();
    response.setTopProductCode("TOP-A");
    when(quotePricePrepareWorkbenchService.generate("OA-T8-001", 101L, request)).thenReturn(response);

    CommonResult<QuotePricePrepareWorkbenchResponse> result =
        controller.generatePricePrepare("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTopProductCode()).isEqualTo("TOP-A");
    verify(quotePricePrepareWorkbenchService).generate("OA-T8-001", 101L, request);
  }

  @Test
  void checkPriceSourcesReturnsTemporaryPreview() {
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth("2026-06");
    QuotePricePrepareWorkbenchResponse response = new QuotePricePrepareWorkbenchResponse();
    response.setPeriodMonth("2026-06");
    when(quotePricePrepareWorkbenchService.checkPriceSources("OA-T8-001", 101L, request))
        .thenReturn(response);

    CommonResult<QuotePricePrepareWorkbenchResponse> result =
        controller.checkPriceSources("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPeriodMonth()).isEqualTo("2026-06");
    verify(quotePricePrepareWorkbenchService)
        .checkPriceSources("OA-T8-001", 101L, request);
    verify(quotePricePrepareWorkbenchService, never())
        .generate("OA-T8-001", 101L, request);
  }

  @Test
  void priceTypeRecognitionReturnsServiceResponse() {
    QuotePriceTypeRecognitionResponse response = new QuotePriceTypeRecognitionResponse();
    response.setPeriodMonth("2026-06");
    when(quotePriceTypeRecognitionService.getRecognition("OA-T8-001", 101L, "2026-06"))
        .thenReturn(response);

    CommonResult<QuotePriceTypeRecognitionResponse> result =
        controller.priceTypeRecognition("OA-T8-001", 101L, "2026-06");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPeriodMonth()).isEqualTo("2026-06");
    verify(quotePriceTypeRecognitionService).getRecognition("OA-T8-001", 101L, "2026-06");
  }

  @Test
  void priceTypeRecognitionEndpointIsReadOnlyAndOldManualActionsAreRemoved() throws Exception {
    GetMapping mapping =
        QuoteRequestController.class
            .getMethod("priceTypeRecognition", String.class, Long.class, String.class)
            .getAnnotation(GetMapping.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value())
        .containsExactly("/{oaNo}/items/{oaFormItemId}/price-type-recognition");
    assertThat(QuoteRequestController.class.getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain("importMissingPriceType", "adjustPriceType");
  }

}
