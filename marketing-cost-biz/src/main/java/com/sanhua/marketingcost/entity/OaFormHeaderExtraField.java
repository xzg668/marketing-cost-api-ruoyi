package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_oa_form_header_extra_field")
public class OaFormHeaderExtraField {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long oaFormId;
  /** 表头扩展字段只关联 oa_form，不关联产品行。 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  private String fieldCode;
  private String fieldName;
  private String fieldValue;
  private BigDecimal fieldValueNumber;
  private LocalDate fieldValueDate;
  private String valueType;
  private String sourceFieldName;
  private String sourceFieldPath;
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

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
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
