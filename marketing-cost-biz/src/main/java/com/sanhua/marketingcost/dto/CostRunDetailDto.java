package com.sanhua.marketingcost.dto;

import java.util.List;

public class CostRunDetailDto {
  private List<CostRunPartItemDto> partItems;
  private List<CostRunCostItemDto> costItems;
  private String productAttr;
  private String productName;
  private String productModel;
  private java.math.BigDecimal copperPrice;
  private java.math.BigDecimal zincPrice;
  /**
   * T12：不含税总成本快捷取值（= costItems 中 cost_code='TOTAL' 行的 amount）。
   * 提供顶层字段是为了让前端不用扫数组。null 表示没算出（缺率导致 totalAmount=null）。
   */
  private java.math.BigDecimal total;

  public List<CostRunPartItemDto> getPartItems() {
    return partItems;
  }

  public void setPartItems(List<CostRunPartItemDto> partItems) {
    this.partItems = partItems;
  }

  public List<CostRunCostItemDto> getCostItems() {
    return costItems;
  }

  public void setCostItems(List<CostRunCostItemDto> costItems) {
    this.costItems = costItems;
  }

  public String getProductAttr() {
    return productAttr;
  }

  public void setProductAttr(String productAttr) {
    this.productAttr = productAttr;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public java.math.BigDecimal getCopperPrice() {
    return copperPrice;
  }

  public void setCopperPrice(java.math.BigDecimal copperPrice) {
    this.copperPrice = copperPrice;
  }

  public java.math.BigDecimal getZincPrice() {
    return zincPrice;
  }

  public void setZincPrice(java.math.BigDecimal zincPrice) {
    this.zincPrice = zincPrice;
  }

  public java.math.BigDecimal getTotal() {
    return total;
  }

  public void setTotal(java.math.BigDecimal total) {
    this.total = total;
  }
}
