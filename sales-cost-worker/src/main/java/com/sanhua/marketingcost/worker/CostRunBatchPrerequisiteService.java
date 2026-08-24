package com.sanhua.marketingcost.worker;

public interface CostRunBatchPrerequisiteService {

  PreparationSummary preparePendingQuoteBatches(
      String workerId, int limit, int staleTimeoutMinutes);

  record PreparationSummary(int candidateCount, int claimedCount, int successCount, int failedCount) {
    public static PreparationSummary empty() {
      return new PreparationSummary(0, 0, 0, 0);
    }
  }
}
