package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_quality_loss_rate")
public class QualityLossRate {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String company;
  private String businessUnit;
  private String productCategory;
  private String productSubcategory;
  private BigDecimal lossRate;
  private String customer;
  private String period;
  private Integer rateYear;
  private String sourceBasis;
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD，区别于组织口径 businessUnit（V22 补齐） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  private String businessDivision;
  private String productCode;
  private String productName;
  private String productModel;
  private String productSpec;
  private String remark;
  private String sourceType;
  private String sourceBatchNo;
  private String matchLevel;
  private String matchKey;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getCompany() {
    return company;
  }

  public void setCompany(String company) {
    this.company = company;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
  }

  public String getProductCategory() {
    return productCategory;
  }

  public void setProductCategory(String productCategory) {
    this.productCategory = productCategory;
  }

  public String getProductSubcategory() {
    return productSubcategory;
  }

  public void setProductSubcategory(String productSubcategory) {
    this.productSubcategory = productSubcategory;
  }

  public BigDecimal getLossRate() {
    return lossRate;
  }

  public void setLossRate(BigDecimal lossRate) {
    this.lossRate = lossRate;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public Integer getRateYear() {
    return rateYear;
  }

  public void setRateYear(Integer rateYear) {
    this.rateYear = rateYear;
  }

  public String getSourceBasis() {
    return sourceBasis;
  }

  public void setSourceBasis(String sourceBasis) {
    this.sourceBasis = sourceBasis;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getBusinessDivision() {
    return businessDivision;
  }

  public void setBusinessDivision(String businessDivision) {
    this.businessDivision = businessDivision;
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

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getProductSpec() {
    return productSpec;
  }

  public void setProductSpec(String productSpec) {
    this.productSpec = productSpec;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceBatchNo() {
    return sourceBatchNo;
  }

  public void setSourceBatchNo(String sourceBatchNo) {
    this.sourceBatchNo = sourceBatchNo;
  }

  public String getMatchLevel() {
    return matchLevel;
  }

  public void setMatchLevel(String matchLevel) {
    this.matchLevel = matchLevel;
  }

  public String getMatchKey() {
    return matchKey;
  }

  public void setMatchKey(String matchKey) {
    this.matchKey = matchKey;
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
