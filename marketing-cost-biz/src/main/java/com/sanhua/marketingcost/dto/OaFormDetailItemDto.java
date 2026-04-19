package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class OaFormDetailItemDto {
  private Integer seq;
  private String productName;
  private String customerDrawing;
  private String materialNo;
  private String sunlModel;
  private String spec;
  private BigDecimal shippingFee;
  private BigDecimal supportQty;
  private BigDecimal totalWithShip;
  private BigDecimal totalNoShip;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private LocalDate validDate;
  private BigDecimal unitCost;
  private BigDecimal costAmount;

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getCustomerDrawing() {
    return customerDrawing;
  }

  public void setCustomerDrawing(String customerDrawing) {
    this.customerDrawing = customerDrawing;
  }

  public String getMaterialNo() {
    return materialNo;
  }

  public void setMaterialNo(String materialNo) {
    this.materialNo = materialNo;
  }

  public String getSunlModel() {
    return sunlModel;
  }

  public void setSunlModel(String sunlModel) {
    this.sunlModel = sunlModel;
  }

  public String getSpec() {
    return spec;
  }

  public void setSpec(String spec) {
    this.spec = spec;
  }

  public BigDecimal getShippingFee() {
    return shippingFee;
  }

  public void setShippingFee(BigDecimal shippingFee) {
    this.shippingFee = shippingFee;
  }

  public BigDecimal getSupportQty() {
    return supportQty;
  }

  public void setSupportQty(BigDecimal supportQty) {
    this.supportQty = supportQty;
  }

  public BigDecimal getTotalWithShip() {
    return totalWithShip;
  }

  public void setTotalWithShip(BigDecimal totalWithShip) {
    this.totalWithShip = totalWithShip;
  }

  public BigDecimal getTotalNoShip() {
    return totalNoShip;
  }

  public void setTotalNoShip(BigDecimal totalNoShip) {
    this.totalNoShip = totalNoShip;
  }

  public BigDecimal getMaterialCost() {
    return materialCost;
  }

  public void setMaterialCost(BigDecimal materialCost) {
    this.materialCost = materialCost;
  }

  public BigDecimal getLaborCost() {
    return laborCost;
  }

  public void setLaborCost(BigDecimal laborCost) {
    this.laborCost = laborCost;
  }

  public BigDecimal getManufacturingCost() {
    return manufacturingCost;
  }

  public void setManufacturingCost(BigDecimal manufacturingCost) {
    this.manufacturingCost = manufacturingCost;
  }

  public BigDecimal getManagementCost() {
    return managementCost;
  }

  public void setManagementCost(BigDecimal managementCost) {
    this.managementCost = managementCost;
  }

  public LocalDate getValidDate() {
    return validDate;
  }

  public void setValidDate(LocalDate validDate) {
    this.validDate = validDate;
  }

  public BigDecimal getUnitCost() {
    return unitCost;
  }

  public void setUnitCost(BigDecimal unitCost) {
    this.unitCost = unitCost;
  }

  public BigDecimal getCostAmount() {
    return costAmount;
  }

  public void setCostAmount(BigDecimal costAmount) {
    this.costAmount = costAmount;
  }
}
