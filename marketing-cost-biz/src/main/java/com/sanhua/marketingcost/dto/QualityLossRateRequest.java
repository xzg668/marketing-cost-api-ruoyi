package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class QualityLossRateRequest {
  private String company;
  private String businessUnit;
  private String productCategory;
  private String productSubcategory;
  private BigDecimal lossRate;
  private String customer;
  private String period;
  private Integer rateYear;
  private String sourceBasis;
  private String businessUnitType;
  private String businessDivision;
  private String productCode;
  private String productName;
  private String productModel;
  private String productSpec;
  private String remark;
  private String sourceType;
  private String sourceBatchNo;

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
}
