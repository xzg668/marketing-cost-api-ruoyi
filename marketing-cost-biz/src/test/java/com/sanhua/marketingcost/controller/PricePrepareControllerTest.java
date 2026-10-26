package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateTarget;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareControllerTest {

  private PricePrepareService pricePrepareService;
  private PricePrepareQueryService queryService;
  private MakePartNoScrapConfirmationService noScrapConfirmationService;
  private PricePrepareController controller;

  @BeforeEach
  void setUp() {
    pricePrepareService = mock(PricePrepareService.class);
    queryService = mock(PricePrepareQueryService.class);
    noScrapConfirmationService = mock(MakePartNoScrapConfirmationService.class);
    controller =
        new PricePrepareController(queryService, noScrapConfirmationService);
  }

  @Test void legacyGenerateCannotAdoptEvenWithAnExplicitItem() {
    var request = new PricePrepareGenerateRequest(); request.setOaNo("OA-001"); request.setOaFormItemId(1L);
    var result = controller.generate(request);
    assertThat(result.isSuccess()).isFalse(); assertThat(result.getMsg()).contains("单产品核算工作台");
    org.mockito.Mockito.verifyNoInteractions(pricePrepareService, queryService);
  }

  @Test void legacyBulkCannotAdoptAnyOaOrTarget() {
    var request = new PricePrepareBulkGenerateRequest(); request.setOaNos(List.of("OA-001", "OA-002"));
    request.setTargets(List.of(target("OA-001", "TOP-A")));
    var result = controller.generateBulk(request);
    assertThat(result.isSuccess()).isFalse(); assertThat(result.getMsg()).contains("单产品核算工作台");
    org.mockito.Mockito.verifyNoInteractions(pricePrepareService, queryService);
  }

  @Test
  @DisplayName("候选查询：委托 QueryService")
  void candidatesDelegatesQueryService() {
    PricePrepareCandidatePageResponse mocked = new PricePrepareCandidatePageResponse(0, List.of());
    when(queryService.pageCandidates(any())).thenReturn(mocked);
    PricePrepareCandidateQueryRequest request = new PricePrepareCandidateQueryRequest();
    request.setKeyword("OA-001");

    CommonResult<PricePrepareCandidatePageResponse> result = controller.candidates(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    verify(queryService).pageCandidates(request);
  }

  @Test
  @DisplayName("OA汇总查询：委托 QueryService")
  void oaSummaryDelegatesQueryService() {
    PricePrepareOaSummaryPageResponse mocked = new PricePrepareOaSummaryPageResponse(0, List.of());
    when(queryService.pageOaSummaries(any())).thenReturn(mocked);

    CommonResult<PricePrepareOaSummaryPageResponse> result =
        controller.oaSummary(new PricePrepareOaSummaryQueryRequest());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
  }

  @Test
  @DisplayName("顶级产品汇总查询：委托 QueryService")
  void topProductSummaryDelegatesQueryService() {
    PricePrepareTopProductSummaryPageResponse mocked =
        new PricePrepareTopProductSummaryPageResponse(0, List.of());
    when(queryService.pageTopProductSummaries(any())).thenReturn(mocked);

    CommonResult<PricePrepareTopProductSummaryPageResponse> result =
        controller.topProductSummary(new PricePrepareTopProductSummaryQueryRequest());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
  }

  @Test
  @DisplayName("批次查询：空结果正常返回")
  void batchesReturnsEmptyPage() {
    PricePrepareBatchPageResponse mocked = new PricePrepareBatchPageResponse(0, List.of());
    when(queryService.pageBatches(any())).thenReturn(mocked);

    CommonResult<PricePrepareBatchPageResponse> result =
        controller.batches(batchRequest("OA-001", "2026-05", "SUCCESS"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isZero();
    assertThat(result.getData().getRecords()).isEmpty();
    ArgumentCaptor<PricePrepareBatchQueryRequest> captor =
        ArgumentCaptor.forClass(PricePrepareBatchQueryRequest.class);
    verify(queryService).pageBatches(captor.capture());
    assertThat(captor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(captor.getValue().getPeriodMonth()).isEqualTo("2026-05");
  }

  @Test
  @DisplayName("明细查询：空结果正常返回")
  void itemsReturnsEmptyPage() {
    PricePrepareItemPageResponse mocked = new PricePrepareItemPageResponse(0, List.of());
    when(queryService.pageItems(any())).thenReturn(mocked);
    PricePrepareItemQueryRequest request = new PricePrepareItemQueryRequest();
    request.setPrepareNo("PPR-1");
    request.setItemType("PACKAGE_COMPONENT");

    CommonResult<PricePrepareItemPageResponse> result = controller.items(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRecords()).isEmpty();
    ArgumentCaptor<PricePrepareItemQueryRequest> captor =
        ArgumentCaptor.forClass(PricePrepareItemQueryRequest.class);
    verify(queryService).pageItems(captor.capture());
    assertThat(captor.getValue().getPrepareNo()).isEqualTo("PPR-1");
    assertThat(captor.getValue().getItemType()).isEqualTo("PACKAGE_COMPONENT");
  }

  @Test
  @DisplayName("缺口查询：空结果正常返回")
  void gapsReturnsEmptyPage() {
    PricePrepareGapPageResponse mocked = new PricePrepareGapPageResponse(0, List.of());
    when(queryService.pageGaps(any())).thenReturn(mocked);
    PricePrepareGapQueryRequest request = new PricePrepareGapQueryRequest();
    request.setGapType("MISSING_PRICE");
    request.setOaPushStatus("PENDING");

    CommonResult<PricePrepareGapPageResponse> result = controller.gaps(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRecords()).isEmpty();
    ArgumentCaptor<PricePrepareGapQueryRequest> captor =
        ArgumentCaptor.forClass(PricePrepareGapQueryRequest.class);
    verify(queryService).pageGaps(captor.capture());
    assertThat(captor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(captor.getValue().getOaPushStatus()).isEqualTo("PENDING");
  }

  private PricePrepareBatchQueryRequest batchRequest(String oaNo, String period, String status) {
    PricePrepareBatchQueryRequest request = new PricePrepareBatchQueryRequest();
    request.setOaNo(oaNo);
    request.setPeriodMonth(period);
    request.setStatus(status);
    return request;
  }

  private PricePrepareGenerateTarget target(String oaNo, String topProductCode) {
    PricePrepareGenerateTarget target = new PricePrepareGenerateTarget();
    target.setOaNo(oaNo);
    target.setTopProductCode(topProductCode);
    return target;
  }
}
