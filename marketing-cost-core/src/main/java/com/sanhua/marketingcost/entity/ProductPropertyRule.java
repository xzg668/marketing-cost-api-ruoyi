package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_product_property_rule")
public class ProductPropertyRule {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnitType;
  private Integer propertyYear;
  private String productAttr;
  private BigDecimal upliftRate;
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String value) { this.businessUnitType = value; }
  public Integer getPropertyYear() { return propertyYear; }
  public void setPropertyYear(Integer value) { this.propertyYear = value; }
  public String getProductAttr() { return productAttr; }
  public void setProductAttr(String value) { this.productAttr = value; }
  public BigDecimal getUpliftRate() { return upliftRate; }
  public void setUpliftRate(BigDecimal value) { this.upliftRate = value; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
