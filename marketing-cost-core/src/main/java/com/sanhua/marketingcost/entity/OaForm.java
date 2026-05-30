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

@TableName("oa_form")
public class OaForm {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private String expenseProductCategory;
  private String oaNo;
  private String formType;
  private LocalDate applyDate;
  private String customer;
  private String sourceCompany;
  private String sourceBusinessDivision;
  private String applicantDept;
  private String applicantOffice;
  private String applicantName;
  private String applicantUnit;
  private String urgency;
  private String productAttr;
  private String priceLinkMode;
  private String overseasSalesMode;
  private String tradeTerms;
  private BigDecimal exchangeRate;
  private BigDecimal copperPrice;
  private BigDecimal zincPrice;
  private BigDecimal aluminumPrice;
  private BigDecimal steelPrice;
  private BigDecimal silverPrice;
  private BigDecimal goldPrice;
  private BigDecimal sus304Price;
  private BigDecimal sus316lPrice;
  private BigDecimal otherMaterial;
  private BigDecimal baseShipping;
  private String calcStatus;
  private LocalDateTime calcAt;
  private String classificationStatus;
  private Long ingestLogId;
  private String saleLink;
  private String remark;

  /** V21 业务单元数据隔离：COMMERCIAL 商用 / HOUSEHOLD 家用 */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  /** 申请日期所属月份，格式 YYYY-MM，用于三项费用等按月匹配。 */
  private String accountingPeriodMonth;

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

  public String getAccountingPeriodMonth() {
    return accountingPeriodMonth;
  }

  public void setAccountingPeriodMonth(String accountingPeriodMonth) {
    this.accountingPeriodMonth = accountingPeriodMonth;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public void setSourceSystem(String sourceSystem) {
    this.sourceSystem = sourceSystem;
  }

  public String getExternalFormNo() {
    return externalFormNo;
  }

  public void setExternalFormNo(String externalFormNo) {
    this.externalFormNo = externalFormNo;
  }

  public String getProcessCode() {
    return processCode;
  }

  public void setProcessCode(String processCode) {
    this.processCode = processCode;
  }

  public String getProcessName() {
    return processName;
  }

  public void setProcessName(String processName) {
    this.processName = processName;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public void setQuoteScenario(String quoteScenario) {
    this.quoteScenario = quoteScenario;
  }

  public String getExpenseProductCategory() {
    return expenseProductCategory;
  }

  public void setExpenseProductCategory(String expenseProductCategory) {
    this.expenseProductCategory = expenseProductCategory;
  }

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

  public String getSourceCompany() {
    return sourceCompany;
  }

  public void setSourceCompany(String sourceCompany) {
    this.sourceCompany = sourceCompany;
  }

  public String getSourceBusinessDivision() {
    return sourceBusinessDivision;
  }

  public void setSourceBusinessDivision(String sourceBusinessDivision) {
    this.sourceBusinessDivision = sourceBusinessDivision;
  }

  public String getApplicantDept() {
    return applicantDept;
  }

  public void setApplicantDept(String applicantDept) {
    this.applicantDept = applicantDept;
  }

  public String getApplicantOffice() {
    return applicantOffice;
  }

  public void setApplicantOffice(String applicantOffice) {
    this.applicantOffice = applicantOffice;
  }

  public String getApplicantName() {
    return applicantName;
  }

  public void setApplicantName(String applicantName) {
    this.applicantName = applicantName;
  }

  public String getApplicantUnit() {
    return applicantUnit;
  }

  public void setApplicantUnit(String applicantUnit) {
    this.applicantUnit = applicantUnit;
  }

  public String getUrgency() {
    return urgency;
  }

  public void setUrgency(String urgency) {
    this.urgency = urgency;
  }

  public String getProductAttr() {
    return productAttr;
  }

  public void setProductAttr(String productAttr) {
    this.productAttr = productAttr;
  }

  public String getPriceLinkMode() {
    return priceLinkMode;
  }

  public void setPriceLinkMode(String priceLinkMode) {
    this.priceLinkMode = priceLinkMode;
  }

  public String getOverseasSalesMode() {
    return overseasSalesMode;
  }

  public void setOverseasSalesMode(String overseasSalesMode) {
    this.overseasSalesMode = overseasSalesMode;
  }

  public String getTradeTerms() {
    return tradeTerms;
  }

  public void setTradeTerms(String tradeTerms) {
    this.tradeTerms = tradeTerms;
  }

  public BigDecimal getExchangeRate() {
    return exchangeRate;
  }

  public void setExchangeRate(BigDecimal exchangeRate) {
    this.exchangeRate = exchangeRate;
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

  public BigDecimal getSilverPrice() {
    return silverPrice;
  }

  public void setSilverPrice(BigDecimal silverPrice) {
    this.silverPrice = silverPrice;
  }

  public BigDecimal getGoldPrice() {
    return goldPrice;
  }

  public void setGoldPrice(BigDecimal goldPrice) {
    this.goldPrice = goldPrice;
  }

  public BigDecimal getSus304Price() {
    return sus304Price;
  }

  public void setSus304Price(BigDecimal sus304Price) {
    this.sus304Price = sus304Price;
  }

  public BigDecimal getSus316lPrice() {
    return sus316lPrice;
  }

  public void setSus316lPrice(BigDecimal sus316lPrice) {
    this.sus316lPrice = sus316lPrice;
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

  public LocalDateTime getCalcAt() {
    return calcAt;
  }

  public void setCalcAt(LocalDateTime calcAt) {
    this.calcAt = calcAt;
  }

  public String getClassificationStatus() {
    return classificationStatus;
  }

  public void setClassificationStatus(String classificationStatus) {
    this.classificationStatus = classificationStatus;
  }

  public Long getIngestLogId() {
    return ingestLogId;
  }

  public void setIngestLogId(Long ingestLogId) {
    this.ingestLogId = ingestLogId;
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
