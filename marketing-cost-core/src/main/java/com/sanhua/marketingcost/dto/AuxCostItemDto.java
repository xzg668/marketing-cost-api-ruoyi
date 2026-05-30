package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class AuxCostItemDto {
  private String materialCode;
  private String refMaterialCode;
  private String auxSubjectCode;
  private String auxSubjectName;
  private BigDecimal unitPrice;
  private BigDecimal floatRate;
  private String amountCalcMode;
  private Integer displayOrder;
  private String source;

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getRefMaterialCode() {
    return refMaterialCode;
  }

  public void setRefMaterialCode(String refMaterialCode) {
    this.refMaterialCode = refMaterialCode;
  }

  public String getAuxSubjectCode() {
    return auxSubjectCode;
  }

  public void setAuxSubjectCode(String auxSubjectCode) {
    this.auxSubjectCode = auxSubjectCode;
  }

  public String getAuxSubjectName() {
    return auxSubjectName;
  }

  public void setAuxSubjectName(String auxSubjectName) {
    this.auxSubjectName = auxSubjectName;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getFloatRate() {
    return floatRate;
  }

  public void setFloatRate(BigDecimal floatRate) {
    this.floatRate = floatRate;
  }

  public String getAmountCalcMode() {
    return amountCalcMode;
  }

  public void setAmountCalcMode(String amountCalcMode) {
    this.amountCalcMode = amountCalcMode;
  }

  public Integer getDisplayOrder() {
    return displayOrder;
  }

  public void setDisplayOrder(Integer displayOrder) {
    this.displayOrder = displayOrder;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
