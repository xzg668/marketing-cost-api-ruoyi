package com.sanhua.marketingcost.dto;

public class MaterialPriceTypeRequest {
  private Integer rowNo;
  private String billNo;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String materialShape;
  private String priceType;
  private String period;
  private String source;

  public Integer getRowNo() {
    return rowNo;
  }

  public void setRowNo(Integer rowNo) {
    this.rowNo = rowNo;
  }

  public String getBillNo() {
    return billNo;
  }

  public void setBillNo(String billNo) {
    this.billNo = billNo;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getMaterialSpec() {
    return materialSpec;
  }

  public void setMaterialSpec(String materialSpec) {
    this.materialSpec = materialSpec;
  }

  public String getMaterialModel() {
    return materialModel;
  }

  public void setMaterialModel(String materialModel) {
    this.materialModel = materialModel;
  }

  public String getMaterialShape() {
    return materialShape;
  }

  public void setMaterialShape(String materialShape) {
    this.materialShape = materialShape;
  }

  public String getPriceType() {
    return priceType;
  }

  public void setPriceType(String priceType) {
    this.priceType = priceType;
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
