package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SupplierSupplyRatioWorkbookParserImplTest {
  private final SupplierSupplyRatioWorkbookParserImpl parser = new SupplierSupplyRatioWorkbookParserImpl();
  private static final List<String> HEADERS =
      List.of("供应商名称", "供应商代码", "物料名称", "物料代码", "规格型号", "比例");

  @Test void findsMatchingSheetAtAnyPositionAndMapsActualTemplateHeaders() throws Exception {
    for (String name : List.of("供货比例-3季度比例", "2026供货比率", "供货比利新版本")) {
      var result = parser.parse(workbook(name, false, List.of(
          List.of("供应商A", "S1", "物料A", "M1", "规格", "65%"))), "book.xlsx", null);
      assertThat(result.getErrors()).isEmpty();
      assertThat(result.getRows()).hasSize(1);
      var row = result.getRows().getFirst();
      assertThat(row.getSupplierCode()).isEqualTo("S1");
      assertThat(row.getMaterialCode()).isEqualTo("M1");
      assertThat(row.getSupplyRatio()).isEqualByComparingTo("0.65");
      assertThat(row.getUnit()).isNull();
    }
  }

  @Test void multipleMatchingSheetsRequireExplicitChoice() throws Exception {
    var missing = parser.parse(workbook("供货比例本期", true, List.of()), "a.xlsx", null);
    assertThat(missing.getErrors()).anySatisfy(e -> assertThat(e.getMessage()).contains("多个"));
    var selected = parser.parse(workbook("供货比例本期", true, List.of(
        List.of("供应商", "S1", "物料", "M1", "规格", "0.3"))),
        "a.xlsx", "供货比例本期");
    assertThat(selected.getRows()).hasSize(1);
    assertThat(selected.getErrors()).isEmpty();
  }

  @Test void importsMissingRatiosAsNullAndKeepsExplicitZero() throws Exception {
    var rows = List.of(
        List.of("供应商A", "S1", "物料", "M1", "规格", "补充"),
        List.of("供应商B", "S2", "物料", "M1", "规格", ""),
        List.of("供应商C", "S3", "物料", "M1", "规格", "0"));
    var result = parser.parse(workbook("供货比率", false, rows), "a.xlsx", null);
    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(3);
    assertThat(result.getRows().get(0).getSupplyRatio()).isNull();
    assertThat(result.getRows().get(1).getSupplyRatio()).isNull();
    assertThat(result.getRows().get(2).getSupplyRatio()).isZero();
  }

  @Test void invalidTextAndOutOfRangeRatiosRejectImport() throws Exception {
    var result = parser.parse(workbook("供货比率", false, List.of(
        List.of("供应商", "S1", "物料", "M1", "规格", "abc"),
        List.of("供应商", "S2", "物料", "M1", "规格", "110%"))), "a.xlsx", null);
    assertThat(result.getErrors()).hasSize(2);
    assertThat(result.getRows()).isEmpty();
  }

  @Test void numericPercentRetainsActualValueInsteadOfRoundedDisplay() throws Exception {
    try (var book = new XSSFWorkbook(workbook("供货比例", false, List.of(
        List.of("供应商", "S1", "物料", "M1", "规格", "0"))));
        var out = new ByteArrayOutputStream()) {
      var cell = book.getSheet("供货比例").getRow(1).getCell(5);
      cell.setCellValue(0.123456);
      var style = book.createCellStyle();
      style.setDataFormat(book.createDataFormat().getFormat("0%"));
      cell.setCellStyle(style);
      book.write(out);
      var result = parser.parse(new ByteArrayInputStream(out.toByteArray()), "a.xlsx", null);
      assertThat(result.getRows().getFirst().getSupplyRatio()).isEqualByComparingTo("0.123456");
    }
  }

  private ByteArrayInputStream workbook(String name, boolean multiple, List<List<String>> rows) throws Exception {
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      workbook.createSheet("其他资料1");
      workbook.createSheet("其他资料2");
      var sheet = workbook.createSheet(name);
      if (multiple) workbook.createSheet("历史供货比例");
      var header = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
      for (int i = 0; i < rows.size(); i++) {
        var row = sheet.createRow(i + 1);
        for (int j = 0; j < rows.get(i).size(); j++) row.createCell(j).setCellValue(rows.get(i).get(j));
      }
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    }
  }
}
