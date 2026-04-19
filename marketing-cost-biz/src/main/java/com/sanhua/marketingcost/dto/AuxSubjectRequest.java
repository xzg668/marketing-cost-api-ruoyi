package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class AuxSubjectRequest {
  private String materialCode;
  private String productName;
  private String spec;
  private String model;
  private String refMaterialCode;
  private String auxSubjectCode;
  private String auxSubjectName;
  private BigDecimal unitPrice;
  private String period;
  private String source;

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getSpec() {
    return spec;
  }

  public void setSpec(String spec) {
    this.spec = spec;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getRefMaterialCode() {
    return refMaterialCode;
  }

  public void setRefMaterialCode(String refMaterialCode) {
    this.refMaterialCode = refMaterialCode;
  }

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

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
