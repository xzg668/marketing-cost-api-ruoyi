package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;
import com.sanhua.marketingcost.dto.MonthlyRepriceLinkedPricePrepareResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.service.MonthlyRepriceConfirmService;
import com.sanhua.marketingcost.service.MonthlyRepriceLinkedPricePrepareService;
import com.sanhua.marketingcost.service.MonthlyRepriceObjectExpandService;
import com.sanhua.marketingcost.service.MonthlyRepriceOperationService;
import com.sanhua.marketingcost.service.MonthlyRepriceStartService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@DisplayName("T9 月度调价操作 Controller")
class MonthlyRepriceControllerTest {

  private MonthlyRepriceStartService startService;
  private MonthlyRepriceObjectExpandService expandService;
  private MonthlyRepriceLinkedPricePrepareService prepareService;
  private MonthlyRepriceConfirmService confirmService;
  private MonthlyRepriceOperationService operationService;
  private MonthlyRepriceController controller;

  @BeforeEach
  void setUp() {
    startService = mock(MonthlyRepriceStartService.class);
    expandService = mock(MonthlyRepriceObjectExpandService.class);
    prepareService = mock(MonthlyRepriceLinkedPricePrepareService.class);
    confirmService = mock(MonthlyRepriceConfirmService.class);
    operationService = mock(MonthlyRepriceOperationService.class);
    controller = new MonthlyRepriceController(
        startService, expandService, prepareService, confirmService, operationService);
  }

  @Test
  @DisplayName("POST /batches：发起月度调价并返回任务数量")
  void createBatchStartsMonthlyReprice() {
    MonthlyRepriceBatchCreateResponse response = new MonthlyRepriceBatchCreateResponse();
    response.setRepriceNo("MRP-001");
    response.setTotalCount(2);
    response.setSkippedCount(0);
    when(startService.start(any(), eq("alice"))).thenReturn(response);
    MonthlyRepriceBatchCreateRequest request = new MonthlyRepriceBatchCreateRequest();
    request.setPricingMonth("2026-05");

    CommonResult<MonthlyRepriceBatchCreateResponse> result =
        controller.createBatch(request, auth("alice"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRepriceNo()).isEqualTo("MRP-001");
    assertThat(result.getData().getTotalCount()).isEqualTo(2);
    assertThat(result.getData().getSkippedCount()).isZero();
    verify(startService).start(eq(request), eq("alice"));
  }

  @Test
  @DisplayName("POST /expand 和 /prepare-linked-price：暴露 T3/T4 编排入口")
  void expandAndPrepareDelegateToServices() {
    MonthlyRepriceObjectExpandResult expandResult =
        MonthlyRepriceObjectExpandResult.of("MRP-001", 2, 2, 0);
    MonthlyRepriceLinkedPricePrepareResult prepareResult =
        new MonthlyRepriceLinkedPricePrepareResult();
    when(expandService.expand("MRP-001", "alice")).thenReturn(expandResult);
    when(prepareService.prepare("MRP-001", "alice")).thenReturn(prepareResult);

    assertThat(controller.expand("MRP-001", auth("alice")).getData()).isSameAs(expandResult);
    assertThat(controller.prepareLinkedPrice("MRP-001", auth("alice")).getData()).isSameAs(prepareResult);
  }

  @Test
  @DisplayName("confirm/cancel/retry-failed：透传批次号和当前用户名")
  void stateOperationsDelegateToServices() {
    MonthlyRepriceProgressSnapshot snapshot = new MonthlyRepriceProgressSnapshot();
    snapshot.setRepriceNo("MRP-001");
    when(confirmService.confirm("MRP-001", "alice")).thenReturn(snapshot);
    when(operationService.cancel("MRP-001", "alice")).thenReturn(snapshot);
    when(operationService.retryFailed("MRP-001", "alice")).thenReturn(snapshot);

    assertThat(controller.confirm("MRP-001", auth("alice")).getData()).isSameAs(snapshot);
    assertThat(controller.cancel("MRP-001", auth("alice")).getData()).isSameAs(snapshot);
    assertThat(controller.retryFailed("MRP-001", auth("alice")).getData()).isSameAs(snapshot);
  }

  @Test
  @DisplayName("操作接口必须要求业务总监或操作权限")
  void operationEndpointsHavePreAuthorize() throws Exception {
    Method create = MonthlyRepriceController.class.getMethod(
        "createBatch", MonthlyRepriceBatchCreateRequest.class,
        org.springframework.security.core.Authentication.class);
    Method expand = MonthlyRepriceController.class.getMethod(
        "expand", String.class, org.springframework.security.core.Authentication.class);
    Method prepare = MonthlyRepriceController.class.getMethod(
        "prepareLinkedPrice", String.class, org.springframework.security.core.Authentication.class);
    Method confirm = MonthlyRepriceController.class.getMethod(
        "confirm", String.class, org.springframework.security.core.Authentication.class);
    Method cancel = MonthlyRepriceController.class.getMethod(
        "cancel", String.class, org.springframework.security.core.Authentication.class);
    Method retry = MonthlyRepriceController.class.getMethod(
        "retryFailed", String.class, org.springframework.security.core.Authentication.class);

    assertThat(create.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
    assertThat(expand.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
    assertThat(prepare.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
    assertThat(confirm.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
    assertThat(cancel.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
    assertThat(retry.getAnnotation(PreAuthorize.class).value())
        .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
  }

  private UsernamePasswordAuthenticationToken auth(String username) {
    return new UsernamePasswordAuthenticationToken(username, "N/A");
  }
}
