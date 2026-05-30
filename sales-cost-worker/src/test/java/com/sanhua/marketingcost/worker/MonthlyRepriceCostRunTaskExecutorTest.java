package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.service.MonthlyRepriceCostRunAdapter;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyRepriceCostRunTaskExecutorTest {

  @Test
  @DisplayName("MONTHLY_REPRICE 直接使用通用任务执行月度调价 adapter")
  void monthlyRepriceTaskRunsThroughMonthlyAdapter() {
    Harness harness = new Harness();
    CostRunTask task = costRunTask();

    CostRunTaskExecutionResult result = harness.executor.execute(task, "worker-1");

    assertThat(result.resultSummaryJson())
        .isEqualTo("{\"partItemCount\":0,\"costItemCount\":0,\"totalCost\":\"88.66\"}");
    assertThat(harness.adapterTask).isSameAs(task);
    assertThat(harness.adapterWorkerId).isEqualTo("worker-1");
  }

  @Test
  @DisplayName("MONTHLY_REPRICE 通用任务缺少关键字段时拒绝执行")
  void missingRequiredTaskFieldFailsFast() {
    Harness harness = new Harness();
    CostRunTask task = costRunTask();
    task.setSourceNo(null);

    assertThatThrownBy(() -> harness.executor.execute(task, "worker-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repriceNo");
  }

  @Test
  @DisplayName("adapter 失败时由通用 worker 统一记录失败状态")
  void adapterFailureIsRethrownForGenericWorkerFailureHandling() {
    Harness harness = new Harness();
    harness.adapterFailure = new IllegalStateException("缺少联动价");

    assertThatThrownBy(() -> harness.executor.execute(costRunTask(), "worker-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少联动价");
  }

  private static CostRunTask costRunTask() {
    CostRunTask task = new CostRunTask();
    task.setId(501L);
    task.setBatchNo("CRM-1");
    task.setScene("MONTHLY_REPRICE");
    task.setSourceNo("MRP-001");
    task.setCalcObjectKey("OBJECT-1");
    return task;
  }

  private static CostRunObjectResult result() {
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("88.66"));
    return CostRunObjectResult.of(null, null, resultDto, List.of(), List.of());
  }

  private static class Harness {
    private final MonthlyRepriceCostRunTaskExecutor executor;
    private CostRunTask adapterTask;
    private String adapterWorkerId;
    private RuntimeException adapterFailure;

    private Harness() {
      MonthlyRepriceCostRunAdapter adapter =
          (task, workerId) -> {
            adapterTask = task;
            adapterWorkerId = workerId;
            if (adapterFailure != null) {
              throw adapterFailure;
            }
            return result();
          };
      executor = new MonthlyRepriceCostRunTaskExecutor(adapter);
    }
  }
}
