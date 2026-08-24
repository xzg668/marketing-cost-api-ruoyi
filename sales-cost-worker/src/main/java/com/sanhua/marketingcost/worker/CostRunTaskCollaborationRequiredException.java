package com.sanhua.marketingcost.worker;

/** 产品存在业务资料缺口；worker 应结束本次任务并归入协作，而不是重试或系统失败。 */
public class CostRunTaskCollaborationRequiredException extends RuntimeException {

  private final String resultSummaryJson;

  public CostRunTaskCollaborationRequiredException(String message, String resultSummaryJson) {
    super(message);
    this.resultSummaryJson = resultSummaryJson;
  }

  public String getResultSummaryJson() {
    return resultSummaryJson;
  }
}
