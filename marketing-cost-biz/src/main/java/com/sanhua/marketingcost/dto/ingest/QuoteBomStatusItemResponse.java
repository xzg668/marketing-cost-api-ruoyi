package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class QuoteBomStatusItemResponse {
  private Long id;
  private Long oaFormItemId;
  private Integer seq;
  private String productCode;
  private String productModel;
  private String productPackagingType;
  private String mainCategoryCode;
  private String bomStatus;
  private String bomSource;
  private String bomPurpose;
  private String bomVersion;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private LocalDateTime checkedAt;
  private String syncBatchId;
  private String costPeriodMonth;
  private Long syncRecordId;
  private Long reusedFromRecordId;
  private LocalDateTime syncAt;
  private String manualTaskNo;
  private Long supplementTaskId;
  private String errorMessage;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getOaFormItemId() {
    return oaFormItemId;
  }

  public void setOaFormItemId(Long oaFormItemId) {
    this.oaFormItemId = oaFormItemId;
  }

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getProductPackagingType() {
    return productPackagingType;
  }

  public void setProductPackagingType(String productPackagingType) {
    this.productPackagingType = productPackagingType;
  }

  public String getMainCategoryCode() {
    return mainCategoryCode;
  }

  public void setMainCategoryCode(String mainCategoryCode) {
    this.mainCategoryCode = mainCategoryCode;
  }

  public String getBomStatus() {
    return bomStatus;
  }

  public void setBomStatus(String bomStatus) {
    this.bomStatus = bomStatus;
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

  public String getCostPeriodMonth() {
    return costPeriodMonth;
  }

  public void setCostPeriodMonth(String costPeriodMonth) {
    this.costPeriodMonth = costPeriodMonth;
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

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
