package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_department_fund_rate")
public class DepartmentFundRate {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnit;
  private BigDecimal overhaulRate;
  private BigDecimal toolingRepairRate;
  private BigDecimal waterPowerRate;
  private BigDecimal otherRate;
  private BigDecimal upliftRate;
  private BigDecimal manhourRate;

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

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
  }

  public BigDecimal getOverhaulRate() {
    return overhaulRate;
  }

  public void setOverhaulRate(BigDecimal overhaulRate) {
    this.overhaulRate = overhaulRate;
  }

  public BigDecimal getToolingRepairRate() {
    return toolingRepairRate;
  }

  public void setToolingRepairRate(BigDecimal toolingRepairRate) {
    this.toolingRepairRate = toolingRepairRate;
  }

  public BigDecimal getWaterPowerRate() {
    return waterPowerRate;
  }

  public void setWaterPowerRate(BigDecimal waterPowerRate) {
    this.waterPowerRate = waterPowerRate;
  }

  public BigDecimal getOtherRate() {
    return otherRate;
  }

  public void setOtherRate(BigDecimal otherRate) {
    this.otherRate = otherRate;
  }

  public BigDecimal getUpliftRate() {
    return upliftRate;
  }

  public void setUpliftRate(BigDecimal upliftRate) {
    this.upliftRate = upliftRate;
  }

  public BigDecimal getManhourRate() {
    return manhourRate;
  }

  public void setManhourRate(BigDecimal manhourRate) {
    this.manhourRate = manhourRate;
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
