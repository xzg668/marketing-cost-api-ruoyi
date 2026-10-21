package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PlateMaterialWorkbookParserTest {
  private final PlateMaterialWorkbookParser parser = new PlateMaterialWorkbookParser();

  @Test void onlyNamedFinanceSheetIsReadAndFooterDoesNotBecomeBusinessData() {
    byte[] bytes = workbook(w -> {
      var ignored = w.createSheet("Sheet1");
      ignored.createRow(0).createCell(0).setCellValue("不是六种材质；不得参与校验");
      w.setSheetOrder("Sheet1", 0);
      w.setSheetHidden(0, true);
      var sheet = w.getSheet("提供财务");
      row(w, 3, " B12-8H-V20 ", "水水316L");
      sheet.createRow(3).createCell(0).setCellValue("注：来源说明");
      sheet.createRow(5).createCell(0).setCellValue("编制：签字");
      sheet.createRow(6);
    });
    var result = parser.parse(bytes, "C:\\financial\\source.xlsx");
    assertThat(result.fileName()).isEqualTo("source.xlsx");
    assertThat(result.errors()).isEmpty();
    assertThat(result.rows()).containsExactly(new PlateMaterialWorkbookParser.SourceRow(3, "B12-8H-V20", PlateMaterial.WATER_WATER_316L));
  }

  @Test void illegalRowsReportOriginalExcelPositionsInsteadOfBeingSkipped() {
    var result = parser.parse(workbook(w -> {
      row(w, 3, null, "水水316L");
      row(w, 4, "M-4", null);
      row(w, 5, "M-5", "水水316");
      row(w, 7, "M-7", "水氟316L");
      row(w, 8, "M-8", "水氟316L");
      w.getSheet("提供财务").getRow(7).getCell(1).setCellFormula("\"M-8\"");
    }), "test.xlsx");
    assertThat(result.errors()).extracting(PlateMaterialWorkbookParser.RowError::excelRow).containsExactly(3, 4, 5, 6, 8);
    assertThat(result.rows()).extracting(PlateMaterialWorkbookParser.SourceRow::model).containsExactly("M-7");
  }

  @Test void exactDuplicatesMergeButConflictsRejectAndIdentityDifferencesRemainDistinct() {
    var result = parser.parse(workbook(w -> {
      row(w, 3, " A-b ", "水水316L");
      row(w, 4, "A-b", "水水316L");
      row(w, 5, "A-b", "水氟316L");
      row(w, 6, "a-b", "水水316L");
      row(w, 7, "A- b", "水水316L");
      row(w, 8, "A-b-L", "水水316L");
    }), "test.xlsx");
    assertThat(result.rows()).hasSize(4);
    assertThat(result.duplicates()).containsExactly(new PlateMaterialWorkbookParser.Duplicate(4, 3, "A-b"));
    assertThat(result.errors()).singleElement().satisfies(error -> {
      assertThat(error.excelRow()).isEqualTo(5);
      assertThat(error.message()).contains("第 3 行", "不同");
    });
  }

  @Test void missingSheetWrongHeaderEmptyDataAndUnexpectedFooterRowsAreRejected() {
    assertThatThrownBy(() -> parser.parse(workbook(w -> w.setSheetName(0, "Sheet1")), "test.xlsx"))
        .hasMessageContaining("缺少工作表");
    assertThatThrownBy(() -> parser.parse(workbook(w -> w.getSheetAt(0).getRow(1).getCell(1).setCellValue("型号")), "test.xlsx"))
        .hasMessageContaining("表头");
    assertThat(parser.parse(workbook(w -> {}), "test.xlsx").errors()).singleElement().satisfies(e ->
        assertThat(e.message()).contains("没有可导入"));
    var result = parser.parse(workbook(w -> {
      row(w, 3, "A", "水氟316L");
      w.getSheetAt(0).createRow(3).createCell(0).setCellValue("注：结尾");
      row(w, 5, "B", "水氟316L");
    }), "test.xlsx");
    assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.excelRow()).isEqualTo(5));
  }

  @Test void boundsAndFileFormatAreValidated() {
    assertThatThrownBy(() -> parser.parse(new byte[0], "a.xlsx")).hasMessageContaining("非空");
    assertThatThrownBy(() -> parser.parse(new byte[PlateMaterialWorkbookParser.MAX_BYTES + 1], "a.xlsx"))
        .hasMessageContaining("10 MB");
    assertThatThrownBy(() -> parser.parse(workbook(w -> {}), "a.xls")).hasMessageContaining("xlsx");
    var result = parser.parse(workbook(w -> row(w, 3, "x".repeat(256), "水水316L")), "a.xlsx");
    assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("255"));
  }

  static byte[] workbook(Consumer<XSSFWorkbook> edit) {
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("提供财务");
      sheet.createRow(0).createCell(0).setCellValue("已报价型号");
      var header = sheet.createRow(1);
      header.createCell(1).setCellValue("产品型号");
      header.createCell(2).setCellValue("产品应用材质");
      edit.accept(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  static void row(XSSFWorkbook workbook, int excelRow, String model, String material) {
    var row = workbook.getSheet("提供财务").createRow(excelRow - 1);
    if (model != null) row.createCell(1).setCellValue(model);
    if (material != null) row.createCell(2).setCellValue(material);
  }
}
