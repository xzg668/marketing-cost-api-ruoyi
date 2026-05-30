package com.sanhua.marketingcost.dto.ingest;

public class QuoteProductBomBatchOaTaskResponse {
  private int acceptedCount;
  private String message;

  public int getAcceptedCount() {
    return acceptedCount;
  }

  public void setAcceptedCount(int acceptedCount) {
    this.acceptedCount = acceptedCount;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
