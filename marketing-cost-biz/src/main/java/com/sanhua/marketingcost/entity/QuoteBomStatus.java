package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_quote_bom_status")
public class QuoteBomStatus {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long oaFormId;
  private Long oaFormItemId;
  private String oaNo;
  private String productCode;
  private String productType;
  private String bareProductCode;
  private Integer needPackage;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private String productModel;
  private String customerCode;
  private String packageType;
  private String packageMethod;
  private String costPeriodMonth;
  private String bomStatus;
  private String reviewStatus;
  private String bomSource;
  private String bomPurpose;
  private String bomVersion;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private LocalDateTime checkedAt;
  private String syncBatchId;
  private String costingBuildBatchId;
  private Long syncRecordId;
  private Long reusedFromRecordId;
  private LocalDateTime syncAt;
  private String manualTaskNo;
  private Long supplementTaskId;
  private Long preparationRecordId;
  private String technicianName;
  private Long reviewerUserId;
  private String reviewerName;
  private LocalDateTime reviewedAt;
  private String lockOwner;
  private LocalDateTime lockUntil;
  private String errorMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getOaFormId() {
    return oaFormId;
  }

  public void setOaFormId(Long oaFormId) {
    this.oaFormId = oaFormId;
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

  public Integer getNeedPackage() {
    return needPackage;
  }

  public void setNeedPackage(Integer needPackage) {
    this.needPackage = needPackage;
  }

  public String getReferenceFinishedCode() {
    return referenceFinishedCode;
  }

  public void setReferenceFinishedCode(String referenceFinishedCode) {
    this.referenceFinishedCode = referenceFinishedCode;
  }

  public String getSourceTopProductCode() {
    return sourceTopProductCode;
  }

  public void setSourceTopProductCode(String sourceTopProductCode) {
    this.sourceTopProductCode = sourceTopProductCode;
  }

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getCustomerCode() {
    return customerCode;
  }

  public void setCustomerCode(String customerCode) {
    this.customerCode = customerCode;
  }

  public String getPackageType() {
    return packageType;
  }

  public void setPackageType(String packageType) {
    this.packageType = packageType;
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

  public String getBomStatus() {
    return bomStatus;
  }

  public void setBomStatus(String bomStatus) {
    this.bomStatus = bomStatus;
  }

  public String getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(String reviewStatus) {
    this.reviewStatus = reviewStatus;
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

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public LocalDateTime getCheckedAt() {
    return checkedAt;
  }

  public void setCheckedAt(LocalDateTime checkedAt) {
    this.checkedAt = checkedAt;
  }

  public String getSyncBatchId() {
    return syncBatchId;
  }

  public void setSyncBatchId(String syncBatchId) {
    this.syncBatchId = syncBatchId;
  }

  public String getCostingBuildBatchId() {
    return costingBuildBatchId;
  }

  public void setCostingBuildBatchId(String costingBuildBatchId) {
    this.costingBuildBatchId = costingBuildBatchId;
  }

  public Long getSyncRecordId() {
    return syncRecordId;
  }

  public void setSyncRecordId(Long syncRecordId) {
    this.syncRecordId = syncRecordId;
  }

  public Long getReusedFromRecordId() {
    return reusedFromRecordId;
  }

  public void setReusedFromRecordId(Long reusedFromRecordId) {
    this.reusedFromRecordId = reusedFromRecordId;
  }

  public LocalDateTime getSyncAt() {
    return syncAt;
  }

  public void setSyncAt(LocalDateTime syncAt) {
    this.syncAt = syncAt;
  }

  public String getManualTaskNo() {
    return manualTaskNo;
  }

  public void setManualTaskNo(String manualTaskNo) {
    this.manualTaskNo = manualTaskNo;
  }

  public Long getSupplementTaskId() {
    return supplementTaskId;
  }

  public void setSupplementTaskId(Long supplementTaskId) {
    this.supplementTaskId = supplementTaskId;
  }

  public Long getPreparationRecordId() {
    return preparationRecordId;
  }

  public void setPreparationRecordId(Long preparationRecordId) {
    this.preparationRecordId = preparationRecordId;
  }

  public String getTechnicianName() {
    return technicianName;
  }

  public void setTechnicianName(String technicianName) {
    this.technicianName = technicianName;
  }

  public Long getReviewerUserId() {
    return reviewerUserId;
  }

  public void setReviewerUserId(Long reviewerUserId) {
    this.reviewerUserId = reviewerUserId;
  }

  public String getReviewerName() {
    return reviewerName;
  }

  public void setReviewerName(String reviewerName) {
    this.reviewerName = reviewerName;
  }

  public LocalDateTime getReviewedAt() {
    return reviewedAt;
  }

  public void setReviewedAt(LocalDateTime reviewedAt) {
    this.reviewedAt = reviewedAt;
  }

  public String getLockOwner() {
    return lockOwner;
  }

  public void setLockOwner(String lockOwner) {
    this.lockOwner = lockOwner;
  }

  public LocalDateTime getLockUntil() {
    return lockUntil;
  }

  public void setLockUntil(LocalDateTime lockUntil) {
    this.lockUntil = lockUntil;
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
