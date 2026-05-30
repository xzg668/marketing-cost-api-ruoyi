package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.util.List;
import java.util.Set;

/** 通用成本核算任务抢占与状态推进服务。 */
public interface CostRunTaskClaimService {

  List<CostRunTask> claimTasks(
      String workerId, Set<CostRunTaskScene> scenes, int batchSize, int lockTimeoutMinutes);

  boolean markSuccess(Long taskId, String workerId, String resultSummaryJson);

  boolean markRetryable(Long taskId, String workerId, String errorMessage, String errorStack);

  boolean markFailure(Long taskId, String workerId, String errorMessage, String errorStack);

  boolean recordFailure(
      Long taskId, String workerId, boolean retryable, String errorMessage, String errorStack);
}
