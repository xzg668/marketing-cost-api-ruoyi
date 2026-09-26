package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

class TechnicalDataSalaryUploadParserTest {
  private final TechnicalDataSalaryUploadParser parser = new TechnicalDataSalaryUploadParser();

  @Test void actualSixRowsConvertFenToYuanOnceAndIgnoreUnusedFormulaRows() throws Exception {
    var parsed = parser.parse("工时模板2026第一版.xlsx", original());
    assertThat(parsed.issues()).isEmpty();
    assertThat(parsed.items()).hasSize(6);
    assertThat(parsed.amountFen()).isEqualByComparingTo("52.6034");
    assertThat(parsed.amountYuan()).isEqualByComparingTo("0.526034");
    assertThat(parsed.items().getFirst().amountYuan()).isEqualByComparingTo("0.075207");
    assertThat(parsed.items().getFirst().wageFormula()).isEqualTo("ROUND(J3*H3*100*1.03/3600,4)");
    assertThat(parsed.items().getLast().row()).isEqualTo(8);
    assertThat(parsed.items().getLast().processNo()).isNull();
    assertThat(parsed.fileSha256()).isEqualTo(TechnicalDataAttachmentStore.sha256(original()));
  }

  @Test void changedInputsAreRecalculatedWithoutTrustingCachedAmountsAndZeroWageIsValid() throws Exception {
    try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(original()))) {
      var cell = workbook.getSheetAt(0).getRow(2).getCell(7);
      cell.setCellValue(46);
      var doubled = parser.parse("工资.xlsx", bytes(workbook));
      assertThat(doubled.issues()).isEmpty();
      assertThat(doubled.items().getFirst().amountYuan()).isEqualByComparingTo("0.150413");
      cell.setCellValue(0);
      var zero = parser.parse("工资.xlsx", bytes(workbook));
      assertThat(zero.issues()).isEmpty();
      assertThat(zero.items().getFirst().amountYuan()).isZero();
    }
  }

  @Test void malformedFormulaNegativeInputAndHalfRowReportLocationsAndNeverReturnUsableTotal() throws Exception {
    try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(original()))) {
      var sheet = workbook.getSheetAt(0);
      sheet.getRow(2).getCell(10).setCellFormula("J3/0");
      sheet.getRow(3).getCell(7).setCellValue(-1);
      sheet.getRow(8).createCell(5).setCellValue(1);
      var parsed = parser.parse("工资.xlsx", bytes(workbook));
      assertThat(parsed.issues()).hasSize(3).extracting(I -> I.row()).containsExactly(3, 4, 9);
      assertThat(parsed.issues().getFirst().message()).contains("单件工资", "#DIV/0!");
      assertThat(parsed.issues()).allSatisfy(issue -> assertThat(issue.sheetName()).isEqualTo("Sheet1"));
      assertThat(parsed.amountYuan()).isNull();
      assertThat(parsed.amountFen()).isNull();
    }
  }

  @Test void blankAndWrongTemplatesCannotBeApprovedAndTotalsDoNotDoubleCount() throws Exception {
    try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(original()))) {
      var sheet = workbook.getSheetAt(0);
      var row = sheet.getRow(8);
      row.createCell(0).setCellValue("合计");
      row.getCell(10).setCellFormula("SUM(K3:K8)");
      assertThat(parser.parse("工资.xlsx", bytes(workbook)).amountYuan()).isEqualByComparingTo("0.526034");
      for (int index = 2; index < 8; index++) for (int col = 0; col < 8; col++) {
        var cell = sheet.getRow(index).getCell(col); if (cell != null) cell.setBlank();
      }
      assertThat(parser.parse("空表.xlsx", bytes(workbook)).issues()).anySatisfy(issue -> assertThat(issue.message()).contains("没有工时明细"));
      sheet.getRow(1).getCell(10).setCellValue("单价");
      assertThat(parser.parse("错表.xlsx", bytes(workbook)).issues().getFirst().row()).isEqualTo(2);
    }
    assertThatThrownBy(() -> parser.parse("工资.xls", original())).hasMessageContaining("xlsx");
  }

  @Test void downloadableTemplateHasNoFormulaErrorsAndMatchesActualSample() throws Exception {
    try (var input = getClass().getResourceAsStream("/templates/technical-data/salary.xlsx")) {
      var bytes = input.readAllBytes();
      assertThat(parser.parse("工资.xlsx", bytes).amountYuan()).isEqualByComparingTo("0.526034");
      try (var workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
        var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
          if (cell.getCellType() == CellType.FORMULA) assertThat(evaluator.evaluate(cell).getCellType()).isNotEqualTo(CellType.ERROR);
        }
      }
    }
  }

  static byte[] original() throws Exception {
    try (var input = TechnicalDataSalaryUploadParserTest.class.getResourceAsStream("/fixtures/technical-data/salary-original.xlsx")) { return input.readAllBytes(); }
  }
  static byte[] bytes(Workbook workbook) throws Exception {
    var output = new ByteArrayOutputStream(); workbook.write(output); return output.toByteArray();
  }
}
