package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePageResponse;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskQueryRequest;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import com.sanhua.marketingcost.service.MonthlyRepriceQueryService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("T9 月度调价查询 Controller")
class MonthlyRepriceQueryControllerTest {

  private MonthlyRepriceQueryService queryService;
  private MonthlyRepriceProgressService progressService;
  private MonthlyRepriceQueryController controller;

  @BeforeEach
  void setUp() {
    queryService = mock(MonthlyRepriceQueryService.class);
    progressService = mock(MonthlyRepriceProgressService.class);
    controller = new MonthlyRepriceQueryController(queryService, progressService);
  }

  @Test
  @DisplayName("GET /batches：组装分页筛选排序请求")
  void listBatchesBuildsQueryRequest() {
    when(queryService.pageBatches(any()))
        .thenReturn(new MonthlyRepricePageResponse<>(0, List.of()));

    controller.listBatches(
        "MRP", "2026-05", "COMMERCIAL", "CONFIRMED", "alice", "bob",
        2, 30, "confirmedAt", "asc");

    ArgumentCaptor<MonthlyRepriceBatchQueryRequest> captor =
        ArgumentCaptor.forClass(MonthlyRepriceBatchQueryRequest.class);
    verify(queryService).pageBatches(captor.capture());
    assertThat(captor.getValue().getRepriceNo()).isEqualTo("MRP");
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getStatus()).isEqualTo("CONFIRMED");
    assertThat(captor.getValue().getPage()).isEqualTo(2);
    assertThat(captor.getValue().getPageSize()).isEqualTo(30);
    assertThat(captor.getValue().getSortBy()).isEqualTo("confirmedAt");
  }

  @Test
  @DisplayName("GET /progress：先校验批次可见，再查进度")
  void progressChecksBatchVisibilityBeforeProgress() {
    MonthlyRepriceBatchDto batch = new MonthlyRepriceBatchDto();
    batch.setRepriceNo("MRP-001");
    MonthlyRepriceProgressSnapshot progress = new MonthlyRepriceProgressSnapshot();
    progress.setRepriceNo("MRP-001");
    when(queryService.getBatch("MRP-001")).thenReturn(batch);
    when(progressService.getProgress("MRP-001")).thenReturn(progress);

    assertThat(controller.progress("MRP-001").getData()).isSameAs(progress);
  }

  @Test
  @DisplayName("GET /batches/{repriceNo}：不存在时返回清晰错误")
  void getBatchReturnsNotFoundWhenMissing() {
    when(queryService.getBatch("MRP-MISSING")).thenReturn(null);

    var response = controller.getBatch("MRP-MISSING");

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getMsg()).contains("not found");
  }

  @Test
  @DisplayName("GET /tasks 和 /results：透传批次号与筛选条件")
  void tasksAndResultsBuildRequests() {
    when(queryService.pageTasks(any(), any()))
        .thenReturn(new MonthlyRepricePageResponse<>(0, List.of()));
    when(queryService.pageResults(any(), any()))
        .thenReturn(new MonthlyRepricePageResponse<>(0, List.of()));

    controller.tasks("MRP-001", "FAILED", "OA-001", 1, 20, "status", "desc");
    controller.results("MRP-001", "OA-001", "P-001", "客户", "SUCCESS", "OBJ", 3, 40, "totalCost", "desc");

    ArgumentCaptor<MonthlyRepriceTaskQueryRequest> taskCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceTaskQueryRequest.class);
    ArgumentCaptor<MonthlyRepriceResultQueryRequest> resultCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceResultQueryRequest.class);
    verify(queryService).pageTasks(org.mockito.ArgumentMatchers.eq("MRP-001"), taskCaptor.capture());
    verify(queryService).pageResults(org.mockito.ArgumentMatchers.eq("MRP-001"), resultCaptor.capture());
    assertThat(taskCaptor.getValue().getStatus()).isEqualTo("FAILED");
    assertThat(taskCaptor.getValue().getKeyword()).isEqualTo("OA-001");
    assertThat(resultCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(resultCaptor.getValue().getProductCode()).isEqualTo("P-001");
    assertThat(resultCaptor.getValue().getPage()).isEqualTo(3);
  }

  @Test
  @DisplayName("GET /results/{resultId}/part-items：下钻月度调价部品明细")
  void partItemsDelegatesToQueryService() {
    MonthlyRepricePartItemDto item = new MonthlyRepricePartItemDto();
    item.setPartCode("PART-1");
    when(queryService.listPartItems("MRP-001", 11L)).thenReturn(List.of(item));

    var response = controller.partItems("MRP-001", 11L);

    assertThat(response.getData()).extracting("partCode").containsExactly("PART-1");
    verify(queryService).listPartItems("MRP-001", 11L);
  }

  @Test
  @DisplayName("GET /results/{resultId}/cost-items：下钻月度调价成本项明细")
  void costItemsDelegatesToQueryService() {
    MonthlyRepriceCostItemDto item = new MonthlyRepriceCostItemDto();
    item.setCostItemCode("MATERIAL");
    when(queryService.listCostItems("MRP-001", 11L)).thenReturn(List.of(item));

    var response = controller.costItems("MRP-001", 11L);

    assertThat(response.getData()).extracting("costItemCode").containsExactly("MATERIAL");
    verify(queryService).listCostItems("MRP-001", 11L);
  }

  @Test
  @DisplayName("GET /audit-logs：组装审计日志分页筛选排序请求")
  void auditLogsBuildsQueryRequest() {
    when(queryService.pageAuditLogs(any()))
        .thenReturn(new MonthlyRepricePageResponse<>(0, List.of()));

    controller.auditLogs(
        "MRP-001", "2026-05", "COMMERCIAL", "CONFIRM_BATCH", "alice",
        2, 30, "operationTime", "desc");

    ArgumentCaptor<MonthlyRepriceAuditLogQueryRequest> captor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLogQueryRequest.class);
    verify(queryService).pageAuditLogs(captor.capture());
    assertThat(captor.getValue().getRepriceNo()).isEqualTo("MRP-001");
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getOperationType()).isEqualTo("CONFIRM_BATCH");
    assertThat(captor.getValue().getOperatorName()).isEqualTo("alice");
    assertThat(captor.getValue().getPage()).isEqualTo(2);
    assertThat(captor.getValue().getPageSize()).isEqualTo(30);
    assertThat(captor.getValue().getSortBy()).isEqualTo("operationTime");
  }

  @Test
  @DisplayName("GET /active-lock：普通成本页读取月度调价锁定提示")
  void activeLockDelegatesToQueryService() {
    controller.activeLock();

    verify(queryService).getActiveLock();
  }

  @Test
  @DisplayName("查询接口允许月度调价复核权限或现有成本结果查询权限")
  void queryEndpointsHavePreAuthorize() throws Exception {
    Method results = MonthlyRepriceQueryController.class.getMethod(
        "results",
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        Integer.class,
        Integer.class,
        String.class,
        String.class);

    Method auditLogs = MonthlyRepriceQueryController.class.getMethod(
        "auditLogs",
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        Integer.class,
        Integer.class,
        String.class,
        String.class);

    assertThat(results.getAnnotation(PreAuthorize.class).value())
        .contains("price:monthly-reprice:review", "cost:run:list");
    assertThat(auditLogs.getAnnotation(PreAuthorize.class).value())
        .contains("price:monthly-reprice:review", "cost:run:list");
  }
}
