package com.sanhua.marketingcost.dto;

public class FormulaFactorRef {
  private String workbookName;
  private String sheetName;
  private String columnName;
  private Integer rowNumber;
  private String rawRef;
  private Integer orderIndex;

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

  public Integer getOrderIndex() {
    return orderIndex;
  }

  public void setOrderIndex(Integer orderIndex) {
    this.orderIndex = orderIndex;
  }
}
