package com.sanhua.marketingcost.service.electronicdrawing;

/** 可对外说明的接收拒绝；不携带数据库错误或原始报文。 */
public class DrawingReviewRejected extends RuntimeException {
  public enum Reason {
    INVALID_CONFIRMATION,
    UNKNOWN_TASK,
    CONTENT_CONFLICT,
    BINDING_CONFLICT
  }

  private final Reason reason;

  public DrawingReviewRejected(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
