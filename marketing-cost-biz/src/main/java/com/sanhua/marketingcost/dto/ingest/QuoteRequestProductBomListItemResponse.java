package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;

public class QuoteRequestProductBomListItemResponse {
  private Long quoteBomStatusId;
  private Long oaFormItemId;
  private String oaNo;
  private String productCode;
  private String productName;
  private String productSpec;
  private String customerCode;
  private String customerName;
  private String productType;
  private String bareProductCode;
  private String packageMethod;
  private String businessUnitType;
  private String technicianName;
  private String preparationStatus;
  private Boolean bodyBomReady;
  private Boolean needPackage;
  private Boolean packageReferenceReady;
  private Boolean needTechnicianTask;
  private Long taskId;
  private String taskNo;
  private String reviewStatus;
  private String bomStatus;
  private String bomStatusLabel;
  private Boolean canCostRun;
  private LocalDateTime syncAt;
  private LocalDateTime lastHandledAt;
  private String costingBuildBatchId;
  private Long reusedFromRecordId;
  private String oaTodoId;
  private String oaTodoUrl;
  private String oaTodoPushStatus;
  private String oaTodoPushErrorMessage;
  private LocalDateTime oaTodoLastPushAt;
  private String errorMessage;

  public Long getQuoteBomStatusId() {
    return quoteBomStatusId;
  }

  public void setQuoteBomStatusId(Long quoteBomStatusId) {
    this.quoteBomStatusId = quoteBomStatusId;
  }

  public Long getOaFormItemId() {
    return oaFormItemId;
  }

  public void setOaFormItemId(Long oaFormItemId) {
    this.oaFormItemId = oaFormItemId;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductSpec() {
    return productSpec;
  }

  public void setProductSpec(String productSpec) {
    this.productSpec = productSpec;
  }

  public String getCustomerCode() {
    return customerCode;
  }

  public void setCustomerCode(String customerCode) {
    this.customerCode = customerCode;
  }

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public String getProductType() {
    return productType;
  }

  public void setProductType(String productType) {
    this.productType = productType;
  }

  public String getBareProductCode() {
    return bareProductCode;
  }

  public void setBareProductCode(String bareProductCode) {
    this.bareProductCode = bareProductCode;
  }

  public String getPackageMethod() {
    return packageMethod;
  }

  public void setPackageMethod(String packageMethod) {
    this.packageMethod = packageMethod;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getTechnicianName() {
    return technicianName;
  }

  public void setTechnicianName(String technicianName) {
    this.technicianName = technicianName;
  }

  public String getPreparationStatus() {
    return preparationStatus;
  }

  public void setPreparationStatus(String preparationStatus) {
    this.preparationStatus = preparationStatus;
  }

  public Boolean getBodyBomReady() {
    return bodyBomReady;
  }

  public void setBodyBomReady(Boolean bodyBomReady) {
    this.bodyBomReady = bodyBomReady;
  }

  public Boolean getNeedPackage() {
    return needPackage;
  }

  public void setNeedPackage(Boolean needPackage) {
    this.needPackage = needPackage;
  }

  public Boolean getPackageReferenceReady() {
    return packageReferenceReady;
  }

  public void setPackageReferenceReady(Boolean packageReferenceReady) {
    this.packageReferenceReady = packageReferenceReady;
  }

  public Boolean getNeedTechnicianTask() {
    return needTechnicianTask;
  }

  public void setNeedTechnicianTask(Boolean needTechnicianTask) {
    this.needTechnicianTask = needTechnicianTask;
  }

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long taskId) {
    this.taskId = taskId;
  }

  public String getTaskNo() {
    return taskNo;
  }

  public void setTaskNo(String taskNo) {
    this.taskNo = taskNo;
  }

  public String getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(String reviewStatus) {
    this.reviewStatus = reviewStatus;
  }

  public String getBomStatus() {
    return bomStatus;
  }

  public void setBomStatus(String bomStatus) {
    this.bomStatus = bomStatus;
  }

  public String getBomStatusLabel() {
    return bomStatusLabel;
  }

  public void setBomStatusLabel(String bomStatusLabel) {
    this.bomStatusLabel = bomStatusLabel;
  }

  public Boolean getCanCostRun() {
    return canCostRun;
  }

  public void setCanCostRun(Boolean canCostRun) {
    this.canCostRun = canCostRun;
  }

  public LocalDateTime getSyncAt() {
    return syncAt;
  }

  public void setSyncAt(LocalDateTime syncAt) {
    this.syncAt = syncAt;
  }

  public LocalDateTime getLastHandledAt() {
    return lastHandledAt;
  }

  public void setLastHandledAt(LocalDateTime lastHandledAt) {
    this.lastHandledAt = lastHandledAt;
  }

  public String getCostingBuildBatchId() {
    return costingBuildBatchId;
  }

  public void setCostingBuildBatchId(String costingBuildBatchId) {
    this.costingBuildBatchId = costingBuildBatchId;
  }

  public Long getReusedFromRecordId() {
    return reusedFromRecordId;
  }

  public void setReusedFromRecordId(Long reusedFromRecordId) {
    this.reusedFromRecordId = reusedFromRecordId;
  }

  public String getOaTodoId() {
    return oaTodoId;
  }

  public void setOaTodoId(String oaTodoId) {
    this.oaTodoId = oaTodoId;
  }

  public String getOaTodoUrl() {
    return oaTodoUrl;
  }

  public void setOaTodoUrl(String oaTodoUrl) {
    this.oaTodoUrl = oaTodoUrl;
  }

  public String getOaTodoPushStatus() {
    return oaTodoPushStatus;
  }

  public void setOaTodoPushStatus(String oaTodoPushStatus) {
    this.oaTodoPushStatus = oaTodoPushStatus;
  }

  public String getOaTodoPushErrorMessage() {
    return oaTodoPushErrorMessage;
  }

  public void setOaTodoPushErrorMessage(String oaTodoPushErrorMessage) {
    this.oaTodoPushErrorMessage = oaTodoPushErrorMessage;
  }

  public LocalDateTime getOaTodoLastPushAt() {
    return oaTodoLastPushAt;
  }

  public void setOaTodoLastPushAt(LocalDateTime oaTodoLastPushAt) {
    this.oaTodoLastPushAt = oaTodoLastPushAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
