package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class ProductPropertyAnnualSyncRequest {
  private Integer propertyYear;
  private String businessUnitType;
  private String attrSourceType;
  private String attrSourceBatchNo;
  private String annualUsageSourceType;
  private String annualUsageSourceBatchNo;
  private boolean usageOnly;
  private boolean requireProductCode;
  private boolean createPlaceholderOnMissing;
  private List<ProductPropertyAnnualSyncRow> rows = new ArrayList<>();

  public Integer getPropertyYear() {
    return propertyYear;
  }

  public void setPropertyYear(Integer propertyYear) {
    this.propertyYear = propertyYear;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
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

  public boolean isUsageOnly() {
    return usageOnly;
  }

  public void setUsageOnly(boolean usageOnly) {
    this.usageOnly = usageOnly;
  }

  public boolean isRequireProductCode() {
    return requireProductCode;
  }

  public void setRequireProductCode(boolean requireProductCode) {
    this.requireProductCode = requireProductCode;
  }

  public boolean isCreatePlaceholderOnMissing() {
    return createPlaceholderOnMissing;
  }

  public void setCreatePlaceholderOnMissing(boolean createPlaceholderOnMissing) {
    this.createPlaceholderOnMissing = createPlaceholderOnMissing;
  }

  public List<ProductPropertyAnnualSyncRow> getRows() {
    return rows;
  }

  public void setRows(List<ProductPropertyAnnualSyncRow> rows) {
    this.rows = rows == null ? new ArrayList<>() : rows;
  }
}
