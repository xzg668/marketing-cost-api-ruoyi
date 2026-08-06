package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.service.QuoteEffectiveBomConfirmationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomConfirmationControllerTest {

  private QuoteEffectiveBomConfirmationService service;
  private QuoteEffectiveBomConfirmationController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteEffectiveBomConfirmationService.class);
    controller =
        new QuoteEffectiveBomConfirmationController(
            service, new QuoteEffectiveBomErrorMapper());
  }

  @Test
  void confirmsExactlyOneProductItem() {
    QuoteBomConfirmRequest request = new QuoteBomConfirmRequest();
    when(service.confirm("OA-1", 11L, request)).thenReturn(confirmResponse());

    CommonResult<QuoteEffectiveBomConfirmResponse> result =
        controller.confirm("OA-1", 11L, request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().buildBatchId()).isEqualTo("qeb_BUILD_1");
    verify(service).confirm("OA-1", 11L, request);
  }

  @Test
  void preparesStepTwoRowsWithoutASeparateProductDetailConfirmation() {
    when(service.prepareCostingBom("OA-1", 11L)).thenReturn(costing());

    CommonResult<QuoteBomCostingBuildResponse> result =
        controller.prepareCostingBom("OA-1", 11L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().costingRowsWritten()).isEqualTo(2);
    verify(service).prepareCostingBom("OA-1", 11L);
  }

  @Test
  void rebuildUsesTheSingleItemEffectiveRoute() {
    when(service.rebuildCostingFromEffective("OA-1", 11L))
        .thenReturn(costing());

    CommonResult<QuoteBomCostingBuildResponse> result =
        controller.rebuildFromEffective("OA-1", 11L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().buildBatchId()).isEqualTo("qeb_BUILD_1");
    verify(service).rebuildCostingFromEffective("OA-1", 11L);
  }

  @Test
  void confirmedRebuildConflictUsesStableReadableCode() {
    when(service.rebuildCostingFromEffective("OA-1", 11L))
        .thenThrow(
            new QuoteEffectiveBomQueryException(
                "EFFECTIVE_BOM_ALREADY_CONFIRMED", "当前产品已经确认"));

    CommonResult<QuoteBomCostingBuildResponse> result =
        controller.rebuildFromEffective("OA-1", 11L);

    assertThat(result.getCode()).isEqualTo(409);
    assertThat(result.getMsg()).contains("EFFECTIVE_BOM_ALREADY_CONFIRMED");
  }

  private static QuoteEffectiveBomConfirmResponse confirmResponse() {
    QuoteBomConfirmResponse confirmation = new QuoteBomConfirmResponse();
    confirmation.setCostingBuildBatchId("qeb_BUILD_1");
    return new QuoteEffectiveBomConfirmResponse(
        1L, "qeb_BUILD_1", false, false, 3, 2, confirmation);
  }

  private static QuoteBomCostingBuildResponse costing() {
    return new QuoteBomCostingBuildResponse(
        1L, null, 11L, "OA-1", "P", "NON_BARE", "2026-08", "qeb_BUILD_1",
        2, 2, 0, Map.of(), List.of(), LocalDateTime.now());
  }
}
