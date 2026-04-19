package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BomManageParentRow {
  private Long oaFormItemId;
  private String oaNo;
  private Long oaFormId;
  private String materialNo;
  private String productName;
  private String productSpec;
  private String productModel;
  private String customerName;
  private BigDecimal copperPriceTax;
  private BigDecimal zincPriceTax;
  private BigDecimal aluminumPriceTax;
  private BigDecimal steelPriceTax;
  private String bomCode;
  private String rootItemCode;
  private Long detailCount;
  private LocalDateTime updatedAt;

  public Long getOaFormItemId() {
    return oaFormItemId;
  }

  public void setOaFormItemId(Long oaFormItemId) {
    this.oaFormItemId = oaFormItemId;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public Long getOaFormId() {
    return oaFormId;
  }

  public void setOaFormId(Long oaFormId) {
    this.oaFormId = oaFormId;
  }

  public String getMaterialNo() {
    return materialNo;
  }

  public void setMaterialNo(String materialNo) {
    this.materialNo = materialNo;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductSpec() {
    return productSpec;
  }

  public void setProductSpec(String productSpec) {
    this.productSpec = productSpec;
  }

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public BigDecimal getCopperPriceTax() {
    return copperPriceTax;
  }

  public void setCopperPriceTax(BigDecimal copperPriceTax) {
    this.copperPriceTax = copperPriceTax;
  }

  public BigDecimal getZincPriceTax() {
    return zincPriceTax;
  }

  public void setZincPriceTax(BigDecimal zincPriceTax) {
    this.zincPriceTax = zincPriceTax;
  }

  public BigDecimal getAluminumPriceTax() {
    return aluminumPriceTax;
  }

  public void setAluminumPriceTax(BigDecimal aluminumPriceTax) {
    this.aluminumPriceTax = aluminumPriceTax;
  }

  public BigDecimal getSteelPriceTax() {
    return steelPriceTax;
  }

  public void setSteelPriceTax(BigDecimal steelPriceTax) {
    this.steelPriceTax = steelPriceTax;
  }

  public String getBomCode() {
    return bomCode;
  }

  public void setBomCode(String bomCode) {
    this.bomCode = bomCode;
  }

  public String getRootItemCode() {
    return rootItemCode;
  }

  public void setRootItemCode(String rootItemCode) {
    this.rootItemCode = rootItemCode;
  }

  public Long getDetailCount() {
    return detailCount;
  }

  public void setDetailCount(Long detailCount) {
    this.detailCount = detailCount;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
