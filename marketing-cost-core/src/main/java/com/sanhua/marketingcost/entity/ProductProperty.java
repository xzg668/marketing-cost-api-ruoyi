package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 业务年度产品属性清单，唯一键为业务单元 + 年度 + 料号。 */
@TableName("lp_product_property")
public class ProductProperty {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Integer propertyYear;
  private String businessDivision;
  private String productCode;
  private String productName;
  private String productSpec;
  private String productModel;
  private String productAttr;
  private String remark;
  private String sourceType;
  private String sourceBatchNo;
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  @TableField(exist = false)
  private BigDecimal upliftRate;
  @TableField(exist = false)
  private BigDecimal coefficient;
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long value) { this.id = value; }
  public Integer getPropertyYear() { return propertyYear; }
  public void setPropertyYear(Integer value) { this.propertyYear = value; }
  public String getBusinessDivision() { return businessDivision; }
  public void setBusinessDivision(String value) { this.businessDivision = value; }
  public String getProductCode() { return productCode; }
  public void setProductCode(String value) { this.productCode = value; }
  public String getProductName() { return productName; }
  public void setProductName(String value) { this.productName = value; }
  public String getProductSpec() { return productSpec; }
  public void setProductSpec(String value) { this.productSpec = value; }
  public String getProductModel() { return productModel; }
  public void setProductModel(String value) { this.productModel = value; }
  public String getProductAttr() { return productAttr; }
  public void setProductAttr(String value) { this.productAttr = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { this.remark = value; }
  public String getSourceType() { return sourceType; }
  public void setSourceType(String value) { this.sourceType = value; }
  public String getSourceBatchNo() { return sourceBatchNo; }
  public void setSourceBatchNo(String value) { this.sourceBatchNo = value; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String value) { this.businessUnitType = value; }
  public BigDecimal getUpliftRate() { return upliftRate; }
  public void setUpliftRate(BigDecimal value) { this.upliftRate = value; }
  public BigDecimal getCoefficient() { return coefficient; }
  public void setCoefficient(BigDecimal value) { this.coefficient = value; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
