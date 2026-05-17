package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 联动价计算结果行（BOM 部品页展示口径）。
 *
 * <p>扩展点：
 * <ul>
 *   <li>{@code calcId} —— 对应 {@code lp_price_linked_calc_item.id}，前端点"查看 trace"时回传</li>
 *   <li>{@code formulaExprCn} —— 中文公式原文，前端 trace 弹窗渲染用</li>
 *   <li>{@code formulaExpr} —— 规范化后 {@code [code]} 形式公式，对账时配合 variables 看</li>
 * </ul>
 */
public class PriceLinkedCalcRow {
  private Long calcId;
  private String oaNo;
  private String customer;
  private String businessUnitType;
  private String itemCode;
  private String materialName;
  private String supplierName;
  private String pricingMonth;
  private String shapeAttr;
  private BigDecimal bomQty;
  private BigDecimal partUnitPrice;
  private BigDecimal partAmount;
  private String formulaExpr;
  private String formulaExprCn;
  private Boolean hasTrace;
  private String calcStatus;
  private Map<String, Integer> variableSourceSummary;
  private LocalDateTime updatedAt;

  public Long getCalcId() {
    return calcId;
  }

  public void setCalcId(Long calcId) {
    this.calcId = calcId;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public void setSupplierName(String supplierName) {
    this.supplierName = supplierName;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public BigDecimal getBomQty() {
    return bomQty;
  }

  public void setBomQty(BigDecimal bomQty) {
    this.bomQty = bomQty;
  }

  public BigDecimal getPartUnitPrice() {
    return partUnitPrice;
  }

  public void setPartUnitPrice(BigDecimal partUnitPrice) {
    this.partUnitPrice = partUnitPrice;
  }

  public BigDecimal getPartAmount() {
    return partAmount;
  }

  public void setPartAmount(BigDecimal partAmount) {
    this.partAmount = partAmount;
  }

  public String getFormulaExpr() {
    return formulaExpr;
  }

  public void setFormulaExpr(String formulaExpr) {
    this.formulaExpr = formulaExpr;
  }

  public String getFormulaExprCn() {
    return formulaExprCn;
  }

  public void setFormulaExprCn(String formulaExprCn) {
    this.formulaExprCn = formulaExprCn;
  }

  public Boolean getHasTrace() {
    return hasTrace;
  }

  public void setHasTrace(Boolean hasTrace) {
    this.hasTrace = hasTrace;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public Map<String, Integer> getVariableSourceSummary() {
    return variableSourceSummary;
  }

  public void setVariableSourceSummary(Map<String, Integer> variableSourceSummary) {
    this.variableSourceSummary = variableSourceSummary;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
