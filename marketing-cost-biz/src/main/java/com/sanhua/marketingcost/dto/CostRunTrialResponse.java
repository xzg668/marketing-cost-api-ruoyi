package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;

public class CostRunTrialResponse {
  private int productCount;
  private int partCount;
  private int costItemCount;
  private String batchNo;
  private String executionMode;
  private String taskStatus;
  private int taskCount;
  private int skippedCount;
  private boolean existingBatch;
  private PricePrepareReadinessResult pricePrepareReadiness;

  public CostRunTrialResponse() {
  }

  public CostRunTrialResponse(int productCount, int partCount, int costItemCount) {
    this.productCount = productCount;
    this.partCount = partCount;
    this.costItemCount = costItemCount;
  }

  public int getProductCount() {
    return productCount;
  }

  public void setProductCount(int productCount) {
    this.productCount = productCount;
  }

  public int getPartCount() {
    return partCount;
  }

  public void setPartCount(int partCount) {
    this.partCount = partCount;
  }

  public int getCostItemCount() {
    return costItemCount;
  }

  public void setCostItemCount(int costItemCount) {
    this.costItemCount = costItemCount;
  }

  public String getBatchNo() {
    return batchNo;
  }

  public void setBatchNo(String batchNo) {
    this.batchNo = batchNo;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public void setExecutionMode(String executionMode) {
    this.executionMode = executionMode;
  }

  public String getTaskStatus() {
    return taskStatus;
  }

  public void setTaskStatus(String taskStatus) {
    this.taskStatus = taskStatus;
  }

  public int getTaskCount() {
    return taskCount;
  }

  public void setTaskCount(int taskCount) {
    this.taskCount = taskCount;
  }

  public int getSkippedCount() {
    return skippedCount;
  }

  public void setSkippedCount(int skippedCount) {
    this.skippedCount = skippedCount;
  }

  public boolean isExistingBatch() {
    return existingBatch;
  }

  public void setExistingBatch(boolean existingBatch) {
    this.existingBatch = existingBatch;
  }

  public PricePrepareReadinessResult getPricePrepareReadiness() {
    return pricePrepareReadiness;
  }

  public void setPricePrepareReadiness(PricePrepareReadinessResult pricePrepareReadiness) {
    this.pricePrepareReadiness = pricePrepareReadiness;
  }
}
