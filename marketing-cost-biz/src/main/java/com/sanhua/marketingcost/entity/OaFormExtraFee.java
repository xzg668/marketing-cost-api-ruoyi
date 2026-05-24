package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_oa_form_extra_fee")
public class OaFormExtraFee {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long oaFormId;
  private Long oaFormItemId;
  private String feeScope;
  private String businessUnitType;
  private String feeCode;
  private String feeName;
  private String feeCategory;
  private BigDecimal amount;
  private String unit;
  private Integer taxIncluded;
  private String allocationMethod;
  private BigDecimal allocatedAmount;
  private String bearer;
  private String projectNo;
  private String sourceType;
  private String sourceFieldName;
  private String sourceFieldPath;
  private String remark;
  private Long ingestLogId;
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

  public String getFeeScope() {
    return feeScope;
  }

  public void setFeeScope(String feeScope) {
    this.feeScope = feeScope;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getFeeCode() {
    return feeCode;
  }

  public void setFeeCode(String feeCode) {
    this.feeCode = feeCode;
  }

  public String getFeeName() {
    return feeName;
  }

  public void setFeeName(String feeName) {
    this.feeName = feeName;
  }

  public String getFeeCategory() {
    return feeCategory;
  }

  public void setFeeCategory(String feeCategory) {
    this.feeCategory = feeCategory;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public Integer getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Integer taxIncluded) {
    this.taxIncluded = taxIncluded;
  }

  public String getAllocationMethod() {
    return allocationMethod;
  }

  public void setAllocationMethod(String allocationMethod) {
    this.allocationMethod = allocationMethod;
  }

  public BigDecimal getAllocatedAmount() {
    return allocatedAmount;
  }

  public void setAllocatedAmount(BigDecimal allocatedAmount) {
    this.allocatedAmount = allocatedAmount;
  }

  public String getBearer() {
    return bearer;
  }

  public void setBearer(String bearer) {
    this.bearer = bearer;
  }

  public String getProjectNo() {
    return projectNo;
  }

  public void setProjectNo(String projectNo) {
    this.projectNo = projectNo;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceFieldName() {
    return sourceFieldName;
  }

  public void setSourceFieldName(String sourceFieldName) {
    this.sourceFieldName = sourceFieldName;
  }

  public String getSourceFieldPath() {
    return sourceFieldPath;
  }

  public void setSourceFieldPath(String sourceFieldPath) {
    this.sourceFieldPath = sourceFieldPath;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public Long getIngestLogId() {
    return ingestLogId;
  }

  public void setIngestLogId(Long ingestLogId) {
    this.ingestLogId = ingestLogId;
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
