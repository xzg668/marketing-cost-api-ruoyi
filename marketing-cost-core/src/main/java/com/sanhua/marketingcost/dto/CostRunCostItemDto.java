package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class CostRunCostItemDto {
  private Long id;
  private String costCode;
  private String costName;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  /** T10：缺率/异常说明，非空时前端展示告警提示，空表示正常 */
  private String remark;
  /**
   * T24：分类标记，区分两类语义：
   * EXPENSE = 传统费用项（参与 totalAmount 累加），
   * BOM_BUCKET = 见机表原材料汇总（仅展示，不参与累加）。
   * 默认 EXPENSE，新增见机表汇总行时显式设 BOM_BUCKET。
   */
  private String category;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }
}
