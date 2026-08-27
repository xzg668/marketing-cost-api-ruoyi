package com.sanhua.marketingcost.worker;

public record CostRunTaskExecutionResult(
    String resultSummaryJson, Long costRunVersionId, String costRunNo) {

  public static CostRunTaskExecutionResult empty() {
    return new CostRunTaskExecutionResult(null, null, null);
  }
}
