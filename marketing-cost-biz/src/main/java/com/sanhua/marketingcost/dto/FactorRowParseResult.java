package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class FactorRowParseResult {
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private BigDecimal price;
  private String unit;
  private BigDecimal originalPrice;

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public void setSourceSheetName(String sourceSheetName) {
    this.sourceSheetName = sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public void setSourceRowNumber(Integer sourceRowNumber) {
    this.sourceRowNumber = sourceRowNumber;
  }

  public String getFactorSeqNo() {
    return factorSeqNo;
  }

  public void setFactorSeqNo(String factorSeqNo) {
    this.factorSeqNo = factorSeqNo;
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

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public BigDecimal getOriginalPrice() {
    return originalPrice;
  }

  public void setOriginalPrice(BigDecimal originalPrice) {
    this.originalPrice = originalPrice;
  }
}
