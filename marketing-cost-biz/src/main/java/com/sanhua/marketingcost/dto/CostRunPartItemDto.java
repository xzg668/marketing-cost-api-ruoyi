package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class CostRunPartItemDto {
  private String oaNo;
  private String partName;
  private String partCode;
  private String productCode;
  private String partDrawingNo;
  private BigDecimal partQty;
  private String shapeAttr;
  private String material;
  private String priceType;
  private String priceSource;
  private String remark;
  private BigDecimal unitPrice;
  private BigDecimal amount;

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getPartName() {
    return partName;
  }

  public void setPartName(String partName) {
    this.partName = partName;
  }

  public String getPartCode() {
    return partCode;
  }

  public void setPartCode(String partCode) {
    this.partCode = partCode;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getPartDrawingNo() {
    return partDrawingNo;
  }

  public void setPartDrawingNo(String partDrawingNo) {
    this.partDrawingNo = partDrawingNo;
  }

  public BigDecimal getPartQty() {
    return partQty;
  }

  public void setPartQty(BigDecimal partQty) {
    this.partQty = partQty;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public String getMaterial() {
    return material;
  }

  public void setMaterial(String material) {
    this.material = material;
  }

  public String getPriceType() {
    return priceType;
  }

  public void setPriceType(String priceType) {
    this.priceType = priceType;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }
}
