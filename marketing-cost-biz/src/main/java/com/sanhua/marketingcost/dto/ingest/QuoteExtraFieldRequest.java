package com.sanhua.marketingcost.dto.ingest;

public class QuoteExtraFieldRequest {
  private String fieldCode;
  private String fieldName;
  private String fieldValue;
  private String valueType;
  private String sourceFieldName;
  private String sourceFieldPath;

  public String getFieldCode() {
    return fieldCode;
  }

  public void setFieldCode(String fieldCode) {
    this.fieldCode = fieldCode;
  }

  public String getFieldName() {
    return fieldName;
  }

  public void setFieldName(String fieldName) {
    this.fieldName = fieldName;
  }

  public String getFieldValue() {
    return fieldValue;
  }

  public void setFieldValue(String fieldValue) {
    this.fieldValue = fieldValue;
  }

  public String getValueType() {
    return valueType;
  }

  public void setValueType(String valueType) {
    this.valueType = valueType;
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
