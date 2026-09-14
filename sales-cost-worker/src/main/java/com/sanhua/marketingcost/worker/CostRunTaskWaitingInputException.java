package com.sanhua.marketingcost.worker;

/** 产品存在业务资料缺口；worker 应结束本次任务并标记为等待资料。 */
public class CostRunTaskWaitingInputException extends RuntimeException {

  private final String resultSummaryJson;

  public CostRunTaskWaitingInputException(String message, String resultSummaryJson) {
    super(message);
    this.resultSummaryJson = resultSummaryJson;
  }

  public String getResultSummaryJson() {
    return resultSummaryJson;
  }
}
