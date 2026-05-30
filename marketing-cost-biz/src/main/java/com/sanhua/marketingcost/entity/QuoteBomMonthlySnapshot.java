package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("lp_quote_bom_monthly_snapshot")
public class QuoteBomMonthlySnapshot {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String productCode;
  private String customerCode;
  private String packageMethod;
  private String costPeriodMonth;
  private String bomSource;
  private String bomPurpose;
  private String bomVersion;
  private String syncType;
  private String syncStatus;
  private LocalDateTime syncAt;
  private String syncBy;
  private String sourceOaNo;
  private Long sourceOaFormItemId;
  private String bomBatchId;
  private Integer activeFlag;
  private String errorMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getCustomerCode() {
    return customerCode;
  }

  public void setCustomerCode(String customerCode) {
    this.customerCode = customerCode;
  }

  public String getPackageMethod() {
    return packageMethod;
  }

  public void setPackageMethod(String packageMethod) {
    this.packageMethod = packageMethod;
  }

  public String getCostPeriodMonth() {
    return costPeriodMonth;
  }

  public void setCostPeriodMonth(String costPeriodMonth) {
    this.costPeriodMonth = costPeriodMonth;
  }

  public String getBomSource() {
    return bomSource;
  }

  public void setBomSource(String bomSource) {
    this.bomSource = bomSource;
  }

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
  }

  public String getBomVersion() {
    return bomVersion;
  }

  public void setBomVersion(String bomVersion) {
    this.bomVersion = bomVersion;
  }

  public String getSyncType() {
    return syncType;
  }

  public void setSyncType(String syncType) {
    this.syncType = syncType;
  }

  public String getSyncStatus() {
    return syncStatus;
  }

  public void setSyncStatus(String syncStatus) {
    this.syncStatus = syncStatus;
  }

  public LocalDateTime getSyncAt() {
    return syncAt;
  }

  public void setSyncAt(LocalDateTime syncAt) {
    this.syncAt = syncAt;
  }

  public String getSyncBy() {
    return syncBy;
  }

  public void setSyncBy(String syncBy) {
    this.syncBy = syncBy;
  }

  public String getSourceOaNo() {
    return sourceOaNo;
  }

  public void setSourceOaNo(String sourceOaNo) {
    this.sourceOaNo = sourceOaNo;
  }

  public Long getSourceOaFormItemId() {
    return sourceOaFormItemId;
  }

  public void setSourceOaFormItemId(Long sourceOaFormItemId) {
    this.sourceOaFormItemId = sourceOaFormItemId;
  }

  public String getBomBatchId() {
    return bomBatchId;
  }

  public void setBomBatchId(String bomBatchId) {
    this.bomBatchId = bomBatchId;
  }

  public Integer getActiveFlag() {
    return activeFlag;
  }

  public void setActiveFlag(Integer activeFlag) {
    this.activeFlag = activeFlag;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
