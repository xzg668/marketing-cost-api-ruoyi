package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class PriceLinkedCalcRow {
  private String oaNo;
  private String itemCode;
  private String shapeAttr;
  private BigDecimal bomQty;
  private BigDecimal partUnitPrice;
  private BigDecimal partAmount;

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
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
}
