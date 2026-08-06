package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 类型 2 业务计算 Sheet 上方影响因素行。 */
public final class PriceLinkedType2FactorRow {

  private final String sourceSheetName;
  private final Integer sourceRowNumber;
  private final String factorSeqNo;
  private final String factorName;
  private final String shortName;
  private final String priceSource;
  private final BigDecimal price;
  private final String unit;
  private final String priceCellRef;

  public PriceLinkedType2FactorRow(
      String sourceSheetName,
      Integer sourceRowNumber,
      String factorSeqNo,
      String factorName,
      String shortName,
      String priceSource,
      BigDecimal price,
      String unit,
      String priceCellRef) {
    this.sourceSheetName = sourceSheetName;
    this.sourceRowNumber = sourceRowNumber;
    this.factorSeqNo = factorSeqNo;
    this.factorName = factorName;
    this.shortName = shortName;
    this.priceSource = priceSource;
    this.price = price;
    this.unit = unit;
    this.priceCellRef = priceCellRef;
  }

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public String getFactorSeqNo() {
    return factorSeqNo;
  }

  public String getFactorName() {
    return factorName;
  }

  public String getShortName() {
    return shortName;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getUnit() {
    return unit;
  }

  public String getPriceCellRef() {
    return priceCellRef;
  }
}
