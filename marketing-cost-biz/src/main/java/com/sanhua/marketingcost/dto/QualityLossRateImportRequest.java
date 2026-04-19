package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class QualityLossRateImportRequest {
  private List<QualityLossRateRow> rows;

  public List<QualityLossRateRow> getRows() {
    return rows;
  }

  public void setRows(List<QualityLossRateRow> rows) {
    this.rows = rows;
  }

  public static class QualityLossRateRow {
    private String company;
    private String businessUnit;
    private String productCategory;
    private String productSubcategory;
    private BigDecimal lossRate;
    private String customer;
    private String period;
    private String sourceBasis;

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

    public String getSourceBasis() {
      return sourceBasis;
    }

    public void setSourceBasis(String sourceBasis) {
      this.sourceBasis = sourceBasis;
    }
  }
}
