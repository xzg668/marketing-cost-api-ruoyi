package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class QualityLossRateImportRequest {
  private Integer rateYear;
  private String businessUnitType;
  private String sourceBatchNo;
  private List<QualityLossRateRow> rows;

  public Integer getRateYear() {
    return rateYear;
  }

  public void setRateYear(Integer rateYear) {
    this.rateYear = rateYear;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getSourceBatchNo() {
    return sourceBatchNo;
  }

  public void setSourceBatchNo(String sourceBatchNo) {
    this.sourceBatchNo = sourceBatchNo;
  }

  public List<QualityLossRateRow> getRows() {
    return rows;
  }

  public void setRows(List<QualityLossRateRow> rows) {
    this.rows = rows;
  }

  public static class QualityLossRateRow {
    private Integer rowNo;
    private String company;
    private String businessUnit;
    private String businessDivision;
    private String productCategory;
    private String productSubcategory;
    private String productCode;
    private String productName;
    private String productModel;
    private String productSpec;
    private BigDecimal lossRate;
    private String customer;
    private String period;
    private Integer rateYear;
    private String sourceBasis;
    private String remark;

    public Integer getRowNo() {
      return rowNo;
    }

    public void setRowNo(Integer rowNo) {
      this.rowNo = rowNo;
    }

    public String getCompany() {
      return company;
    }

    public void setCompany(String company) {
      this.company = company;
    }

    public String getBusinessUnit() {
      return businessUnit;
    }

    public void setBusinessUnit(String businessUnit) {
      this.businessUnit = businessUnit;
    }

    public String getBusinessDivision() {
      return businessDivision;
    }

    public void setBusinessDivision(String businessDivision) {
      this.businessDivision = businessDivision;
    }

    public String getProductCategory() {
      return productCategory;
    }

    public void setProductCategory(String productCategory) {
      this.productCategory = productCategory;
    }

    public String getProductSubcategory() {
      return productSubcategory;
    }

    public void setProductSubcategory(String productSubcategory) {
      this.productSubcategory = productSubcategory;
    }

    public String getProductCode() {
      return productCode;
    }

    public void setProductCode(String productCode) {
      this.productCode = productCode;
    }

    public String getProductName() {
      return productName;
    }

    public void setProductName(String productName) {
      this.productName = productName;
    }

    public String getProductModel() {
      return productModel;
    }

    public void setProductModel(String productModel) {
      this.productModel = productModel;
    }

    public String getProductSpec() {
      return productSpec;
    }

    public void setProductSpec(String productSpec) {
      this.productSpec = productSpec;
    }

    public BigDecimal getLossRate() {
      return lossRate;
    }

    public void setLossRate(BigDecimal lossRate) {
      this.lossRate = lossRate;
    }

    public String getCustomer() {
      return customer;
    }

    public void setCustomer(String customer) {
      this.customer = customer;
    }

    public String getPeriod() {
      return period;
    }

    public void setPeriod(String period) {
      this.period = period;
    }

    public Integer getRateYear() {
      return rateYear;
    }

    public void setRateYear(Integer rateYear) {
      this.rateYear = rateYear;
    }

    public String getSourceBasis() {
      return sourceBasis;
    }

    public void setSourceBasis(String sourceBasis) {
      this.sourceBasis = sourceBasis;
    }

    public String getRemark() {
      return remark;
    }

    public void setRemark(String remark) {
      this.remark = remark;
    }
  }
}
