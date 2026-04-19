package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class BomManualItemRequest {
  private String bomCode;
  private String itemCode;
  private String itemName;
  private String itemSpec;
  private String itemModel;
  private Integer bomLevel;
  private String parentCode;
  private String shapeAttr;
  private BigDecimal bomQty;
  private String material;
  private String source;

  public String getBomCode() {
    return bomCode;
  }

  public void setBomCode(String bomCode) {
    this.bomCode = bomCode;
  }

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
  }

  public String getItemName() {
    return itemName;
  }

  public void setItemName(String itemName) {
    this.itemName = itemName;
  }

  public String getItemSpec() {
    return itemSpec;
  }

  public void setItemSpec(String itemSpec) {
    this.itemSpec = itemSpec;
  }

  public String getItemModel() {
    return itemModel;
  }

  public void setItemModel(String itemModel) {
    this.itemModel = itemModel;
  }

  public Integer getBomLevel() {
    return bomLevel;
  }

  public void setBomLevel(Integer bomLevel) {
    this.bomLevel = bomLevel;
  }

  public String getParentCode() {
    return parentCode;
  }

  public void setParentCode(String parentCode) {
    this.parentCode = parentCode;
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

  public String getMaterial() {
    return material;
  }

  public void setMaterial(String material) {
    this.material = material;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
