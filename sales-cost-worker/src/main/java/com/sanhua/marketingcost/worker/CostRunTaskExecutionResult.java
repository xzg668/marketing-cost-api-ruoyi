package com.sanhua.marketingcost.worker;

public record CostRunTaskExecutionResult(String resultSummaryJson) {

  public static CostRunTaskExecutionResult empty() {
    return new CostRunTaskExecutionResult(null);
  }
}
