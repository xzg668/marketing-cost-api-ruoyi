package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class ProductPropertyAnnualSyncRow {
  private Integer rowNo;
  private Long id;
  private String businessUnitType;
  private String level1Code;
  private String level1Name;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentModel;
  private String period;
  private String productAttr;
  private Integer propertyYear;
  private String businessDivision;
  private String productCode;
  private String productName;
  private String productModel;
  private String productSpec;
  private BigDecimal annualUsage;
  private String remark;
  private String attrSourceType;
  private String attrSourceBatchNo;
  private String annualUsageSourceType;
  private String annualUsageSourceBatchNo;
  private String annualUsageOaNo;
  private String annualUsageOaLineId;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private Integer matchRiskFlag;
  private String matchRiskReason;
  private BigDecimal coefficient;

  public Integer getRowNo() {
    return rowNo;
  }

  public void setRowNo(Integer rowNo) {
    this.rowNo = rowNo;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getLevel1Code() {
    return level1Code;
  }

  public void setLevel1Code(String level1Code) {
    this.level1Code = level1Code;
  }

  public String getLevel1Name() {
    return level1Name;
  }

  public void setLevel1Name(String level1Name) {
    this.level1Name = level1Name;
  }

  public String getParentCode() {
    return parentCode;
  }

  public void setParentCode(String parentCode) {
    this.parentCode = parentCode;
  }

  public String getParentName() {
    return parentName;
  }

  public void setParentName(String parentName) {
    this.parentName = parentName;
  }

  public String getParentSpec() {
    return parentSpec;
  }

  public void setParentSpec(String parentSpec) {
    this.parentSpec = parentSpec;
  }

  public String getParentModel() {
    return parentModel;
  }

  public void setParentModel(String parentModel) {
    this.parentModel = parentModel;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getProductAttr() {
    return productAttr;
  }

  public void setProductAttr(String productAttr) {
    this.productAttr = productAttr;
  }

  public Integer getPropertyYear() {
    return propertyYear;
  }

  public void setPropertyYear(Integer propertyYear) {
    this.propertyYear = propertyYear;
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

  public BigDecimal getAnnualUsage() {
    return annualUsage;
  }

  public void setAnnualUsage(BigDecimal annualUsage) {
    this.annualUsage = annualUsage;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getAttrSourceType() {
    return attrSourceType;
  }

  public void setAttrSourceType(String attrSourceType) {
    this.attrSourceType = attrSourceType;
  }

  public String getAttrSourceBatchNo() {
    return attrSourceBatchNo;
  }

  public void setAttrSourceBatchNo(String attrSourceBatchNo) {
    this.attrSourceBatchNo = attrSourceBatchNo;
  }

  public String getAnnualUsageSourceType() {
    return annualUsageSourceType;
  }

  public void setAnnualUsageSourceType(String annualUsageSourceType) {
    this.annualUsageSourceType = annualUsageSourceType;
  }

  public String getAnnualUsageSourceBatchNo() {
    return annualUsageSourceBatchNo;
  }

  public void setAnnualUsageSourceBatchNo(String annualUsageSourceBatchNo) {
    this.annualUsageSourceBatchNo = annualUsageSourceBatchNo;
  }

  public String getAnnualUsageOaNo() {
    return annualUsageOaNo;
  }

  public void setAnnualUsageOaNo(String annualUsageOaNo) {
    this.annualUsageOaNo = annualUsageOaNo;
  }

  public String getAnnualUsageOaLineId() {
    return annualUsageOaLineId;
  }

  public void setAnnualUsageOaLineId(String annualUsageOaLineId) {
    this.annualUsageOaLineId = annualUsageOaLineId;
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

  public Integer getMatchRiskFlag() {
    return matchRiskFlag;
  }

  public void setMatchRiskFlag(Integer matchRiskFlag) {
    this.matchRiskFlag = matchRiskFlag;
  }

  public String getMatchRiskReason() {
    return matchRiskReason;
  }

  public void setMatchRiskReason(String matchRiskReason) {
    this.matchRiskReason = matchRiskReason;
  }

  public BigDecimal getCoefficient() {
    return coefficient;
  }

  public void setCoefficient(BigDecimal coefficient) {
    this.coefficient = coefficient;
  }
}
