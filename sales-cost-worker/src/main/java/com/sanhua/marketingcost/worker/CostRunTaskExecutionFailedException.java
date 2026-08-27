package com.sanhua.marketingcost.worker;

/** Structured executor failure; retry policy is decided by the product pipeline. */
public class CostRunTaskExecutionFailedException extends RuntimeException {

  private final boolean retryable;

  public CostRunTaskExecutionFailedException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
