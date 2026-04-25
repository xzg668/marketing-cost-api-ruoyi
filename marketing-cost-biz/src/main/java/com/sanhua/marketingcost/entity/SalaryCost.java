package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_salary_cost")
public class SalaryCost {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String materialCode;
  private String productName;
  private String spec;
  private String model;
  private String refMaterialCode;
  private BigDecimal directLaborCost;
  private BigDecimal indirectLaborCost;
  private String source;
  private String businessUnit;
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD，区别于组织口径 businessUnit（V22 补齐） */
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

  public BigDecimal getDirectLaborCost() {
    return directLaborCost;
  }

  public void setDirectLaborCost(BigDecimal directLaborCost) {
    this.directLaborCost = directLaborCost;
  }

  public BigDecimal getIndirectLaborCost() {
    return indirectLaborCost;
  }

  public void setIndirectLaborCost(BigDecimal indirectLaborCost) {
    this.indirectLaborCost = indirectLaborCost;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
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
