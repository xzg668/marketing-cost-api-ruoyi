package com.sanhua.marketingcost.service.electronicdrawing;

/** 交给现有核算 worker 做有限退避重试的稳定异常，不向页面暴露上游 HTTP 细节。 */
public class ElectronicDrawingWorkflowRetryException extends RuntimeException {

  public ElectronicDrawingWorkflowRetryException(String message) {
    super(message);
  }

  public ElectronicDrawingWorkflowRetryException(String message, Throwable cause) {
    super(message, cause);
  }
}
