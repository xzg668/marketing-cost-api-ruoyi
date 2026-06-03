package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class OaFormDetailItemDto {
  private Long id;
  private Integer seq;
  private String productName;
  private String customerDrawing;
  private String customerCode;
  private String materialNo;
  private String sunlModel;
  private String spec;
  private String packageType;
  private String packageMethod;
  private String packageComponentCode;
  private BigDecimal shippingFee;
  private BigDecimal supportQty;
  private BigDecimal totalWithShip;
  private BigDecimal totalNoShip;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private Integer validMonth;
  private BigDecimal sus304WeightG;
  private BigDecimal sus316WeightG;
  private BigDecimal copperWeightG;
  private LocalDate validDate;
  private String calcStatus;
  private LocalDateTime calcAt;
  private BigDecimal unitCost;
  private BigDecimal costAmount;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public String getCustomerCode() {
    return customerCode;
  }

  public void setCustomerCode(String customerCode) {
    this.customerCode = customerCode;
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

  public String getPackageType() {
    return packageType;
  }

  public void setPackageType(String packageType) {
    this.packageType = packageType;
  }

  public String getPackageMethod() {
    return packageMethod;
  }

  public void setPackageMethod(String packageMethod) {
    this.packageMethod = packageMethod;
  }

  public String getPackageComponentCode() {
    return packageComponentCode;
  }

  public void setPackageComponentCode(String packageComponentCode) {
    this.packageComponentCode = packageComponentCode;
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

  public Integer getValidMonth() {
    return validMonth;
  }

  public void setValidMonth(Integer validMonth) {
    this.validMonth = validMonth;
  }

  public BigDecimal getSus304WeightG() {
    return sus304WeightG;
  }

  public void setSus304WeightG(BigDecimal sus304WeightG) {
    this.sus304WeightG = sus304WeightG;
  }

  public BigDecimal getSus316WeightG() {
    return sus316WeightG;
  }

  public void setSus316WeightG(BigDecimal sus316WeightG) {
    this.sus316WeightG = sus316WeightG;
  }

  public BigDecimal getCopperWeightG() {
    return copperWeightG;
  }

  public void setCopperWeightG(BigDecimal copperWeightG) {
    this.copperWeightG = copperWeightG;
  }

  public LocalDate getValidDate() {
    return validDate;
  }

  public void setValidDate(LocalDate validDate) {
    this.validDate = validDate;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public LocalDateTime getCalcAt() {
    return calcAt;
  }

  public void setCalcAt(LocalDateTime calcAt) {
    this.calcAt = calcAt;
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
