package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class TechnicalDataAuxiliaryUploadParserTest {
  private final TechnicalDataAuxiliaryUploadParser parser = new TechnicalDataAuxiliaryUploadParser();

  @Test void actualTemplateEvaluatesAllocationAndKeepsCategorySeparateFromOptionalSubject() throws Exception {
    try (var input = getClass().getResourceAsStream("/templates/technical-data/auxiliary.xlsx")) {
      assertThat(input).isNotNull();
      var parsed = parser.parse("辅料模板.xlsx", input.readAllBytes());
      assertThat(parsed.issues()).isEmpty(); assertThat(parsed.items()).hasSize(3);
      assertThat(parsed.items()).extracting(row -> row.materialNo()).containsExactly("311034325", "337120955", "311020150");
      assertThat(parsed.fileSha256()).matches("[a-f0-9]{64}");
      assertThat(parsed.items()).allSatisfy(row -> assertThat(row.secondarySubjectName()).isNull());
      assertThat(parsed.items().get(0).category()).isEqualTo("清洗类");
      assertThat(parsed.items().get(0).amountPerProduct()).isEqualByComparingTo("0.08157002");
      assertThat(parsed.items().get(1).amountPerProduct()).isEqualByComparingTo("0.25");
      assertThat(parsed.items().get(2).amountPerProduct()).isEqualByComparingTo("0.00673167");
      assertThat(parsed.items().get(0).sheetRow()).isEqualTo(4);
    }
  }

  @Test void badFormulaHalfFilledRowAndDuplicateSequenceAreLocatedWithoutPretendingImportSucceeded() throws Exception {
    try (var workbook = template()) {
      var sheet = workbook.getSheetAt(0);
      sheet.getRow(3).getCell(9).setCellFormula("G4/0");
      sheet.getRow(5).getCell(2).setCellValue(2);
      sheet.getRow(6).createCell(6).setCellValue(15);
      var parsed = parser.parse("辅料.xlsx", bytes(workbook));
      assertThat(parsed.issues()).hasSize(3).extracting(row -> row.row()).containsExactly(4, 6, 7);
      assertThat(parsed.issues()).allSatisfy(row -> assertThat(row.sheetName()).isEqualTo("辅料一览表"));
      assertThat(parsed.issues().get(0).message()).contains("分摊费用", "Excel 错误");
      assertThat(parsed.issues().get(1).message()).contains("序号");
      assertThat(parsed.issues().get(2).message()).contains("辅料名称");
    }
  }

  @Test void oldTemplateIsAcceptedButWrongHeadersAndWrongFileTypeAreRejected() throws Exception {
    try (var workbook = template()) {
      var sheet = workbook.getSheetAt(0); sheet.getRow(2).removeCell(sheet.getRow(2).getCell(12));
      assertThat(parser.parse("原辅料模板.xlsx", bytes(workbook)).issues()).isEmpty();
      sheet.getRow(2).getCell(9).setCellValue("单价");
      var parsed = parser.parse("错误表头.xlsx", bytes(workbook));
      assertThat(parsed.issues()).hasSize(1); assertThat(parsed.issues().getFirst().row()).isEqualTo(3);
      assertThat(parsed.items()).isEmpty();
    }
    assertThatThrownBy(() -> parser.parse("辅料.xls", new byte[]{1})).hasMessageContaining("xlsx");
  }

  @Test void materialIdentifiersIgnoreNumericDisplayFormattingAndPreserveTextLeadingZeros() throws Exception {
    try (var workbook = template()) {
      var cell = workbook.getSheetAt(0).getRow(3).getCell(4);
      cell.setCellValue("00123456");
      assertThat(parser.parse("辅料.xlsx", bytes(workbook)).items().getFirst().materialNo()).isEqualTo("00123456");
      cell.setCellValue(123.5);
      assertThat(parser.parse("辅料.xlsx", bytes(workbook)).issues().getFirst().message()).contains("数字料号须为正整数");
    }
  }

  private Workbook template() throws Exception {
    return WorkbookFactory.create(getClass().getResourceAsStream("/templates/technical-data/auxiliary.xlsx"));
  }
  private byte[] bytes(Workbook workbook) throws Exception {
    var output = new ByteArrayOutputStream(); workbook.write(output); return output.toByteArray();
  }
}
