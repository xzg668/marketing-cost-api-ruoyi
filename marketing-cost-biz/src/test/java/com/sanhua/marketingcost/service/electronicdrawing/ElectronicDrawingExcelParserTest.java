package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.electronicdrawing.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库正式 Excel 解析契约")
class ElectronicDrawingExcelParserTest {
  private static final List<String> HEADERS = List.of(
      "序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "重量", "备注");
  private final ElectronicDrawingExcelParser parser = new ElectronicDrawingExcelParser();

  @Test
  void parsesHierarchyAndStopsBeforeFooter() throws Exception {
    byte[] bytes = workbook(2, List.of(
        row("1", "A-1", "一级", "铜", "B", "B", "1", "", ""),
        row("2", "A-2", "父件", "/", "B", "B", "1", "10.5", ""),
        row("2.1", "A-21", "子件1", "铜", "B", "B", "2", "3", ""),
        row("2.2", "A-22", "子件2", "铜", "B", "B", "1", "4", ""),
        row("3", "A-3", "另一个父件", "/", "B", "B", "1", "5", "")), true);

    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));

    assertThat(result.valid()).isTrue();
    assertThat(result.nodes()).hasSize(5);
    assertThat(result.nodes().get(2).parentSourceSequence()).isEqualTo("2");
    assertThat(result.nodes().get(2).level()).isEqualTo(2);
    assertThat(result.nodes().get(2).quantity()).isEqualByComparingTo("2");
    assertThat(result.nodes()).noneMatch(node -> "2026年07月28日".equals(node.sourceSequence()));
  }

  @Test
  void stopsWhenFooterTextOccupiesTheQuantityColumn() throws Exception {
    byte[] bytes = workbookWithQuantityColumnFooter();

    ElectronicDrawingExcelParseResult result = parser.parse(
        "drawing.xlsx", new ByteArrayInputStream(bytes));

    assertThat(result.valid()).isTrue();
    assertThat(result.nodes()).hasSize(1);
    assertThat(result.nodes().getFirst().drawingCode()).isEqualTo("A-1");
  }

  @Test
  void findsHeaderWhenItIsNotFirstRow() throws Exception {
    byte[] bytes = workbook(5, List.of(row("1", "A-1", "零件", "铜", "B", "B", "1", "1", "")), false);
    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.valid()).isTrue();
    assertThat(result.nodes()).hasSize(1);
    assertThat(result.nodes().getFirst().sourceRowNumber()).isEqualTo(7);
  }

  @Test
  void resumesAfterPrintedFooterOnlyAtCompleteHeaderAndKeepsCrossPageParents() throws Exception {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("Sheet");
      for (int index : List.of(0, 5, 10)) {
        Row header = sheet.createRow(index);
        for (int column = 0; column < HEADERS.size(); column++)
          header.createCell(column).setCellValue(HEADERS.get(column));
      }
      for (var detail : List.of(
          new String[] {"1", "1", "PARENT", "父件", "1", "10"},
          new String[] {"6", "1.1", "CHILD", "子部件", "2", "3"},
          new String[] {"11", "1.1.1", "LEAF", "下级原料", "4", "0.5"})) {
        Row row = sheet.createRow(Integer.parseInt(detail[0]));
        row.createCell(0).setCellValue(detail[1]);
        row.createCell(1).setCellValue(detail[2]);
        row.createCell(2).setCellValue(detail[3]);
        row.createCell(6).setCellValue(detail[4]);
        row.createCell(7).setCellValue(detail[5]);
      }
      sheet.createRow(2).createCell(0).setCellValue("2026年07月31日浙三司(22)");
      sheet.createRow(3).createCell(2).setCellValue("审核签字");
      sheet.createRow(7).createCell(0).setCellValue("技术专用章");
      sheet.createRow(8).createCell(6).setCellValue("第2页");
      sheet.createRow(12).createCell(0).setCellValue("共3页");
      book.write(output);
      var result = parser.parse("three-pages.xlsx", new ByteArrayInputStream(output.toByteArray()));
      assertThat(result.issues()).isEmpty();
      assertThat(result.nodes()).extracting(ElectronicDrawingExcelParseResult.SourceNode::sourceSequence)
          .containsExactly("1", "1.1", "1.1.1");
      assertThat(result.nodes()).extracting(ElectronicDrawingExcelParseResult.SourceNode::sourceRowNumber)
          .containsExactly(2, 7, 12);
      assertThat(result.nodes().getLast().parentSourceSequence()).isEqualTo("1.1");
      assertThat(result.nodes().getLast().quantity()).isEqualByComparingTo("4");
    }
  }

  @Test
  void parsesAllThreePagesOfShflSampleWhenAvailable() throws Exception {
    String configured = System.getProperty("electronic.drawing.shfl.sample");
    Assumptions.assumeTrue(configured != null && !configured.isBlank(), "未指定SHF(L)正式样例");
    Path sample = Path.of(configured);
    Assumptions.assumeTrue(Files.exists(sample), "本机没有SHF(L)样例");
    ElectronicDrawingExcelParseResult result;
    try (var input = Files.newInputStream(sample)) {
      result = parser.parse(sample.getFileName().toString(), input);
    }
    assertThat(result.issues()).isEmpty();
    assertThat(result.nodes()).hasSize(73);
    assertThat(find(result, "7.1.7.3.1.1")).satisfies(node -> {
      assertThat(node.parentSourceSequence()).isEqualTo("7.1.7.3.1");
      assertThat(node.level()).isEqualTo(6);
      assertThat(node.sourceRowNumber()).isEqualTo(76);
    });
    assertThat(find(result, "7.1.1.2").referenceWeight()).isNull();
    assertThat(find(result, "9").sourceRowNumber()).isEqualTo(97);
  }

  @Test
  void acceptsWeightAndUnitWeightHeaderAliases() throws Exception {
    for (String weightHeader : List.of("重量", "单重")) {
      byte[] bytes = workbook(0,
          List.of(row("1", "A-1", "零件", "铜", "B", "B", "1", "1.25", "")),
          false,
          weightHeader);
      ElectronicDrawingExcelParseResult result = parser.parse(
          "drawing.xlsx", new ByteArrayInputStream(bytes));
      assertThat(result.valid()).as(weightHeader).isTrue();
      assertThat(result.nodes().getFirst().referenceWeight()).as(weightHeader)
          .isEqualByComparingTo("1.25");
      assertThat(result.nodes().getFirst().referenceWeightUnit()).as(weightHeader).isEqualTo("g");
    }
  }

  @Test
  void preservesExplicitWeightUnitsWithoutRescalingOriginalValue() throws Exception {
    for (String label : List.of("单重（g）", "重量(克)", "单重（kg）", "重量(千克)")) {
      byte[] bytes = workbook(0,
          List.of(row("1", "A-1", "零件", "铜", "B", "B", "2", "0.012", "")), false, label);
      var result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
      assertThat(result.issues()).as(label).isEmpty();
      assertThat(result.nodes().getFirst().referenceWeight()).isEqualByComparingTo("0.012");
      assertThat(result.nodes().getFirst().referenceWeightUnit())
          .isEqualTo(label.contains("kg") || label.contains("千克") ? "kg" : "g");
      assertThat(result.nodes().getFirst().quantity()).isEqualByComparingTo("2");
    }
  }

  @Test
  void readsRowUnitsAndRejectsConflictsOrUnsupportedUnits() throws Exception {
    for (String[] example : List.of(
        new String[] {"重量", "kg", "kg"},
        new String[] {"单重（g）", "克", "g"},
        new String[] {"重量(g)", "kg", "WEIGHT_UNIT_CONFLICT"},
        new String[] {"重量", "吨", "WEIGHT_UNIT_INVALID"},
        new String[] {"重量(lb)", "g", "WEIGHT_UNIT_INVALID"})) {
      byte[] source = workbook(0,
          List.of(row("1", "A-1", "零件", "铜", "B", "B", "1", "12", "")), false, example[0]);
      byte[] bytes;
      try (var book = new XSSFWorkbook(new ByteArrayInputStream(source));
           var output = new ByteArrayOutputStream()) {
        book.getSheetAt(0).getRow(0).createCell(9).setCellValue("重量单位");
        book.getSheetAt(0).getRow(1).createCell(9).setCellValue(example[1]);
        book.write(output);
        bytes = output.toByteArray();
      }
      var result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
      if (example[2].startsWith("WEIGHT_")) {
        assertThat(result.issues()).extracting(ElectronicDrawingExcelParseResult.Issue::code)
            .containsExactly(example[2]);
      } else {
        assertThat(result.issues()).isEmpty();
        assertThat(result.nodes().getFirst().referenceWeightUnit()).isEqualTo(example[2]);
        assertThat(result.nodes().getFirst().referenceWeight()).isEqualByComparingTo("12");
      }
    }
  }

  @Test
  void leavesMissingWeightEmptyInsteadOfFabricatingZero() throws Exception {
    byte[] bytes = workbook(0,
        List.of(row("1", "A-1", "零件", "铜", "B", "B", "1", "", "")), false);
    var result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.issues()).isEmpty();
    assertThat(result.nodes().getFirst().referenceWeight()).isNull();
    assertThat(result.nodes().getFirst().referenceWeightUnit()).isEqualTo("g");
  }

  @Test
  void reportsInvalidAndDuplicatedSequences() throws Exception {
    byte[] bytes = workbook(0, List.of(
        row("1", "A", "A", "", "", "", "1", "", ""),
        row("1", "B", "B", "", "", "", "1", "", ""),
        row("2.x", "C", "C", "", "", "", "1", "", "")), false);
    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.issues()).extracting(ElectronicDrawingExcelParseResult.Issue::code)
        .contains("SEQUENCE_DUPLICATED", "SEQUENCE_INVALID");
  }

  @Test
  void reportsMissingDirectParent() throws Exception {
    byte[] bytes = workbook(0, List.of(
        row("1", "A", "A", "", "", "", "1", "", ""),
        row("2.1", "B", "B", "", "", "", "1", "", "")), false);
    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.issues()).anySatisfy(issue -> {
      assertThat(issue.code()).isEqualTo("PARENT_MISSING");
      assertThat(issue.sourceSequence()).isEqualTo("2.1");
    });
  }

  @Test
  void reportsMissingAndInvalidQuantity() throws Exception {
    byte[] bytes = workbook(0, List.of(
        row("1", "A", "A", "", "", "", "", "", ""),
        row("2", "B", "B", "", "", "", "0", "", ""),
        row("3", "C", "C", "", "", "", "abc", "", "")), false);
    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.issues()).extracting(ElectronicDrawingExcelParseResult.Issue::code)
        .contains("QUANTITY_REQUIRED", "QUANTITY_INVALID");
  }

  @Test
  void rejectsEmptyFileAndWrongExtension() {
    assertThat(parser.parse("drawing.xlsx", new ByteArrayInputStream(new byte[0])).issues())
        .extracting(ElectronicDrawingExcelParseResult.Issue::code).containsExactly("FILE_EMPTY");
    assertThat(parser.parse("drawing.xls", new ByteArrayInputStream(new byte[] {1})).issues())
        .extracting(ElectronicDrawingExcelParseResult.Issue::code).containsExactly("FILE_TYPE_INVALID");
  }

  @Test
  void rejectsCorruptWorkbook() {
    assertThat(parser.parse("drawing.xlsx", new ByteArrayInputStream(new byte[] {1, 2, 3})).issues())
        .extracting(ElectronicDrawingExcelParseResult.Issue::code)
        .containsExactly("FILE_INVALID");
  }

  @Test
  void rejectsWorkbookWithoutFormalHeader() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      workbook.createSheet("Sheet").createRow(0).createCell(0).setCellValue("错误表头");
      workbook.write(output);
      ElectronicDrawingExcelParseResult result = parser.parse(
          "drawing.xlsx", new ByteArrayInputStream(output.toByteArray()));
      assertThat(result.issues()).extracting(ElectronicDrawingExcelParseResult.Issue::code)
          .containsExactly("HEADER_MISSING");
    }
  }

  @Test
  void parsesTheFormalElectronicDrawingSampleWhenAvailable() throws Exception {
    String configuredSample = System.getProperty("electronic.drawing.sample");
    Assumptions.assumeTrue(configuredSample != null && !configuredSample.isBlank(),
        "未通过 -Delectronic.drawing.sample 指定正式电子图库样例");
    Path sample = Path.of(configuredSample);
    Assumptions.assumeTrue(Files.exists(sample), "本机没有正式电子图库样例");
    ElectronicDrawingExcelParseResult result;
    try (var input = Files.newInputStream(sample)) {
      result = parser.parse(sample.getFileName().toString(), input);
    }
    assertThat(result.issues()).isEmpty();
    assertThat(result.sourceSheetName()).isEqualTo("Sheet");
    assertThat(result.nodes()).hasSize(40);
    assertThat(find(result, "1")).satisfies(node -> {
      assertThat(node.parentSourceSequence()).isNull();
      assertThat(node.drawingCode()).isEqualTo("S040A-29804");
      assertThat(node.quantity()).isEqualByComparingTo("1");
      assertThat(node.sourceRowNumber()).isEqualTo(2);
    });
    assertThat(find(result, "1.1.1.1.2")).satisfies(node -> {
      assertThat(node.parentSourceSequence()).isEqualTo("1.1.1.1");
      assertThat(node.level()).isEqualTo(5);
      assertThat(node.drawingCode()).isEqualTo("S040A-03101");
      assertThat(node.referenceWeight()).isEqualByComparingTo("17.185");
      assertThat(node.sourceRowNumber()).isEqualTo(7);
    });
    assertThat(find(result, "1.6.4")).satisfies(node -> {
      assertThat(node.parentSourceSequence()).isEqualTo("1.6");
      assertThat(node.drawingCode()).isEqualTo("B-JG-H0021");
      assertThat(node.quantity()).isEqualByComparingTo("2");
      assertThat(node.sourceRowNumber()).isEqualTo(33);
    });
    assertThat(find(result, "7")).satisfies(node -> {
      assertThat(node.parentSourceSequence()).isNull();
      assertThat(node.drawingCode()).isEqualTo("S040A-41006");
      assertThat(node.quantity()).isEqualByComparingTo("2");
      assertThat(node.sourceRowNumber()).isEqualTo(41);
    });
  }

  private ElectronicDrawingExcelParseResult.SourceNode find(
      ElectronicDrawingExcelParseResult result, String sequence) {
    return result.nodes().stream().filter(node -> sequence.equals(node.sourceSequence()))
        .findFirst().orElseThrow();
  }

  private byte[] workbook(int headerRowIndex, List<List<String>> rows, boolean footer) throws Exception {
    return workbook(headerRowIndex, rows, footer, "重量");
  }

  private byte[] workbook(
      int headerRowIndex, List<List<String>> rows, boolean footer, String weightHeader) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Sheet");
      Row header = sheet.createRow(headerRowIndex);
      for (int i = 0; i < HEADERS.size(); i++) {
        header.createCell(i).setCellValue("重量".equals(HEADERS.get(i)) ? weightHeader : HEADERS.get(i));
      }
      int index = headerRowIndex + 1;
      for (List<String> values : rows) {
        Row row = sheet.createRow(index++);
        for (int i = 0; i < values.size(); i++) row.createCell(i).setCellValue(values.get(i));
      }
      if (footer) {
        sheet.createRow(index++).createCell(0).setCellValue("2026年07月28日");
        Row footerRow = sheet.createRow(index);
        footerRow.createCell(2).setCellValue("TG-22-280-2026");
        footerRow.createCell(4).setCellValue("张忠旭");
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private List<String> row(String... values) {
    return List.of(values);
  }

  private byte[] workbookWithQuantityColumnFooter() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Sheet");
      Row header = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) {
        header.createCell(i).setCellValue(HEADERS.get(i));
      }
      List<String> detail = row("1", "A-1", "零件", "铜", "B", "B", "1", "1", "");
      Row detailRow = sheet.createRow(1);
      for (int i = 0; i < detail.size(); i++) {
        detailRow.createCell(i).setCellValue(detail.get(i));
      }
      sheet.createRow(2).createCell(6).setCellValue("浙江三花商用制冷有限公司");
      Row laterFooter = sheet.createRow(3);
      laterFooter.createCell(0).setCellValue("标记");
      laterFooter.createCell(1).setCellValue("处数");
      laterFooter.createCell(2).setCellValue("更改文件号");
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
