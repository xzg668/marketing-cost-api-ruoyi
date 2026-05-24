package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class DepartmentFundRateRequest {
  private String businessUnit;
  private String businessDivision;
  private String expenseSubject;
  private BigDecimal budgetAmount;
  private BigDecimal totalWorkMinutes;
  private BigDecimal planRate;
  private BigDecimal upliftRatio;
  private BigDecimal quoteRatio;
  private Integer rateYear;
  private String businessUnitType;
  private String remark;
  private BigDecimal overhaulRate;
  private BigDecimal toolingRepairRate;
  private BigDecimal waterPowerRate;
  private BigDecimal otherRate;
  private BigDecimal upliftRate;
  private BigDecimal manhourRate;

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
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

  public Integer getRateYear() {
    return rateYear;
  }

  public void setRateYear(Integer rateYear) {
    this.rateYear = rateYear;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
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
}
