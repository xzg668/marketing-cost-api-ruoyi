package com.sanhua.marketingcost.dto;

import java.util.List;

public class FinanceBasePriceImportRequest {
  private String priceMonth;
  private List<FinanceBasePriceImportRow> rows;

  public String getPriceMonth() {
    return priceMonth;
  }

  public void setPriceMonth(String priceMonth) {
    this.priceMonth = priceMonth;
  }

  public List<FinanceBasePriceImportRow> getRows() {
    return rows;
  }

  public void setRows(List<FinanceBasePriceImportRow> rows) {
    this.rows = rows;
  }

  public static class FinanceBasePriceImportRow {
    private Integer seq;
    private String factorName;
    private String shortName;
    private String factorCode;
    private String priceSource;
    private java.math.BigDecimal price;
    private String unit;
    private String linkType;

    public Integer getSeq() {
      return seq;
    }

    public void setSeq(Integer seq) {
      this.seq = seq;
    }

    public String getFactorName() {
      return factorName;
    }

    public void setFactorName(String factorName) {
      this.factorName = factorName;
    }

    public String getShortName() {
      return shortName;
    }

    public void setShortName(String shortName) {
      this.shortName = shortName;
    }

    public String getFactorCode() {
      return factorCode;
    }

    public void setFactorCode(String factorCode) {
      this.factorCode = factorCode;
    }

    public String getPriceSource() {
      return priceSource;
    }

    public void setPriceSource(String priceSource) {
      this.priceSource = priceSource;
    }

    public java.math.BigDecimal getPrice() {
      return price;
    }

    public void setPrice(java.math.BigDecimal price) {
      this.price = price;
    }

    public String getUnit() {
      return unit;
    }

    public void setUnit(String unit) {
      this.unit = unit;
    }

    public String getLinkType() {
      return linkType;
    }

    public void setLinkType(String linkType) {
      this.linkType = linkType;
    }
  }
}
