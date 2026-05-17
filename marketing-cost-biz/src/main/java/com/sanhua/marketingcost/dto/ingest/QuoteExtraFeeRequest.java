package com.sanhua.marketingcost.dto.ingest;

public class QuoteExtraFeeRequest {
  private String feeCode;
  private String feeName;
  private String feeCategory;
  private String amount;
  private String unit;
  private String remark;
  private String sourceFieldName;
  private String sourceFieldPath;

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

  public String getAmount() {
    return amount;
  }

  public void setAmount(String amount) {
    this.amount = amount;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
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
}
