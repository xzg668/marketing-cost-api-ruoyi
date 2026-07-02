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
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingBomRowUpdateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationActionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeAdjustRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeImportMissingRequest;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePriceTypeConfirmationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestControllerTest {
  private QuoteRequestQueryService quoteRequestQueryService;
  private QuoteCostingWorkbenchService quoteCostingWorkbenchService;
  private QuoteBomConfirmationService quoteBomConfirmationService;
  private QuotePriceTypeConfirmationService quotePriceTypeConfirmationService;
  private QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService;
  private QuoteCostRunWorkbenchService quoteCostRunWorkbenchService;
  private QuoteRequestController controller;

  @BeforeEach
  void setUp() {
    quoteRequestQueryService = mock(QuoteRequestQueryService.class);
    quoteCostingWorkbenchService = mock(QuoteCostingWorkbenchService.class);
    quoteBomConfirmationService = mock(QuoteBomConfirmationService.class);
    quotePriceTypeConfirmationService = mock(QuotePriceTypeConfirmationService.class);
    quotePricePrepareWorkbenchService = mock(QuotePricePrepareWorkbenchService.class);
    quoteCostRunWorkbenchService = mock(QuoteCostRunWorkbenchService.class);
    controller =
        new QuoteRequestController(
            quoteRequestQueryService,
            quoteCostingWorkbenchService,
            quoteBomConfirmationService,
            quotePriceTypeConfirmationService,
            quotePricePrepareWorkbenchService,
            quoteCostRunWorkbenchService);
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
  void launchCostingWorkbenchReturnsServiceResponse() {
    QuoteCostingWorkbenchResponse response = new QuoteCostingWorkbenchResponse();
    response.setPeriodMonth("2026-06");
    response.setSnapshotGenerated(true);
    when(quoteCostingWorkbenchService.launchWorkbench("OA-T8-001", 101L)).thenReturn(response);

    CommonResult<QuoteCostingWorkbenchResponse> result =
        controller.launchCostingWorkbench("OA-T8-001", 101L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSnapshotGenerated()).isTrue();
    verify(quoteCostingWorkbenchService).launchWorkbench("OA-T8-001", 101L);
  }

  @Test
  void costRunReturnsServiceResponse() {
    QuoteCostRunWorkbenchResponse response = new QuoteCostRunWorkbenchResponse();
    response.setProductCode("TOP-A");
    when(quoteCostRunWorkbenchService.getCostRun("OA-T8-001", 101L, "2026-06")).thenReturn(response);

    CommonResult<QuoteCostRunWorkbenchResponse> result =
        controller.costRun("OA-T8-001", 101L, "2026-06");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getProductCode()).isEqualTo("TOP-A");
    verify(quoteCostRunWorkbenchService).getCostRun("OA-T8-001", 101L, "2026-06");
  }

  @Test
  void trialCostRunReturnsServiceResponse() {
    QuoteCostRunTrialRequest request = new QuoteCostRunTrialRequest();
    request.setPeriodMonth("2026-06");
    QuoteCostRunWorkbenchResponse response = new QuoteCostRunWorkbenchResponse();
    response.setCanConfirm(true);
    when(quoteCostRunWorkbenchService.trial("OA-T8-001", 101L, request)).thenReturn(response);

    CommonResult<QuoteCostRunWorkbenchResponse> result =
        controller.trialCostRun("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().isCanConfirm()).isTrue();
    verify(quoteCostRunWorkbenchService).trial("OA-T8-001", 101L, request);
  }

  @Test
  void confirmCostRunReturnsServiceResponse() {
    QuoteCostRunConfirmRequest request = new QuoteCostRunConfirmRequest();
    request.setConfirmMessage("确认成本");
    QuoteCostRunSummaryResponse response = new QuoteCostRunSummaryResponse();
    response.setStatus("CONFIRMED");
    when(quoteCostRunWorkbenchService.confirm("OA-T8-001", 101L, "TRIAL-1", request))
        .thenReturn(response);

    CommonResult<QuoteCostRunSummaryResponse> result =
        controller.confirmCostRun("OA-T8-001", 101L, "TRIAL-1", request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getStatus()).isEqualTo("CONFIRMED");
    verify(quoteCostRunWorkbenchService).confirm("OA-T8-001", 101L, "TRIAL-1", request);
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
    response.setLatestPriceTypeConfirmNo("PTC-001");
    when(quotePricePrepareWorkbenchService.getPricePrepare("OA-T8-001", 101L, "2026-06"))
        .thenReturn(response);

    CommonResult<QuotePricePrepareWorkbenchResponse> result =
        controller.pricePrepare("OA-T8-001", 101L, "2026-06");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getLatestPriceTypeConfirmNo()).isEqualTo("PTC-001");
    verify(quotePricePrepareWorkbenchService).getPricePrepare("OA-T8-001", 101L, "2026-06");
  }

  @Test
  void generatePricePrepareReturnsServiceResponse() {
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPriceTypeConfirmNo("PTC-001");
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
  void updateCostingBomRowReturnsServiceResponse() {
    QuoteCostingBomRowUpdateRequest request = new QuoteCostingBomRowUpdateRequest();
    request.setChildCode("MAT-NEW");
    QuoteCostingWorkbenchBomRowResponse response = new QuoteCostingWorkbenchBomRowResponse();
    response.setId(300L);
    response.setChildCode("MAT-NEW");
    when(quoteCostingWorkbenchService.updateBomRow("OA-T8-001", 101L, 300L, request))
        .thenReturn(response);

    CommonResult<QuoteCostingWorkbenchBomRowResponse> result =
        controller.updateCostingBomRow("OA-T8-001", 101L, 300L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getChildCode()).isEqualTo("MAT-NEW");
    verify(quoteCostingWorkbenchService).updateBomRow("OA-T8-001", 101L, 300L, request);
  }

  @Test
  void updateCostingBomRowExceptionReturnsBadRequest() {
    QuoteCostingBomRowUpdateRequest request = new QuoteCostingBomRowUpdateRequest();
    when(quoteCostingWorkbenchService.updateBomRow("OA-T8-001", 101L, 300L, request))
        .thenThrow(new QuoteIngestException("BOM 行不属于当前产品行"));

    CommonResult<QuoteCostingWorkbenchBomRowResponse> result =
        controller.updateCostingBomRow("OA-T8-001", 101L, 300L, request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("当前产品行");
  }

  @Test
  void confirmCostingBomReturnsServiceResponse() {
    QuoteBomConfirmRequest request = new QuoteBomConfirmRequest();
    request.setConfirmRemark("确认报价物料");
    QuoteBomConfirmResponse response = new QuoteBomConfirmResponse();
    response.setConfirmNo("BOM-CF-001");
    response.setConfirmStatus("CONFIRMED");
    when(quoteBomConfirmationService.confirm("OA-T8-001", 101L, request)).thenReturn(response);

    CommonResult<QuoteBomConfirmResponse> result =
        controller.confirmCostingBom("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getConfirmNo()).isEqualTo("BOM-CF-001");
    verify(quoteBomConfirmationService).confirm("OA-T8-001", 101L, request);
  }

  @Test
  void confirmCostingBomExceptionReturnsBadRequest() {
    QuoteBomConfirmRequest request = new QuoteBomConfirmRequest();
    when(quoteBomConfirmationService.confirm("OA-T8-001", 101L, request))
        .thenThrow(new QuoteIngestException("当前产品行 BOM 明细为空"));

    CommonResult<QuoteBomConfirmResponse> result =
        controller.confirmCostingBom("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("BOM 明细为空");
  }

  @Test
  void cancelCostingBomConfirmReturnsServiceResponse() {
    QuoteBomCancelConfirmRequest request = new QuoteBomCancelConfirmRequest();
    request.setCancelRemark("撤销后调整用量");
    QuoteBomConfirmResponse response = new QuoteBomConfirmResponse();
    response.setConfirmNo("BOM-CF-001");
    response.setConfirmStatus("INVALID");
    when(quoteBomConfirmationService.cancelConfirm("OA-T8-001", 101L, request))
        .thenReturn(response);

    CommonResult<QuoteBomConfirmResponse> result =
        controller.cancelCostingBomConfirm("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getConfirmStatus()).isEqualTo("INVALID");
    verify(quoteBomConfirmationService).cancelConfirm("OA-T8-001", 101L, request);
  }

  @Test
  void priceTypeConfirmationReturnsServiceResponse() {
    QuotePriceTypeConfirmationResponse response = new QuotePriceTypeConfirmationResponse();
    response.setPeriodMonth("2026-06");
    when(quotePriceTypeConfirmationService.getConfirmation("OA-T8-001", 101L, "2026-06"))
        .thenReturn(response);

    CommonResult<QuotePriceTypeConfirmationResponse> result =
        controller.priceTypeConfirmation("OA-T8-001", 101L, "2026-06");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPeriodMonth()).isEqualTo("2026-06");
    verify(quotePriceTypeConfirmationService).getConfirmation("OA-T8-001", 101L, "2026-06");
  }

  @Test
  void confirmPriceTypeReturnsServiceResponse() {
    QuotePriceTypeConfirmRequest request = new QuotePriceTypeConfirmRequest();
    QuotePriceTypeConfirmationActionResponse response =
        new QuotePriceTypeConfirmationActionResponse();
    response.setConfirmNo("PT-CF-001");
    response.setStatus("CONFIRMED");
    when(quotePriceTypeConfirmationService.confirm("OA-T8-001", 101L, request))
        .thenReturn(response);

    CommonResult<QuotePriceTypeConfirmationActionResponse> result =
        controller.confirmPriceType("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getConfirmNo()).isEqualTo("PT-CF-001");
    verify(quotePriceTypeConfirmationService).confirm("OA-T8-001", 101L, request);
  }

  @Test
  void importMissingPriceTypeReturnsServiceResponse() {
    QuotePriceTypeImportMissingRequest request = new QuotePriceTypeImportMissingRequest();
    QuotePriceTypeConfirmationActionResponse response =
        new QuotePriceTypeConfirmationActionResponse();
    response.getResults()
        .add(QuotePriceTypeConfirmationActionResponse.RowResult.of("MAT-1", "SUCCESS", "导入成功"));
    when(quotePriceTypeConfirmationService.importMissing("OA-T8-001", 101L, request))
        .thenReturn(response);

    CommonResult<QuotePriceTypeConfirmationActionResponse> result =
        controller.importMissingPriceType("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getResults().get(0).getStatus()).isEqualTo("SUCCESS");
    verify(quotePriceTypeConfirmationService).importMissing("OA-T8-001", 101L, request);
  }

  @Test
  void adjustPriceTypeReturnsServiceResponse() {
    QuotePriceTypeAdjustRequest request = new QuotePriceTypeAdjustRequest();
    request.setMaterialCode("MAT-1");
    QuotePriceTypeConfirmationActionResponse response =
        new QuotePriceTypeConfirmationActionResponse();
    response.getResults()
        .add(QuotePriceTypeConfirmationActionResponse.RowResult.of("MAT-1", "SUCCESS", "调整成功"));
    when(quotePriceTypeConfirmationService.adjustPriceType("OA-T8-001", 101L, request))
        .thenReturn(response);

    CommonResult<QuotePriceTypeConfirmationActionResponse> result =
        controller.adjustPriceType("OA-T8-001", 101L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getResults().get(0).getMessage()).contains("调整成功");
    verify(quotePriceTypeConfirmationService).adjustPriceType("OA-T8-001", 101L, request);
  }
}
