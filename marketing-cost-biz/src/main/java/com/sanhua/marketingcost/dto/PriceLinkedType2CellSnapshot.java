package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 类型 2 Excel 单元格只读快照。 */
public final class PriceLinkedType2CellSnapshot {

  private final String sheetName;
  private final String cellRef;
  private final String header;
  private final String displayValue;
  private final BigDecimal numericValue;
  private final String formula;
  private final String unit;
  private final String sourceCellType;
  private final boolean blankCell;

  public PriceLinkedType2CellSnapshot(
      String sheetName,
      String cellRef,
      String header,
      String displayValue,
      BigDecimal numericValue,
      String formula,
      String unit) {
    this(
        sheetName,
        cellRef,
        header,
        displayValue,
        numericValue,
        formula,
        unit,
        null,
        false);
  }

  public PriceLinkedType2CellSnapshot(
      String sheetName,
      String cellRef,
      String header,
      String displayValue,
      BigDecimal numericValue,
      String formula,
      String unit,
      String sourceCellType,
      boolean blankCell) {
    this.sheetName = sheetName;
    this.cellRef = cellRef;
    this.header = header;
    this.displayValue = displayValue;
    this.numericValue = numericValue;
    this.formula = formula;
    this.unit = unit;
    this.sourceCellType = sourceCellType;
    this.blankCell = blankCell;
  }

  public String getSheetName() {
    return sheetName;
  }

  public String getCellRef() {
    return cellRef;
  }

  public String getHeader() {
    return header;
  }

  public String getDisplayValue() {
    return displayValue;
  }

  public BigDecimal getNumericValue() {
    return numericValue;
  }

  public String getFormula() {
    return formula;
  }

  public String getUnit() {
    return unit;
  }

  public String getSourceCellType() {
    return sourceCellType;
  }

  public boolean isBlankCell() {
    return blankCell;
  }
}
