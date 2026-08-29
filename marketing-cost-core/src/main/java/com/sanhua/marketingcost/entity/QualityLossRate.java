package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 报价质量损失率：业务表中的料号统一为裸品料号。 */
@TableName("lp_quality_loss_rate")
public class QualityLossRate {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnitType;
  private Integer rateYear;
  private String bareProductCode;
  private String productName;
  private String materialSpec;
  private String productModel;
  private String businessDivision;
  private String productCategory;
  private String productSubcategory;
  private String categorySpec;
  private String fourthLevel;
  private BigDecimal lossRate;
  private String remark;
  private String sourceType;
  private String sourceBatchNo;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long value) { this.id = value; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String value) { this.businessUnitType = value; }
  public Integer getRateYear() { return rateYear; }
  public void setRateYear(Integer value) { this.rateYear = value; }
  public String getBareProductCode() { return bareProductCode; }
  public void setBareProductCode(String value) { this.bareProductCode = value; }
  public String getProductName() { return productName; }
  public void setProductName(String value) { this.productName = value; }
  public String getMaterialSpec() { return materialSpec; }
  public void setMaterialSpec(String value) { this.materialSpec = value; }
  public String getProductModel() { return productModel; }
  public void setProductModel(String value) { this.productModel = value; }
  public String getBusinessDivision() { return businessDivision; }
  public void setBusinessDivision(String value) { this.businessDivision = value; }
  public String getProductCategory() { return productCategory; }
  public void setProductCategory(String value) { this.productCategory = value; }
  public String getProductSubcategory() { return productSubcategory; }
  public void setProductSubcategory(String value) { this.productSubcategory = value; }
  public String getCategorySpec() { return categorySpec; }
  public void setCategorySpec(String value) { this.categorySpec = value; }
  public String getFourthLevel() { return fourthLevel; }
  public void setFourthLevel(String value) { this.fourthLevel = value; }
  public BigDecimal getLossRate() { return lossRate; }
  public void setLossRate(BigDecimal value) { this.lossRate = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { this.remark = value; }
  public String getSourceType() { return sourceType; }
  public void setSourceType(String value) { this.sourceType = value; }
  public String getSourceBatchNo() { return sourceBatchNo; }
  public void setSourceBatchNo(String value) { this.sourceBatchNo = value; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
