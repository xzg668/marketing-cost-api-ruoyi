package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_product_property")
public class ProductProperty {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String level1Code;
  private String level1Name;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentModel;
  private String period;
  private String productAttr;

  /** V11 新增：产品属性系数 —— 调整后制造成本 = 制造成本 × 系数（标准品=1.0000） */
  private BigDecimal coefficient;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
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

  public BigDecimal getCoefficient() {
    return coefficient;
  }

  public void setCoefficient(BigDecimal coefficient) {
    this.coefficient = coefficient;
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
