package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class CostRunCostItemDto {
  private String costCode;
  private String costName;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  /** T10：缺率/异常说明，非空时前端展示告警提示，空表示正常 */
  private String remark;

  public String getCostCode() {
    return costCode;
  }

  public void setCostCode(String costCode) {
    this.costCode = costCode;
  }

  public String getCostName() {
    return costName;
  }

  public void setCostName(String costName) {
    this.costName = costName;
  }

  public BigDecimal getBaseAmount() {
    return baseAmount;
  }

  public void setBaseAmount(BigDecimal baseAmount) {
    this.baseAmount = baseAmount;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal rate) {
    this.rate = rate;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
