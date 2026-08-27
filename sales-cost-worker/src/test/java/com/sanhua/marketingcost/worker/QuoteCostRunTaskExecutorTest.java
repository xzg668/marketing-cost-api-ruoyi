package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuoteCostRunTaskExecutorTest {

  @AfterEach
  void clearSecurityContext() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("T10：QUOTE worker 与单品入口调用同一个产品级流水线")
  void quoteWorkerUsesUnifiedProductPipeline() {
    AtomicReference<ProductCostingRequest> captured = new AtomicReference<>();
    ProductCostingPipeline pipeline =
        request -> {
          captured.set(request);
          return result("SUCCESS", "产品核算成功");
        };
    QuoteCostRunTaskExecutor executor = new QuoteCostRunTaskExecutor(pipeline, new ObjectMapper());

    CostRunTaskExecutionResult execution = executor.execute(task(), "worker-1");

    assertThat(captured.get().oaNo()).isEqualTo("OA-1");
    assertThat(captured.get().oaFormItemId()).isEqualTo(11L);
    assertThat(captured.get().periodMonth()).isEqualTo("2026-08");
    assertThat(captured.get().initiatedBy()).isEqualTo("worker-1");
    assertThat(captured.get().force()).isFalse();
    assertThat(execution.resultSummaryJson()).contains("\"pipelineStatus\":\"SUCCESS\"");
    assertThat(execution.costRunVersionId()).isEqualTo(88L);
    assertThat(execution.costRunNo()).isEqualTo("COST-88");
  }

  @Test
  @DisplayName("T11：异步整单任务把原提交人传给协作审计，不使用worker实例名")
  void quoteWorkerKeepsOriginalSubmitterForCollaborationAudit() {
    AtomicReference<ProductCostingRequest> captured = new AtomicReference<>();
    AtomicReference<String> contextUsername = new AtomicReference<>();
    AtomicReference<String> contextBusinessUnit = new AtomicReference<>();
    ProductCostingPipeline pipeline = request -> {
      captured.set(request);
      contextUsername.set(
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication()
              .getName());
      contextBusinessUnit.set(BusinessUnitContext.getCurrentBusinessUnitType());
      return result("SUCCESS", "产品核算成功");
    };
    QuoteCostRunTaskExecutor executor = new QuoteCostRunTaskExecutor(pipeline, new ObjectMapper());
    CostRunTask task = task();
    task.setRequestSnapshotJson("{\"submittedBy\":\"quote-user\"}");
    task.setBusinessUnitType("COMMERCIAL");

    executor.execute(task, "worker-1");

    assertThat(captured.get().initiatedBy()).isEqualTo("quote-user");
    assertThat(contextUsername.get()).isEqualTo("quote-user");
    assertThat(contextBusinessUnit.get()).isEqualTo("COMMERCIAL");
    assertThat(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication())
        .isNull();
  }

  @Test
  @DisplayName("业务资料缺口进入协作终态，不伪装成系统失败")
  void blockedPipelineResultBecomesCollaborationSignal() {
    QuoteCostRunTaskExecutor executor =
        new QuoteCostRunTaskExecutor(
            request -> result("BLOCKED", "缺少 3 项价格"), new ObjectMapper());

    assertThatThrownBy(() -> executor.execute(task(), "worker-1"))
        .isInstanceOfSatisfying(
            CostRunTaskCollaborationRequiredException.class,
            ex -> {
              assertThat(ex.getMessage()).isEqualTo("缺少 3 项价格");
              assertThat(ex.getResultSummaryJson()).contains("\"pipelineStatus\":\"BLOCKED\"");
            });
  }

  @Test
  void failedPipelineResultCarriesExplicitRetryPolicy() {
    QuoteCostRunTaskExecutor executor =
        new QuoteCostRunTaskExecutor(
            request -> result("FAILED", "价格服务超时"), new ObjectMapper());

    assertThatThrownBy(() -> executor.execute(task(), "worker-1"))
        .isInstanceOfSatisfying(
            CostRunTaskExecutionFailedException.class,
            ex -> {
              assertThat(ex.getMessage()).isEqualTo("价格服务超时");
              assertThat(ex.isRetryable()).isFalse();
            });
  }

  @Test
  @DisplayName("异步流水线抛异常后清理任务身份，避免污染下一条队列任务")
  void taskSecurityContextIsRestoredWhenPipelineThrows() {
    QuoteCostRunTaskExecutor executor =
        new QuoteCostRunTaskExecutor(
            request -> {
              throw new IllegalStateException("流水线异常");
            },
            new ObjectMapper());
    CostRunTask task = task();
    task.setRequestSnapshotJson("{\"submittedBy\":\"quote-user\"}");
    task.setBusinessUnitType("COMMERCIAL");

    assertThatThrownBy(() -> executor.execute(task, "worker-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("流水线异常");
    assertThat(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication())
        .isNull();
    assertThat(BusinessUnitContext.getCurrentBusinessUnitType()).isNull();
  }

  @Test
  @DisplayName("T9：单产品执行器不持有整张 OA 主档同步服务")
  void productExecutorHasNoMaterialMasterSyncDependency() {
    assertThat(QuoteCostRunTaskExecutor.class.getDeclaredFields())
        .extracting(java.lang.reflect.Field::getType)
        .doesNotContain(com.sanhua.marketingcost.service.MaterialMasterSyncService.class);
    assertThat(QuoteCostRunTaskExecutor.class.getDeclaredConstructors())
        .flatExtracting(constructor -> List.of(constructor.getParameterTypes()))
        .doesNotContain(com.sanhua.marketingcost.service.MaterialMasterSyncService.class);
  }

  private CostRunTask task() {
    CostRunTask task = new CostRunTask();
    task.setId(1L);
    task.setOaNo("OA-1");
    task.setOaFormItemId(11L);
    task.setPricingMonth("2026-08");
    return task;
  }

  private ProductCostingResult result(String status, String message) {
    ProductCostingResult result = new ProductCostingResult();
    result.setOaNo("OA-1");
    result.setOaFormItemId(11L);
    result.setPeriodMonth("2026-08");
    result.setPipelineStatus(status);
    result.setMessage(message);
    result.setCostVersionId(88L);
    result.setCostRunNo("COST-88");
    return result;
  }
}
