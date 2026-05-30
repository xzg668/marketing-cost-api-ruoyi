package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_cost_run_result")
public class CostRunResult {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String oaNo;
  private String productCode;
  private String productName;
  private String productModel;
  private String customerName;
  private String businessUnit;
  private String department;
  private String period;
  private String currency;
  private String unit;
  private BigDecimal totalCost;
  private String calcStatus;
  private LocalDateTime calcAt;
  private String productAttr;

  /** V16 新增：试算时命中的产品属性系数（lp_product_property.coefficient 快照） */
  private BigDecimal productAttrCoefficient;

  /** V16 新增：调整后制造成本 = 制造成本 × 系数；用作三项费用基数 */
  private BigDecimal adjustedManufactureCost;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD（区别于组织口径 businessUnit） */
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

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
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

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public BigDecimal getTotalCost() {
    return totalCost;
  }

  public void setTotalCost(BigDecimal totalCost) {
    this.totalCost = totalCost;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public LocalDateTime getCalcAt() {
    return calcAt;
  }

  public void setCalcAt(LocalDateTime calcAt) {
    this.calcAt = calcAt;
  }

  public String getProductAttr() {
    return productAttr;
  }

  public void setProductAttr(String productAttr) {
    this.productAttr = productAttr;
  }

  public BigDecimal getProductAttrCoefficient() {
    return productAttrCoefficient;
  }

  public void setProductAttrCoefficient(BigDecimal productAttrCoefficient) {
    this.productAttrCoefficient = productAttrCoefficient;
  }

  public BigDecimal getAdjustedManufactureCost() {
    return adjustedManufactureCost;
  }

  public void setAdjustedManufactureCost(BigDecimal adjustedManufactureCost) {
    this.adjustedManufactureCost = adjustedManufactureCost;
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
