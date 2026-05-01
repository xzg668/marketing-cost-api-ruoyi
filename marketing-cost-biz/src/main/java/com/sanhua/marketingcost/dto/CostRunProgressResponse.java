package com.sanhua.marketingcost.dto;

public class CostRunProgressResponse {
  private int percent;
  private String status;
  private String message;
  /**
   * T17：排队位置。0 = 正在跑（或已完成 / IDLE）；&gt;0 = 排队中第 N 位（前端展示
   * "前面 N-1 个试算在排队，预计等 X 秒"）。
   */
  private int queuePos;

  public int getPercent() {
    return percent;
  }

  public void setPercent(int percent) {
    this.percent = percent;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public int getQueuePos() {
    return queuePos;
  }

  public void setQueuePos(int queuePos) {
    this.queuePos = queuePos;
  }
}
