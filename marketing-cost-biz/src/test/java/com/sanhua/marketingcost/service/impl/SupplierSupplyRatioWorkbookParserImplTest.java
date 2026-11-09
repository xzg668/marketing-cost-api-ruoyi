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

  @Test void shellFormatWithArbitrarySheetNameAndExtraColumnsPreservesNumericPercentages() throws Exception {
    try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("外壳");
      var headers = List.of("组织", "来源", "供应商名称", "供应商代码", "采购分类", "物料名称", "物料代码", "规格型号", "所属事业部", "供货比例");
      var header = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
      var percent = book.createCellStyle(); percent.setDataFormat(book.createDataFormat().getFormat("0%"));
      for (int i = 0; i < 2; i++) {
        var row = sheet.createRow(i + 1);
        row.createCell(0).setCellValue(210);
        row.createCell(2).setCellValue("供应商" + i);row.createCell(3).setCellValue("S000" + i);
        row.createCell(5).setCellValue("外壳");
        if (i == 0) row.createCell(6).setCellValue(721300148); else row.createCell(6).setCellValue("721300148");
        row.createCell(7).setCellValue("DPF-S03-024001");
        row.createCell(9).setCellValue(i == 0 ? .2 : .8);row.getCell(9).setCellStyle(percent);
      }
      book.write(out);
      for (String selected : new String[] {null, "外壳"}) {
        var result = parser.parse(new ByteArrayInputStream(out.toByteArray()), "外壳.xlsx", selected);
        assertThat(result.getErrors()).isEmpty();assertThat(result.getSheetName()).isEqualTo("外壳");
        assertThat(result.getRows()).hasSize(2).allSatisfy(row -> {
          assertThat(row.getMaterialCode()).isEqualTo("721300148");
          assertThat(row.getSpecModel()).isEqualTo("DPF-S03-024001");
        });
        assertThat(result.getRows().get(0).getSupplyRatio()).isEqualByComparingTo("0.2");
        assertThat(result.getRows().get(1).getSupplyRatio()).isEqualByComparingTo("0.8");
      }
    }
  }

  @Test void namedInstructionsDoNotCountAsDataAndArbitraryMultipleTablesRequireSelection() throws Exception {
    try (var book = new XSSFWorkbook(workbook("外壳", false, List.of(List.of("甲", "S1", "外壳", "M1", "型号", "20%")))); var out = new ByteArrayOutputStream()) {
      book.createSheet("供货比例说明").createRow(0).createCell(0).setCellValue("说明文字");
      book.cloneSheet(book.getSheetIndex("外壳"), "Sheet1"); book.write(out);
      assertThat(parser.parse(new ByteArrayInputStream(out.toByteArray()), "book.xlsx", null).getErrors())
          .anySatisfy(e -> assertThat(e.getMessage()).contains("多个"));
      assertThat(parser.parse(new ByteArrayInputStream(out.toByteArray()), "book.xlsx", "Sheet1").getRows()).hasSize(1);
      assertThat(parser.parse(new ByteArrayInputStream(out.toByteArray()), "book.xlsx", "供货比例说明").getErrors()).isNotEmpty();
    }
  }

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

  @Test void missingSupplierCodeIsAllowedAndMissingNameStillRejects() throws Exception {
    var result = parser.parse(workbook("供货比例", false, List.of(
        List.of("供应商甲", "", "物料", "M1", "规格", "0.6"),
        List.of("供应商乙", "", "物料", "M1", "规格", "0.4"))), "a.xlsx", null);
    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(2).allSatisfy(row -> assertThat(row.getSupplierCode()).isNull());
    var invalid = parser.parse(workbook("供货比例", false, List.of(
        List.of("", "", "物料", "M1", "规格", "0.6"))), "a.xlsx", null);
    assertThat(invalid.getErrors()).anySatisfy(error -> assertThat(error.getMessage()).contains("供应商不能为空"));
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

  @Test void explicitModelWinsOverSpecificationInEitherColumnOrderIncludingBlankModel() throws Exception {
    for (boolean modelFirst : List.of(true, false)) {
      for (String model : List.of("实际型号", "")) {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
          var sheet = book.createSheet("供货比率");
          var headers = List.of("物料代码", "物料名称", "供应商名称", "供货比例",
              modelFirst ? "型号" : "规格", modelFirst ? "规格" : "型号");
          var values = List.of("00123", "物料", "供应商", "60%", modelFirst ? model : "实际规格", modelFirst ? "实际规格" : model);
          for (int i = 0; i < headers.size(); i++) {
            if (i == 0) { sheet.createRow(0); sheet.createRow(1); }
            sheet.getRow(0).createCell(i).setCellValue(headers.get(i));
            sheet.getRow(1).createCell(i).setCellValue(values.get(i));
          }
          book.write(out);
          var parsed = parser.parse(new ByteArrayInputStream(out.toByteArray()), "补齐.xlsx", null);
          assertThat(parsed.getErrors()).isEmpty();
          assertThat(parsed.getRows().getFirst().getSpecModel()).isEqualTo(model.isEmpty() ? null : model);
        }
      }
    }
  }

  @Test void retainsExplicitRuleFromOriginalAndNewHeaders() throws Exception {
    for (String header : List.of("规则：取供货比例大的", "取价规则", "规则")) {
      try (var book = new XSSFWorkbook(workbook("供货比例", false,
          List.of(List.of("供应商A", "A", "物料", "M1", "型号", ""))));
          var out = new ByteArrayOutputStream()) {
        book.getSheet("供货比例").getRow(0).createCell(6).setCellValue(header);
        book.getSheet("供货比例").getRow(1).createCell(6).setCellValue("无供货比例，取最高价");
        book.write(out);
        var result = parser.parse(new ByteArrayInputStream(out.toByteArray()), "rules.xlsx", null);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getRows().getFirst().getSelectionRule()).isEqualTo("无供货比例，取最高价");
        assertThat(result.getRows().getFirst().getSupplyRatio()).isNull();
      }
    }
  }

  private ByteArrayInputStream workbook(String name, boolean multiple, List<List<String>> rows) throws Exception {
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      workbook.createSheet("其他资料1");
      workbook.createSheet("其他资料2");
      var sheet = workbook.createSheet(name);
      if (multiple) {
        var other = workbook.createSheet("历史供货比例").createRow(0);
        for (int i = 0; i < HEADERS.size(); i++) other.createCell(i).setCellValue(HEADERS.get(i));
      }
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
