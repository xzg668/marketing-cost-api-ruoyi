package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;
import com.sanhua.marketingcost.service.CostRunTraceQueryService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class CostRunTraceControllerTest {

  @Test
  void listReturnsTraceSnapshots() {
    CostRunTraceQueryService service = mock(CostRunTraceQueryService.class);
    CostRunTraceController controller = new CostRunTraceController(service);
    CostRunTraceListResponse payload = new CostRunTraceListResponse();
    payload.setCostRunNo("RUN-8");
    payload.setTotal(2);
    when(service.listByCostRunNo("RUN-8")).thenReturn(payload);

    CommonResult<CostRunTraceListResponse> response = controller.list("RUN-8");

    assertThat(response.isSuccess()).isTrue();
    assertThat(response.getData().getCostRunNo()).isEqualTo("RUN-8");
    assertThat(response.getData().getTotal()).isEqualTo(2);
    verify(service).listByCostRunNo("RUN-8");
  }

  @Test
  void listRejectsBlankCostRunNo() {
    CostRunTraceQueryService service = mock(CostRunTraceQueryService.class);
    CostRunTraceController controller = new CostRunTraceController(service);

    CommonResult<CostRunTraceListResponse> response = controller.list(" ");

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(response.getMsg()).contains("costRunNo");
    verifyNoInteractions(service);
  }

  @Test
  void detailReturnsTraceOnlyWhenItBelongsToCostRunNo() {
    CostRunTraceQueryService service = mock(CostRunTraceQueryService.class);
    CostRunTraceController controller = new CostRunTraceController(service);
    CostRunTraceDetailDto payload = new CostRunTraceDetailDto();
    payload.setId(31L);
    payload.setCostRunNo("RUN-8");
    payload.setAmount(new BigDecimal("88.123456"));
    when(service.getByCostRunNoAndId("RUN-8", 31L)).thenReturn(payload);

    CommonResult<CostRunTraceDetailDto> response = controller.detail("RUN-8", 31L);

    assertThat(response.isSuccess()).isTrue();
    assertThat(response.getData().getId()).isEqualTo(31L);
    assertThat(response.getData().getCostRunNo()).isEqualTo("RUN-8");
    verify(service).getByCostRunNoAndId("RUN-8", 31L);
  }

  @Test
  void detailReturnsNotFoundWhenTraceIsMissingOrBelongsToAnotherRun() {
    CostRunTraceQueryService service = mock(CostRunTraceQueryService.class);
    CostRunTraceController controller = new CostRunTraceController(service);
    when(service.getByCostRunNoAndId("RUN-8", 99L)).thenReturn(null);

    CommonResult<CostRunTraceDetailDto> response = controller.detail("RUN-8", 99L);

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getCode()).isEqualTo(GlobalErrorCodeConstants.NOT_FOUND.getCode());
    assertThat(response.getMsg()).contains("trace not found");
  }

  @Test
  void detailRejectsInvalidTraceId() {
    CostRunTraceQueryService service = mock(CostRunTraceQueryService.class);
    CostRunTraceController controller = new CostRunTraceController(service);

    CommonResult<CostRunTraceDetailDto> response = controller.detail("RUN-8", 0L);

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    verifyNoInteractions(service);
  }

  @Test
  void endpointsReuseCostRunListPermission() throws Exception {
    Method list = CostRunTraceController.class.getMethod("list", String.class);
    Method detail = CostRunTraceController.class.getMethod("detail", String.class, Long.class);

    assertThat(list.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('cost:run:list')");
    assertThat(detail.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('cost:run:list')");
  }
}
