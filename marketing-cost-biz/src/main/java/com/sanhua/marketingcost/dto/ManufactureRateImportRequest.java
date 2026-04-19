package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class ManufactureRateImportRequest {
  private List<ManufactureRateRow> rows;

  public List<ManufactureRateRow> getRows() {
    return rows;
  }

  public void setRows(List<ManufactureRateRow> rows) {
    this.rows = rows;
  }

  public static class ManufactureRateRow {
    private String company;
    private String businessUnit;
    private String productCategory;
    private String productSubcategory;
    private String productSpec;
    private String productModel;
    private BigDecimal feeRate;
    private String period;

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

    public BigDecimal getFeeRate() {
      return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
      this.feeRate = feeRate;
    }

    public String getPeriod() {
      return period;
    }

    public void setPeriod(String period) {
      this.period = period;
    }
  }
}
