package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("oa_form_item")
public class OaFormItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long oaFormId;
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
  private Integer firstQuoteFlag;
  private Integer certificationRequired;
  private String originCountry;
  private String technicianName;
  private String packageType;
  private String packageMethod;
  private String packageComponentCode;
  private BigDecimal packageQty;
  private BigDecimal shippingFee;
  private BigDecimal supportQty;
  private BigDecimal annualVolume;
  private String projectNo;
  private String productStatus;
  private BigDecimal scrapRate;
  private BigDecimal unitLaborCost;
  private String classificationStatus;
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

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  @TableLogic
  private Integer deleted;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getOaFormId() {
    return oaFormId;
  }

  public void setOaFormId(Long oaFormId) {
    this.oaFormId = oaFormId;
  }

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

  public Integer getFirstQuoteFlag() {
    return firstQuoteFlag;
  }

  public void setFirstQuoteFlag(Integer firstQuoteFlag) {
    this.firstQuoteFlag = firstQuoteFlag;
  }

  public Integer getCertificationRequired() {
    return certificationRequired;
  }

  public void setCertificationRequired(Integer certificationRequired) {
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

  public BigDecimal getPackageQty() {
    return packageQty;
  }

  public void setPackageQty(BigDecimal packageQty) {
    this.packageQty = packageQty;
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

  public BigDecimal getAnnualVolume() {
    return annualVolume;
  }

  public void setAnnualVolume(BigDecimal annualVolume) {
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

  public BigDecimal getScrapRate() {
    return scrapRate;
  }

  public void setScrapRate(BigDecimal scrapRate) {
    this.scrapRate = scrapRate;
  }

  public BigDecimal getUnitLaborCost() {
    return unitLaborCost;
  }

  public void setUnitLaborCost(BigDecimal unitLaborCost) {
    this.unitLaborCost = unitLaborCost;
  }

  public String getClassificationStatus() {
    return classificationStatus;
  }

  public void setClassificationStatus(String classificationStatus) {
    this.classificationStatus = classificationStatus;
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

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getDeleted() {
    return deleted;
  }

  public void setDeleted(Integer deleted) {
    this.deleted = deleted;
  }
}
