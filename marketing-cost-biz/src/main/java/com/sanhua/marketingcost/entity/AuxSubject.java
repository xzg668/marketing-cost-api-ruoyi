package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_aux_subject")
public class AuxSubject {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String materialCode;
  private String productName;
  private String spec;
  private String model;
  private String refMaterialCode;
  private String auxSubjectCode;
  private String auxSubjectName;
  private BigDecimal unitPrice;
  private String period;
  private String source;
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD，用于多租户数据隔离（V22 补齐） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

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

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getSpec() {
    return spec;
  }

  public void setSpec(String spec) {
    this.spec = spec;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getRefMaterialCode() {
    return refMaterialCode;
  }

  public void setRefMaterialCode(String refMaterialCode) {
    this.refMaterialCode = refMaterialCode;
  }

  public String getAuxSubjectCode() {
    return auxSubjectCode;
  }

  public void setAuxSubjectCode(String auxSubjectCode) {
    this.auxSubjectCode = auxSubjectCode;
  }

  public String getAuxSubjectName() {
    return auxSubjectName;
  }

  public void setAuxSubjectName(String auxSubjectName) {
    this.auxSubjectName = auxSubjectName;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
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
