package com.sanhua.marketingcost.service.collaboration;

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
  void findsHeaderWhenItIsNotFirstRow() throws Exception {
    byte[] bytes = workbook(5, List.of(row("1", "A-1", "零件", "铜", "B", "B", "1", "1", "")), false);
    ElectronicDrawingExcelParseResult result = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    assertThat(result.valid()).isTrue();
    assertThat(result.nodes()).hasSize(1);
    assertThat(result.nodes().getFirst().sourceRowNumber()).isEqualTo(7);
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
  void createsOneSyntheticRootAboveExcelNodes() throws Exception {
    byte[] bytes = workbook(0, List.of(
        row("1", "A", "一级", "铜", "B", "B", "1", "", ""),
        row("1.1", "B", "二级", "铜", "B", "B", "2", "", "")), false);
    ElectronicDrawingExcelParseResult parsed = parser.parse("drawing.xlsx", new ByteArrayInputStream(bytes));
    ElectronicDrawingBomCandidate.RootProduct root = new ElectronicDrawingBomCandidate.RootProduct(
        "P-1", null, "报价产品", "S", "M", "D", "MANUFACTURE", "件");

    ElectronicDrawingBomCandidate candidate = new ElectronicDrawingBomCandidateFactory().create(parsed, root);

    assertThat(candidate.nodes()).hasSize(3);
    assertThat(candidate.nodes().getFirst().nodeKey()).isEqualTo("ROOT");
    assertThat(candidate.nodes().get(1).parentNodeKey()).isEqualTo("ROOT");
    assertThat(candidate.nodes().get(2).parentNodeKey()).isEqualTo("ED-1");
    assertThat(candidate.nodes().get(2).quantity()).isEqualByComparingTo(new BigDecimal("2"));
  }

  @Test
  void refusesCandidateWhenParsingHasIssues() {
    ElectronicDrawingExcelParseResult invalid = new ElectronicDrawingExcelParseResult(
        "x.xlsx", null, List.of(), List.of(new ElectronicDrawingExcelParseResult.Issue(
            "HEADER_MISSING", null, null, "missing")));
    assertThatThrownBy(() -> new ElectronicDrawingBomCandidateFactory().create(invalid,
        new ElectronicDrawingBomCandidate.RootProduct("P", null, "P", null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
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
    assertThat(result.nodes()).hasSize(20);
    assertThat(find(result, "2.1").parentSourceSequence()).isEqualTo("2");
    assertThat(find(result, "2.2").parentSourceSequence()).isEqualTo("2");
    assertThat(find(result, "2.3").parentSourceSequence()).isEqualTo("2");
    assertThat(find(result, "3.1").parentSourceSequence()).isEqualTo("3");
    assertThat(find(result, "3.2").parentSourceSequence()).isEqualTo("3");
    assertThat(find(result, "1").drawingCode()).isEqualTo("SBV-A05-001163");
    assertThat(find(result, "15").drawingCode()).isEqualTo("SBV-A05-029080");
  }

  private ElectronicDrawingExcelParseResult.SourceNode find(
      ElectronicDrawingExcelParseResult result, String sequence) {
    return result.nodes().stream().filter(node -> sequence.equals(node.sourceSequence()))
        .findFirst().orElseThrow();
  }

  private byte[] workbook(int headerRowIndex, List<List<String>> rows, boolean footer) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Sheet");
      Row header = sheet.createRow(headerRowIndex);
      for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
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
}
