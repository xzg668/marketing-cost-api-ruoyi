package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;

public class QuoteNormalizedExtraField {
  private String scope;
  private Integer itemSeq;
  private String externalLineId;
  private String fieldCode;
  private String fieldName;
  private String fieldValue;
  private BigDecimal fieldValueNumber;
  private LocalDate fieldValueDate;
  private String valueType;
  private String sourceFieldName;
  private String sourceFieldPath;

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public Integer getItemSeq() {
    return itemSeq;
  }

  public void setItemSeq(Integer itemSeq) {
    this.itemSeq = itemSeq;
  }

  public String getExternalLineId() {
    return externalLineId;
  }

  public void setExternalLineId(String externalLineId) {
    this.externalLineId = externalLineId;
  }

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

  public BigDecimal getFieldValueNumber() {
    return fieldValueNumber;
  }

  public void setFieldValueNumber(BigDecimal fieldValueNumber) {
    this.fieldValueNumber = fieldValueNumber;
  }

  public LocalDate getFieldValueDate() {
    return fieldValueDate;
  }

  public void setFieldValueDate(LocalDate fieldValueDate) {
    this.fieldValueDate = fieldValueDate;
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
