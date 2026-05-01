package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_price_fixed_item")
public class PriceFixedItem {
  @TableId(type = IdType.AUTO)
  private Long id;
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
  private Integer taxIncluded;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String orderType;
  private BigDecimal quota;

  /** V46 来源类型：PURCHASE 采购件 / MAKE 自制件 / SETTLE 结算价 / SCRAP 废料 */
  private String sourceType;

  /** V46 采购流程编号（PURCHASE 来源专用） */
  private String processNo;

  /** V46 计划价（SETTLE 来源专用；fixed_price = planned × markup） */
  private BigDecimal plannedPrice;

  /** V46 上浮比例（SETTLE 来源专用） */
  private BigDecimal markupRatio;

  /** V46 备注 */
  private String remark;

  /** V46 结算期间 YYYY-MM（按月切版本快照） */
  private String pricingMonth;

  /** V47 SETTLE 来源专用：基准结算价（家用结算价9 C5 = 计划价×上浮比例） */
  private BigDecimal baseSettlePrice;

  /** V47 SETTLE 来源专用：联动结算价（家用结算价9 C6 = 按金属价公式联动算出） */
  private BigDecimal linkedSettlePrice;

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

  public Integer getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Integer taxIncluded) {
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

  public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
  public void setBaseSettlePrice(BigDecimal baseSettlePrice) { this.baseSettlePrice = baseSettlePrice; }
  public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
  public void setLinkedSettlePrice(BigDecimal linkedSettlePrice) { this.linkedSettlePrice = linkedSettlePrice; }

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
