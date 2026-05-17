package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class ResolvedFactorRef {
  private String workbookName;
  private String sheetName;
  private String columnName;
  private Integer rowNumber;
  private String rawRef;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String factorSeqNo;
  private String shortName;
  private String priceSource;
  private BigDecimal price;
  private String warning;
  private String errorMessage;

  public String getWorkbookName() {
    return workbookName;
  }

  public void setWorkbookName(String workbookName) {
    this.workbookName = workbookName;
  }

  public String getSheetName() {
    return sheetName;
  }

  public void setSheetName(String sheetName) {
    this.sheetName = sheetName;
  }

  public String getColumnName() {
    return columnName;
  }

  public void setColumnName(String columnName) {
    this.columnName = columnName;
  }

  public Integer getRowNumber() {
    return rowNumber;
  }

  public void setRowNumber(Integer rowNumber) {
    this.rowNumber = rowNumber;
  }

  public String getRawRef() {
    return rawRef;
  }

  public void setRawRef(String rawRef) {
    this.rawRef = rawRef;
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public void setFactorIdentityId(Long factorIdentityId) {
    this.factorIdentityId = factorIdentityId;
  }

  public Long getFactorMonthlyPriceId() {
    return factorMonthlyPriceId;
  }

  public void setFactorMonthlyPriceId(Long factorMonthlyPriceId) {
    this.factorMonthlyPriceId = factorMonthlyPriceId;
  }

  public String getFactorSeqNo() {
    return factorSeqNo;
  }

  public void setFactorSeqNo(String factorSeqNo) {
    this.factorSeqNo = factorSeqNo;
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

  public String getWarning() {
    return warning;
  }

  public void setWarning(String warning) {
    this.warning = warning;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public boolean isResolved() {
    return factorIdentityId != null && factorMonthlyPriceId != null && errorMessage == null;
  }
}
