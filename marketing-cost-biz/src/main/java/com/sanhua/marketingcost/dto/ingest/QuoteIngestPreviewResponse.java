package com.sanhua.marketingcost.dto.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class QuoteIngestPreviewResponse extends QuoteIngestResponse {
  private boolean valid;
  private boolean classificationPending;
  private AccountingContext accountingContext;
  private HeaderSummary headerSummary;
  private List<ItemSummary> items = new ArrayList<>();
  private List<FeeSummary> extraFees = new ArrayList<>();

  public boolean isValid() {
    return valid;
  }

  public void setValid(boolean valid) {
    this.valid = valid;
  }

  public boolean isClassificationPending() {
    return classificationPending;
  }

  public void setClassificationPending(boolean classificationPending) {
    this.classificationPending = classificationPending;
  }

  public AccountingContext getAccountingContext() {
    return accountingContext;
  }

  public void setAccountingContext(AccountingContext accountingContext) {
    this.accountingContext = accountingContext;
  }

  public HeaderSummary getHeaderSummary() {
    return headerSummary;
  }

  public void setHeaderSummary(HeaderSummary headerSummary) {
    this.headerSummary = headerSummary;
  }

  public List<ItemSummary> getItems() {
    return items;
  }

  public void setItems(List<ItemSummary> items) {
    this.items = items;
  }

  public List<FeeSummary> getExtraFees() {
    return extraFees;
  }

  public void setExtraFees(List<FeeSummary> extraFees) {
    this.extraFees = extraFees;
  }

  public static class AccountingContext {
    private String businessUnitType;
    private String accountingPeriodMonth;
    private String expenseProductCategory;
    private String sourceCompany;
    private String sourceBusinessDivision;
    private String customer;
    private String productAttr;
    private String quoteScenario;
    private String classificationStatus;
    private String ruleCode;
    private int confidence;

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

    public String getExpenseProductCategory() {
      return expenseProductCategory;
    }

    public void setExpenseProductCategory(String expenseProductCategory) {
      this.expenseProductCategory = expenseProductCategory;
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

    public String getCustomer() {
      return customer;
    }

    public void setCustomer(String customer) {
      this.customer = customer;
    }

    public String getProductAttr() {
      return productAttr;
    }

    public void setProductAttr(String productAttr) {
      this.productAttr = productAttr;
    }

    public String getQuoteScenario() {
      return quoteScenario;
    }

    public void setQuoteScenario(String quoteScenario) {
      this.quoteScenario = quoteScenario;
    }

    public String getClassificationStatus() {
      return classificationStatus;
    }

    public void setClassificationStatus(String classificationStatus) {
      this.classificationStatus = classificationStatus;
    }

    public String getRuleCode() {
      return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
      this.ruleCode = ruleCode;
    }

    public int getConfidence() {
      return confidence;
    }

    public void setConfidence(int confidence) {
      this.confidence = confidence;
    }
  }

  public static class HeaderSummary {
    private String sourceType;
    private String sourceSystem;
    private String externalFormNo;
    private String oaNo;
    private String processCode;
    private String processName;
    private String formType;
    private LocalDate applyDate;
    private String customer;
    private String applicantUnit;
    private String sourceCompany;
    private String sourceBusinessDivision;
    private String expenseProductCategory;
    private String applicantDept;
    private String applicantOffice;
    private String applicantName;
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
    private BigDecimal sus304Price;
    private BigDecimal sus316lPrice;
    private BigDecimal silverPrice;
    private BigDecimal goldPrice;
    private String remark;

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

    public String getOaNo() {
      return oaNo;
    }

    public void setOaNo(String oaNo) {
      this.oaNo = oaNo;
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

    public String getApplicantUnit() {
      return applicantUnit;
    }

    public void setApplicantUnit(String applicantUnit) {
      this.applicantUnit = applicantUnit;
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

    public String getExpenseProductCategory() {
      return expenseProductCategory;
    }

    public void setExpenseProductCategory(String expenseProductCategory) {
      this.expenseProductCategory = expenseProductCategory;
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

    public String getRemark() {
      return remark;
    }

    public void setRemark(String remark) {
      this.remark = remark;
    }
  }

  public static class ItemSummary {
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
    private BigDecimal supportQty;
    private BigDecimal annualVolume;
    private BigDecimal scrapRate;
    private BigDecimal unitLaborCost;
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
    private String classificationStatus;
    private String quoteScenario;
    private String businessUnitType;
    private LocalDate validDate;

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

    public String getClassificationStatus() {
      return classificationStatus;
    }

    public void setClassificationStatus(String classificationStatus) {
      this.classificationStatus = classificationStatus;
    }

    public String getQuoteScenario() {
      return quoteScenario;
    }

    public void setQuoteScenario(String quoteScenario) {
      this.quoteScenario = quoteScenario;
    }

    public String getBusinessUnitType() {
      return businessUnitType;
    }

    public void setBusinessUnitType(String businessUnitType) {
      this.businessUnitType = businessUnitType;
    }

    public LocalDate getValidDate() {
      return validDate;
    }

    public void setValidDate(LocalDate validDate) {
      this.validDate = validDate;
    }
  }

  public static class FeeSummary {
    private String scope;
    private Integer itemSeq;
    private String externalLineId;
    private String feeCode;
    private String feeName;
    private String feeCategory;
    private BigDecimal amount;
    private String unit;
    private String remark;
    private String sourceFieldName;
    private String sourceFieldPath;

    public String getScope() {
      return scope;
    }

    public void setScope(String scope) {
      this.scope = scope;
    }

    public Integer getItemSeq() {
      return itemSeq;
    }

    public void setItemSeq(Integer itemSeq) {
      this.itemSeq = itemSeq;
    }

    public String getExternalLineId() {
      return externalLineId;
    }

    public void setExternalLineId(String externalLineId) {
      this.externalLineId = externalLineId;
    }

    public String getFeeCode() {
      return feeCode;
    }

    public void setFeeCode(String feeCode) {
      this.feeCode = feeCode;
    }

    public String getFeeName() {
      return feeName;
    }

    public void setFeeName(String feeName) {
      this.feeName = feeName;
    }

    public String getFeeCategory() {
      return feeCategory;
    }

    public void setFeeCategory(String feeCategory) {
      this.feeCategory = feeCategory;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public void setAmount(BigDecimal amount) {
      this.amount = amount;
    }

    public String getUnit() {
      return unit;
    }

    public void setUnit(String unit) {
      this.unit = unit;
    }

    public String getRemark() {
      return remark;
    }

    public void setRemark(String remark) {
      this.remark = remark;
    }

    public String getSourceFieldName() {
      return sourceFieldName;
    }

    public void setSourceFieldName(String sourceFieldName) {
      this.sourceFieldName = sourceFieldName;
    }

    public String getSourceFieldPath() {
      return sourceFieldPath;
    }

    public void setSourceFieldPath(String sourceFieldPath) {
      this.sourceFieldPath = sourceFieldPath;
    }
  }
}
