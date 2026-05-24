package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteIngestItemRequest {
  private String externalLineId;
  private Integer seq;
  private String productName;
  private String customerDrawing;
  private String customerCode;
  private String materialNo;
  private String sunlModel;
  private String spec;
  private String productAttr;
  private String businessType;
  private Boolean firstQuoteFlag;
  private Boolean certificationRequired;
  private String originCountry;
  private String technicianName;
  private String packageType;
  private String packageMethod;
  private String packageComponentCode;
  private String packageQty;
  private String shippingFee;
  private String supportQty;
  private String annualVolume;
  private String projectNo;
  private String productStatus;
  private String scrapRate;
  private String unitLaborCost;
  private String totalWithShip;
  private String totalNoShip;
  private String materialCost;
  private String laborCost;
  private String manufacturingCost;
  private String managementCost;
  private String validMonth;
  private String validDate;
  private String sus304WeightG;
  private String sus316WeightG;
  private String copperWeightG;
  private List<QuoteExtraFieldRequest> extraFields = new ArrayList<>();
  private List<QuoteExtraFeeRequest> extraFees = new ArrayList<>();

  public String getExternalLineId() {
    return externalLineId;
  }

  public void setExternalLineId(String externalLineId) {
    this.externalLineId = externalLineId;
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

  public String getProductAttr() {
    return productAttr;
  }

  public void setProductAttr(String productAttr) {
    this.productAttr = productAttr;
  }

  public String getBusinessType() {
    return businessType;
  }

  public void setBusinessType(String businessType) {
    this.businessType = businessType;
  }

  public Boolean getFirstQuoteFlag() {
    return firstQuoteFlag;
  }

  public void setFirstQuoteFlag(Boolean firstQuoteFlag) {
    this.firstQuoteFlag = firstQuoteFlag;
  }

  public Boolean getCertificationRequired() {
    return certificationRequired;
  }

  public void setCertificationRequired(Boolean certificationRequired) {
    this.certificationRequired = certificationRequired;
  }

  public String getOriginCountry() {
    return originCountry;
  }

  public void setOriginCountry(String originCountry) {
    this.originCountry = originCountry;
  }

  public String getTechnicianName() {
    return technicianName;
  }

  public void setTechnicianName(String technicianName) {
    this.technicianName = technicianName;
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

  public String getPackageQty() {
    return packageQty;
  }

  public void setPackageQty(String packageQty) {
    this.packageQty = packageQty;
  }

  public String getShippingFee() {
    return shippingFee;
  }

  public void setShippingFee(String shippingFee) {
    this.shippingFee = shippingFee;
  }

  public String getSupportQty() {
    return supportQty;
  }

  public void setSupportQty(String supportQty) {
    this.supportQty = supportQty;
  }

  public String getAnnualVolume() {
    return annualVolume;
  }

  public void setAnnualVolume(String annualVolume) {
    this.annualVolume = annualVolume;
  }

  public String getProjectNo() {
    return projectNo;
  }

  public void setProjectNo(String projectNo) {
    this.projectNo = projectNo;
  }

  public String getProductStatus() {
    return productStatus;
  }

  public void setProductStatus(String productStatus) {
    this.productStatus = productStatus;
  }

  public String getScrapRate() {
    return scrapRate;
  }

  public void setScrapRate(String scrapRate) {
    this.scrapRate = scrapRate;
  }

  public String getUnitLaborCost() {
    return unitLaborCost;
  }

  public void setUnitLaborCost(String unitLaborCost) {
    this.unitLaborCost = unitLaborCost;
  }

  public String getTotalWithShip() {
    return totalWithShip;
  }

  public void setTotalWithShip(String totalWithShip) {
    this.totalWithShip = totalWithShip;
  }

  public String getTotalNoShip() {
    return totalNoShip;
  }

  public void setTotalNoShip(String totalNoShip) {
    this.totalNoShip = totalNoShip;
  }

  public String getMaterialCost() {
    return materialCost;
  }

  public void setMaterialCost(String materialCost) {
    this.materialCost = materialCost;
  }

  public String getLaborCost() {
    return laborCost;
  }

  public void setLaborCost(String laborCost) {
    this.laborCost = laborCost;
  }

  public String getManufacturingCost() {
    return manufacturingCost;
  }

  public void setManufacturingCost(String manufacturingCost) {
    this.manufacturingCost = manufacturingCost;
  }

  public String getManagementCost() {
    return managementCost;
  }

  public void setManagementCost(String managementCost) {
    this.managementCost = managementCost;
  }

  public String getValidMonth() {
    return validMonth;
  }

  public void setValidMonth(String validMonth) {
    this.validMonth = validMonth;
  }

  public String getValidDate() {
    return validDate;
  }

  public void setValidDate(String validDate) {
    this.validDate = validDate;
  }

  public String getSus304WeightG() {
    return sus304WeightG;
  }

  public void setSus304WeightG(String sus304WeightG) {
    this.sus304WeightG = sus304WeightG;
  }

  public String getSus316WeightG() {
    return sus316WeightG;
  }

  public void setSus316WeightG(String sus316WeightG) {
    this.sus316WeightG = sus316WeightG;
  }

  public String getCopperWeightG() {
    return copperWeightG;
  }

  public void setCopperWeightG(String copperWeightG) {
    this.copperWeightG = copperWeightG;
  }

  public List<QuoteExtraFieldRequest> getExtraFields() {
    return extraFields;
  }

  public void setExtraFields(List<QuoteExtraFieldRequest> extraFields) {
    this.extraFields = extraFields;
  }

  public List<QuoteExtraFeeRequest> getExtraFees() {
    return extraFees;
  }

  public void setExtraFees(List<QuoteExtraFeeRequest> extraFees) {
    this.extraFees = extraFees;
  }
}
