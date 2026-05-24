package com.sanhua.marketingcost.dto.priceprepare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 实时成本计算前的价格准备就绪校验结果。 */
public class PricePrepareReadinessResult {

  private String status;
  private boolean allowContinue;
  private boolean blocking;
  private boolean warning;
  private String message;
  private String prepareNo;
  private String periodMonth;
  private String batchStatus;
  private int gapCount;
  private List<String> gapSummaries = new ArrayList<>();

  public static PricePrepareReadinessResult ready(
      String prepareNo, String periodMonth, String batchStatus) {
    PricePrepareReadinessResult result = new PricePrepareReadinessResult();
    result.setStatus("READY");
    result.setAllowContinue(true);
    result.setBlocking(false);
    result.setWarning(false);
    result.setPrepareNo(prepareNo);
    result.setPeriodMonth(periodMonth);
    result.setBatchStatus(batchStatus);
    result.setMessage("价格准备已完成");
    return result;
  }

  public static PricePrepareReadinessResult notReady(
      String status,
      boolean allowContinue,
      boolean blocking,
      String message,
      String prepareNo,
      String periodMonth,
      String batchStatus,
      int gapCount,
      List<String> gapSummaries) {
    PricePrepareReadinessResult result = new PricePrepareReadinessResult();
    result.setStatus(status);
    result.setAllowContinue(allowContinue);
    result.setBlocking(blocking);
    result.setWarning(true);
    result.setMessage(message);
    result.setPrepareNo(prepareNo);
    result.setPeriodMonth(periodMonth);
    result.setBatchStatus(batchStatus);
    result.setGapCount(gapCount);
    result.setGapSummaries(gapSummaries == null ? Collections.emptyList() : gapSummaries);
    return result;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public boolean isAllowContinue() {
    return allowContinue;
  }

  public void setAllowContinue(boolean allowContinue) {
    this.allowContinue = allowContinue;
  }

  public boolean isBlocking() {
    return blocking;
  }

  public void setBlocking(boolean blocking) {
    this.blocking = blocking;
  }

  public boolean isWarning() {
    return warning;
  }

  public void setWarning(boolean warning) {
    this.warning = warning;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getPrepareNo() {
    return prepareNo;
  }

  public void setPrepareNo(String prepareNo) {
    this.prepareNo = prepareNo;
  }

  public String getPeriodMonth() {
    return periodMonth;
  }

  public void setPeriodMonth(String periodMonth) {
    this.periodMonth = periodMonth;
  }

  public String getBatchStatus() {
    return batchStatus;
  }

  public void setBatchStatus(String batchStatus) {
    this.batchStatus = batchStatus;
  }

  public int getGapCount() {
    return gapCount;
  }

  public void setGapCount(int gapCount) {
    this.gapCount = gapCount;
  }

  public List<String> getGapSummaries() {
    return gapSummaries;
  }

  public void setGapSummaries(List<String> gapSummaries) {
    this.gapSummaries =
        gapSummaries == null ? new ArrayList<>() : new ArrayList<>(gapSummaries);
  }
}
