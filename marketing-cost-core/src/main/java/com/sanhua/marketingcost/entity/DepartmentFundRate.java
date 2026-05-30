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
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD，区别于组织口径 businessUnit（V22 补齐） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  private Integer rateYear;
  private String businessDivision;
  private String expenseSubject;
  private BigDecimal budgetAmount;
  private BigDecimal totalWorkMinutes;
  private BigDecimal planRate;
  private BigDecimal upliftRatio;
  private BigDecimal quoteRatio;
  private String sourceType;
  private String sourceBatchNo;
  private String remark;

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

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Integer getRateYear() {
    return rateYear;
  }

  public void setRateYear(Integer rateYear) {
    this.rateYear = rateYear;
  }

  public String getBusinessDivision() {
    return businessDivision;
  }

  public void setBusinessDivision(String businessDivision) {
    this.businessDivision = businessDivision;
  }

  public String getExpenseSubject() {
    return expenseSubject;
  }

  public void setExpenseSubject(String expenseSubject) {
    this.expenseSubject = expenseSubject;
  }

  public BigDecimal getBudgetAmount() {
    return budgetAmount;
  }

  public void setBudgetAmount(BigDecimal budgetAmount) {
    this.budgetAmount = budgetAmount;
  }

  public BigDecimal getTotalWorkMinutes() {
    return totalWorkMinutes;
  }

  public void setTotalWorkMinutes(BigDecimal totalWorkMinutes) {
    this.totalWorkMinutes = totalWorkMinutes;
  }

  public BigDecimal getPlanRate() {
    return planRate;
  }

  public void setPlanRate(BigDecimal planRate) {
    this.planRate = planRate;
  }

  public BigDecimal getUpliftRatio() {
    return upliftRatio;
  }

  public void setUpliftRatio(BigDecimal upliftRatio) {
    this.upliftRatio = upliftRatio;
  }

  public BigDecimal getQuoteRatio() {
    return quoteRatio;
  }

  public void setQuoteRatio(BigDecimal quoteRatio) {
    this.quoteRatio = quoteRatio;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceBatchNo() {
    return sourceBatchNo;
  }

  public void setSourceBatchNo(String sourceBatchNo) {
    this.sourceBatchNo = sourceBatchNo;
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
