package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.ingest.QuoteIngestException;

/** 已有协作尚未就绪；携带原业务步骤，避免被误报成BOM系统异常。 */
public class CollaborationCostingPendingException extends QuoteIngestException {

  private final String blockingStatus;
  private final String errorCode;
  private final int gapCount;

  public CollaborationCostingPendingException(
      String blockingStatus, String errorCode, int gapCount, String message) {
    super(message);
    this.blockingStatus = blockingStatus;
    this.errorCode = errorCode;
    this.gapCount = Math.max(1, gapCount);
  }

  public String blockingStatus() {
    return blockingStatus;
  }

  public String errorCode() {
    return errorCode;
  }

  public int gapCount() {
    return gapCount;
  }
}
