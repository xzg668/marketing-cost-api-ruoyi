package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class OaFormDetailKeyDto {
  private String oaNo;
  private String formType;
  private LocalDate applyDate;
  private String customer;
  private BigDecimal copperPrice;
  private BigDecimal zincPrice;
  private BigDecimal aluminumPrice;
  private BigDecimal steelPrice;
  private BigDecimal otherMaterial;
  private BigDecimal baseShipping;
  private String calcStatus;
  private String saleLink;
  private String remark;

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getFormType() {
    return formType;
  }

  public void setFormType(String formType) {
    this.formType = formType;
  }

  public LocalDate getApplyDate() {
    return applyDate;
  }

  public void setApplyDate(LocalDate applyDate) {
    this.applyDate = applyDate;
  }

  public String getCustomer() {
    return customer;
  }

  public void setCustomer(String customer) {
    this.customer = customer;
  }

  public BigDecimal getCopperPrice() {
    return copperPrice;
  }

  public void setCopperPrice(BigDecimal copperPrice) {
    this.copperPrice = copperPrice;
  }

  public BigDecimal getZincPrice() {
    return zincPrice;
  }

  public void setZincPrice(BigDecimal zincPrice) {
    this.zincPrice = zincPrice;
  }

  public BigDecimal getAluminumPrice() {
    return aluminumPrice;
  }

  public void setAluminumPrice(BigDecimal aluminumPrice) {
    this.aluminumPrice = aluminumPrice;
  }

  public BigDecimal getSteelPrice() {
    return steelPrice;
  }

  public void setSteelPrice(BigDecimal steelPrice) {
    this.steelPrice = steelPrice;
  }

  public BigDecimal getOtherMaterial() {
    return otherMaterial;
  }

  public void setOtherMaterial(BigDecimal otherMaterial) {
    this.otherMaterial = otherMaterial;
  }

  public BigDecimal getBaseShipping() {
    return baseShipping;
  }

  public void setBaseShipping(BigDecimal baseShipping) {
    this.baseShipping = baseShipping;
  }

  public String getCalcStatus() {
    return calcStatus;
  }

  public void setCalcStatus(String calcStatus) {
    this.calcStatus = calcStatus;
  }

  public String getSaleLink() {
    return saleLink;
  }

  public void setSaleLink(String saleLink) {
    this.saleLink = saleLink;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
