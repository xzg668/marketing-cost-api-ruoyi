package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class PriceFixedItemImportRequest {
  private List<PriceFixedItemImportRow> rows;

  public List<PriceFixedItemImportRow> getRows() {
    return rows;
  }

  public void setRows(List<PriceFixedItemImportRow> rows) {
    this.rows = rows;
  }

  public static class PriceFixedItemImportRow {
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
}
