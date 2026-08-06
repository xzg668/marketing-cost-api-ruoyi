package com.sanhua.marketingcost.dto;

import java.util.List;

/** 类型 2 工作簿中标准导入 Sheet 的原始行，当前阶段不与产品行合并。 */
public final class PriceLinkedType2StandardRow {

  private final String sourceSheetName;
  private final Integer sourceRowNumber;
  private final String materialCode;
  private final String supplierName;
  private final String supplierCode;
  private final List<PriceLinkedType2CellSnapshot> cells;

  public PriceLinkedType2StandardRow(
      String sourceSheetName,
      Integer sourceRowNumber,
      String materialCode,
      String supplierName,
      String supplierCode,
      List<PriceLinkedType2CellSnapshot> cells) {
    this.sourceSheetName = sourceSheetName;
    this.sourceRowNumber = sourceRowNumber;
    this.materialCode = materialCode;
    this.supplierName = supplierName;
    this.supplierCode = supplierCode;
    this.cells = List.copyOf(cells);
  }

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public List<PriceLinkedType2CellSnapshot> getCells() {
    return cells;
  }
}
