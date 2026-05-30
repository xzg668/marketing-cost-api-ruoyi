package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.service.MonthlyRepriceCostRunAdapter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MonthlyRepriceCostRunTaskExecutor implements CostRunTaskExecutor {

  private final MonthlyRepriceCostRunAdapter costRunAdapter;

  public MonthlyRepriceCostRunTaskExecutor(MonthlyRepriceCostRunAdapter costRunAdapter) {
    this.costRunAdapter = costRunAdapter;
  }

  @Override
  public CostRunTaskScene scene() {
    return CostRunTaskScene.MONTHLY_REPRICE;
  }

  @Override
  public CostRunTaskExecutionResult execute(CostRunTask task, String workerId) {
    if (task == null || !StringUtils.hasText(task.getSourceNo())
        || !StringUtils.hasText(task.getCalcObjectKey())) {
      throw new IllegalArgumentException("MONTHLY_REPRICE 通用任务缺少 repriceNo 或 calcObjectKey");
    }
    if (!StringUtils.hasText(workerId)) {
      throw new IllegalArgumentException("workerId 不能为空");
    }
    CostRunObjectResult result = costRunAdapter.execute(task, workerId);
    return new CostRunTaskExecutionResult(resultSummaryJson(result));
  }

  private String resultSummaryJson(CostRunObjectResult result) {
    int partItemCount = result.getPartItems() == null ? 0 : result.getPartItems().size();
    int costItemCount = result.getCostItems() == null ? 0 : result.getCostItems().size();
    String totalCost = result.getResult() == null || result.getResult().getTotalCost() == null
        ? null
        : result.getResult().getTotalCost().toPlainString();
    return "{\"partItemCount\":" + partItemCount
        + ",\"costItemCount\":" + costItemCount
        + ",\"totalCost\":" + (totalCost == null ? "null" : "\"" + totalCost + "\"")
        + "}";
  }
}
