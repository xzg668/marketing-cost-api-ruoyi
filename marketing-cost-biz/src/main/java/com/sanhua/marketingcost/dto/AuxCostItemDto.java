package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class AuxCostItemDto {
  private String auxSubjectCode;
  private String auxSubjectName;
  private BigDecimal unitPrice;
  private BigDecimal floatRate;

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
}
