package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PriceFixedItemUpdateRequest {
  private String orgCode;
  private String sourceName;
  private String supplierName;
  private String supplierCode;
  private String purchaseClass;
  private String materialName;
  private String materialCode;
  private String specModel;
  private String unit;
  private String formulaExpr;
  private BigDecimal blankWeight;
  private BigDecimal netWeight;
  private BigDecimal processFee;
  private BigDecimal agentFee;
  private BigDecimal fixedPrice;
  private Boolean taxIncluded;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String orderType;
  private BigDecimal quota;
  /** V46 来源类型：PURCHASE 采购件 / MAKE 自制件 / SETTLE 结算价 / SCRAP 废料 */
  private String sourceType;
  /** V46 采购流程编号（PURCHASE 来源专用） */
  private String processNo;
  /** V46 计划价（SETTLE 来源专用） */
  private BigDecimal plannedPrice;
  /** V46 上浮比例（SETTLE 来源专用） */
  private BigDecimal markupRatio;
  /** V46 备注 */
  private String remark;
  /** V46 结算期间 YYYY-MM */
  private String pricingMonth;
  /** V47 基准结算价（C5）*/
  private BigDecimal baseSettlePrice;
  /** V47 联动结算价（C6）*/
  private BigDecimal linkedSettlePrice;
  public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
  public void setBaseSettlePrice(BigDecimal v) { this.baseSettlePrice = v; }
  public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
  public void setLinkedSettlePrice(BigDecimal v) { this.linkedSettlePrice = v; }

  public String getSourceType() { return sourceType; }
  public void setSourceType(String sourceType) { this.sourceType = sourceType; }
  public String getProcessNo() { return processNo; }
  public void setProcessNo(String processNo) { this.processNo = processNo; }
  public BigDecimal getPlannedPrice() { return plannedPrice; }
  public void setPlannedPrice(BigDecimal plannedPrice) { this.plannedPrice = plannedPrice; }
  public BigDecimal getMarkupRatio() { return markupRatio; }
  public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public String getPricingMonth() { return pricingMonth; }
  public void setPricingMonth(String pricingMonth) { this.pricingMonth = pricingMonth; }

  public String getOrgCode() {
    return orgCode;
  }

  public void setOrgCode(String orgCode) {
    this.orgCode = orgCode;
  }

  public String getSourceName() {
    return sourceName;
  }

  public void setSourceName(String sourceName) {
    this.sourceName = sourceName;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public void setSupplierName(String supplierName) {
    this.supplierName = supplierName;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public void setSupplierCode(String supplierCode) {
    this.supplierCode = supplierCode;
  }

  public String getPurchaseClass() {
    return purchaseClass;
  }

  public void setPurchaseClass(String purchaseClass) {
    this.purchaseClass = purchaseClass;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getSpecModel() {
    return specModel;
  }

  public void setSpecModel(String specModel) {
    this.specModel = specModel;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getFormulaExpr() {
    return formulaExpr;
  }

  public void setFormulaExpr(String formulaExpr) {
    this.formulaExpr = formulaExpr;
  }

  public BigDecimal getBlankWeight() {
    return blankWeight;
  }

  public void setBlankWeight(BigDecimal blankWeight) {
    this.blankWeight = blankWeight;
  }

  public BigDecimal getNetWeight() {
    return netWeight;
  }

  public void setNetWeight(BigDecimal netWeight) {
    this.netWeight = netWeight;
  }

  public BigDecimal getProcessFee() {
    return processFee;
  }

  public void setProcessFee(BigDecimal processFee) {
    this.processFee = processFee;
  }

  public BigDecimal getAgentFee() {
    return agentFee;
  }

  public void setAgentFee(BigDecimal agentFee) {
    this.agentFee = agentFee;
  }

  public BigDecimal getFixedPrice() {
    return fixedPrice;
  }

  public void setFixedPrice(BigDecimal fixedPrice) {
    this.fixedPrice = fixedPrice;
  }

  public Boolean getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Boolean taxIncluded) {
    this.taxIncluded = taxIncluded;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getOrderType() {
    return orderType;
  }

  public void setOrderType(String orderType) {
    this.orderType = orderType;
  }

  public BigDecimal getQuota() {
    return quota;
  }

  public void setQuota(BigDecimal quota) {
    this.quota = quota;
  }
}
