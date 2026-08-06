package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaFactorBinding;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import java.math.BigDecimal;
import java.util.List;

final class PriceLinkedType2FormulaTestSupport {

  private PriceLinkedType2FormulaTestSupport() {
  }

  static PriceLinkedType2FormulaConverterImpl converter() {
    return new PriceLinkedType2FormulaConverterImpl(
        new PriceLinkedType2FormulaReferenceClassifierImpl());
  }

  static PriceLinkedType2ProductRow row(
      int rowNumber,
      String formula,
      PriceLinkedType2CellSnapshot... snapshots) {
    return row("Sheet1", rowNumber, formula, snapshots);
  }

  static PriceLinkedType2ProductRow row(
      String sheetName,
      int rowNumber,
      String formula,
      PriceLinkedType2CellSnapshot... snapshots) {
    return rowWithPrices(
        sheetName, rowNumber, formula, null, null, snapshots);
  }

  static PriceLinkedType2ProductRow rowWithPrices(
      int rowNumber,
      String formula,
      String taxIncludedPrice,
      String taxExcludedPrice,
      PriceLinkedType2CellSnapshot... snapshots) {
    return rowWithPrices(
        "Sheet1",
        rowNumber,
        formula,
        taxIncludedPrice,
        taxExcludedPrice,
        snapshots);
  }

  static PriceLinkedType2ProductRow rowWithPrices(
      String sheetName,
      int rowNumber,
      String formula,
      String taxIncludedPrice,
      String taxExcludedPrice,
      PriceLinkedType2CellSnapshot... snapshots) {
    return new PriceLinkedType2ProductRow(
        sheetName,
        rowNumber,
        "MAT-" + rowNumber,
        "测试产品",
        "SPEC",
        "只",
        "测试供应商",
        formula,
        "R" + rowNumber,
        decimal(taxIncludedPrice),
        decimal(taxExcludedPrice),
        List.of(snapshots));
  }

  static PriceLinkedType2MergedRow merged(
      PriceLinkedType2ProductRow productRow, String taxIncludedText) {
    PriceLinkedType2StandardRow standardRow = new PriceLinkedType2StandardRow(
        "importdata1",
        productRow.getSourceRowNumber(),
        productRow.getMaterialCode(),
        productRow.getSupplierName(),
        "SUP-001",
        List.of());
    return new PriceLinkedType2MergedRow(
        productRow,
        standardRow,
        "2026-07",
        "测试事业部",
        productRow.getMaterialCode(),
        productRow.getSupplierName(),
        "SUP-001",
        "财务导入",
        "采购件",
        taxIncludedText,
        null,
        null,
        "测试事业部 | 2026-07 | "
            + productRow.getMaterialCode() + " | SUP-001");
  }

  static PriceLinkedType2CellSnapshot value(
      String cellRef, String header, String value) {
    return value("Sheet1", cellRef, header, value, null, null);
  }

  static PriceLinkedType2CellSnapshot value(
      String sheetName,
      String cellRef,
      String header,
      String value,
      String formula,
      String unit) {
    return new PriceLinkedType2CellSnapshot(
        sheetName,
        cellRef,
        header,
        value,
        value == null ? null : new BigDecimal(value),
        formula,
        unit);
  }

  static PriceLinkedType2CellSnapshot blank(
      String cellRef, String header, String unit) {
    return new PriceLinkedType2CellSnapshot(
        "Sheet1",
        cellRef,
        header,
        "",
        null,
        null,
        unit,
        "BLANK",
        true);
  }

  static PriceLinkedType2FormulaFactorBinding factor(
      String cellRef, String shortName, long identityId, String price) {
    return factor("Sheet1", cellRef, shortName, identityId, price);
  }

  static PriceLinkedType2FormulaFactorBinding factor(
      String sheetName,
      String cellRef,
      String shortName,
      long identityId,
      String price) {
    return new PriceLinkedType2FormulaFactorBinding(
        sheetName,
        cellRef,
        shortName,
        identityId,
        new BigDecimal(price));
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
