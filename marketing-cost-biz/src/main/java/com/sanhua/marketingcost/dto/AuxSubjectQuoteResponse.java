package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class AuxSubjectQuoteResponse {
  private BigDecimal unitPrice;

  public AuxSubjectQuoteResponse(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }
}
