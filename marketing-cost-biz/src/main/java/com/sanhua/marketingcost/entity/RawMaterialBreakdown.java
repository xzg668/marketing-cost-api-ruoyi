package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原材料拆解 entity (Task #8) —— 对应 lp_raw_material_breakdown 表。
 *
 * <p>父子件多行；父件单价 = Σ(子件单价 × 子件用量)。
 */
@TableName("lp_raw_material_breakdown")
public class RawMaterialBreakdown {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String parentCode;
  private String parentName;
  private String childCode;
  private String childName;
  private String subject;
  private BigDecimal quantity;
  private String unit;
  private String period;
  private Integer seq;
  private String remark;

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
  public String getChildCode() {
    return childCode;
  }
  public void setChildCode(String childCode) {
    this.childCode = childCode;
  }
  public String getChildName() {
    return childName;
  }
  public void setChildName(String childName) {
    this.childName = childName;
  }
  public String getSubject() {
    return subject;
  }
  public void setSubject(String subject) {
    this.subject = subject;
  }
  public BigDecimal getQuantity() {
    return quantity;
  }
  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }
  public String getUnit() {
    return unit;
  }
  public void setUnit(String unit) {
    this.unit = unit;
  }
  public String getPeriod() {
    return period;
  }
  public void setPeriod(String period) {
    this.period = period;
  }
  public Integer getSeq() {
    return seq;
  }
  public void setSeq(Integer seq) {
    this.seq = seq;
  }
  public String getRemark() {
    return remark;
  }
  public void setRemark(String remark) {
    this.remark = remark;
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
