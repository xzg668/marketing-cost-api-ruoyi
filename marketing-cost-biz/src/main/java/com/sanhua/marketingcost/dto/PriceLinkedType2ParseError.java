package com.sanhua.marketingcost.dto;

/** 类型 2 原始解析错误，始终保留可定位的 Sheet、行号和单元格。 */
public final class PriceLinkedType2ParseError {

  private final String sheetName;
  private final Integer rowNumber;
  private final String cellRef;
  private final String message;

  public PriceLinkedType2ParseError(
      String sheetName, Integer rowNumber, String cellRef, String message) {
    this.sheetName = sheetName;
    this.rowNumber = rowNumber;
    this.cellRef = cellRef;
    this.message = message;
  }

  public String getSheetName() {
    return sheetName;
  }

  public Integer getRowNumber() {
    return rowNumber;
  }

  public String getCellRef() {
    return cellRef;
  }

  public String getMessage() {
    return message;
  }
}
