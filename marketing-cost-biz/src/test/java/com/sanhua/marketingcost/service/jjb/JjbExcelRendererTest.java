package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class JjbExcelRendererTest {
  public static JjbReadModel model(int ordinary, int household, int fees) {
    List<JjbReadModel.Row> rows = new ArrayList<>();
    for (int i = 0; i < ordinary; i++)
      rows.add(
          JjbReadModel.Row.builder()
              .key("part-" + i)
              .section("ORDINARY_PARTS")
              .kind("PART")
              .code("0000123456789012345678901234567890-" + i)
              .name("普通部品" + i)
              .drawingNo("图号" + i)
              .unitPrice("1.234567")
              .quantity("2.5")
              .amount("3.086418")
              .priceSource("固定采购价")
              .build());
    for (var code : List.of("MGMT_EXP", "SALES_EXP", "FIN_EXP"))
      rows.add(
          JjbReadModel.Row.builder()
              .key(code)
              .section("THREE_EXPENSES")
              .kind("COST")
              .code(code)
              .name(code)
              .amount("0.000000")
              .rate("0.085")
              .build());
    for (int i = 0; i < household; i++)
      rows.add(
          JjbReadModel.Row.builder()
              .key("hh-" + i)
              .section("HOUSEHOLD")
              .kind("HOUSEHOLD")
              .code("0000208200507-" + i)
              .name("线圈组件" + i)
              .unitPrice("20.985300")
              .quantity("2")
              .amount("51.641245")
              .householdRate("0.085")
              .salesRate("0.25")
              .coefficient("1.05")
              .monthlyPriceIds(List.of(1L))
              .paths(List.of("root/A" + i))
              .build());
    String[] names = {"模具费", "认证费", "折旧费", "工装夹具费"};
    for (int i = 0; i < 4; i++)
      rows.add(
          JjbReadModel.Row.builder()
              .key("fee-" + i)
              .section("PRODUCT_FEES")
              .kind("FEE")
              .code("FEE_" + i)
              .name(names[i])
              .amount(i < fees ? i == 0 ? "0.000000" : "1.234567" : null)
              .feeState(i < fees ? "EXPLICIT_AMOUNT" : "MISSING")
              .build());
    rows.add(
        JjbReadModel.Row.builder()
            .key("property")
            .section("PROPERTY")
            .kind("PROPERTY")
            .name("非标品")
            .coefficient("1.05")
            .build());
    return new JjbReadModel(
        new JjbReadModel.Identity(
            71L,
            "RUN-71",
            "COST-71",
            "OA-71",
            72L,
            "0000001234567890123456789",
            "COMMERCIAL",
            "2026-10",
            "MONTHLY_PRODUCT_COST_V1",
            "PROFILE_OPTIONAL_V1",
            "SUCCESS",
            true),
        new JjbReadModel.Header(
            "测试产品",
            "长型号",
            "客户",
            "00001",
            "客户图号",
            "保存版本",
            "100000.000000",
            "92000.000000",
            "元/吨",
            "非标品",
            "1.05"),
        rows,
        List.of(),
        new JjbReadModel.Totals("267.627207", "-8.000000", "259.627207"),
        List.of());
  }

  static Stream<Arguments> cases() {
    return Stream.of(0, 1, 90)
        .flatMap(
            o ->
                Stream.of(0, 1, 4)
                    .flatMap(h -> Stream.of(0, 1, 4).map(f -> Arguments.of(o, h, f))));
  }

  public static XSSFWorkbook render(JjbReadModel model) throws IOException {
    var output = new ByteArrayOutputStream();
    new JjbExcelRenderer().write(model, output);
    return new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()));
  }

  public static Cell find(Sheet sheet, String value) {
    for (Row row : sheet)
      for (Cell cell : row)
        if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().equals(value))
          return cell;
    throw new AssertionError("Missing cell: " + value);
  }

  public static void amount(Cell cell, String expected) {
    if (expected == null) {
      assertThat(cell.getCellType()).isEqualTo(CellType.BLANK);
      return;
    }
    String actual =
        cell.getCellType() == CellType.STRING
            ? cell.getStringCellValue()
            : new DataFormatter(Locale.ROOT).formatCellValue(cell);
    assertThat(actual).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("cases")
  void dynamicRowsKeepTypesAndExactSavedAmounts(int ordinary, int household, int fees)
      throws Exception {
    var m = model(ordinary, household, fees);
    try (var wb = render(m)) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);
      Sheet sheet = wb.getSheet("见机表3");
      for (var row : m.rows()) {
        if (row.kind().equals("PART") || row.kind().equals("HOUSEHOLD")) {
          var code = find(sheet, row.code());
          assertThat(code.getCellStyle().getDataFormatString()).isEqualTo("@");
          Row excel = sheet.getRow(code.getRowIndex());
          amount(excel.getCell(3), row.unitPrice());
          amount(excel.getCell(5), row.amount());
          assertThat(BigDecimal.valueOf(excel.getCell(4).getNumericCellValue()))
              .isEqualByComparingTo(row.quantity());
        } else if (row.kind().equals("FEE"))
          amount(sheet.getRow(find(sheet, row.name()).getRowIndex()).getCell(5), row.amount());
      }
      amount(sheet.getRow(find(sheet, "不含税总成本1").getRowIndex()).getCell(5), m.totals().totalCost());
      amount(
          sheet.getRow(find(sheet, "材料成本差异").getRowIndex()).getCell(5),
          m.totals().cuMaterialAdjustment());
      amount(
          sheet.getRow(find(sheet, "不含税总成本2").getRowIndex()).getCell(5),
          m.totals().finalQuoteAmount());
      Row rate = sheet.getRow(find(sheet, "MGMT_EXP").getRowIndex());
      assertThat(rate.getCell(14).getNumericCellValue()).isEqualTo(.085);
      assertThat(rate.getCell(14).getCellStyle().getDataFormatString()).endsWith("%");
      assertThat(wb.getPrintArea(0)).contains("$A$1:$O$");
      assertThat(sheet.getRepeatingRows().getLastRow()).isEqualTo(5);
      assertThat(sheet.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
      assertThat(sheet.getPrintSetup().getFitHeight()).isZero();
      assertThat(sheet.getMargin(Sheet.BottomMargin) - sheet.getMargin(Sheet.FooterMargin))
          .isGreaterThanOrEqualTo(.49);
      assertThat(wb.getExternalLinksTable()).isEmpty();
      List<String> texts = new ArrayList<>();
      for (Sheet s : wb)
        for (Row r : s)
          for (Cell c : r) {
            assertThat(c.getCellType()).isNotIn(CellType.FORMULA, CellType.ERROR);
            if (c.getCellType() == CellType.STRING) texts.add(c.getStringCellValue());
          }
      assertThat(texts.contains("家用部件含二项费用询价")).isEqualTo(household > 0);
      assertThat(texts)
          .noneMatch(
              t ->
                  t.contains("20.847")
                      || t.contains("VLOOKUP")
                      || t.contains("1079900000536")
                      || t.contains("？"));
      for (int i = 0; i < sheet.getNumMergedRegions(); i++)
        for (int j = i + 1; j < sheet.getNumMergedRegions(); j++)
          assertThat(sheet.getMergedRegion(i).intersects(sheet.getMergedRegion(j))).isFalse();
      if (ordinary == 90) assertThat(sheet.getRowBreaks()).isNotEmpty();
    }
  }

  @Test
  void largeAmountsAreExactTextWhileNullZeroAndSignedDifferencesRemainDistinct() throws Exception {
    var initial = model(1, 0, 4);
    var rows = new ArrayList<>(initial.rows());
    rows.set(
        0,
        rows.getFirst().toBuilder()
            .unitPrice("999999999999.999999")
            .amount("999999999999.999999")
            .build());
    var m =
        new JjbReadModel(
            initial.identity(),
            initial.header(),
            rows,
            List.of(),
            new JjbReadModel.Totals("999999999999.999999", "0.000000", null),
            List.of("历史未记录"));
    try (var wb = render(m)) {
      Sheet s = wb.getSheetAt(0);
      Cell c = find(s, "999999999999.999999");
      assertThat(c.getCellType()).isEqualTo(CellType.STRING);
      amount(s.getRow(find(s, "不含税总成本1").getRowIndex()).getCell(5), "999999999999.999999");
      amount(s.getRow(find(s, "材料成本差异").getRowIndex()).getCell(5), "0.000000");
      amount(s.getRow(find(s, "不含税总成本2").getRowIndex()).getCell(5), null);
    }
  }

  @Test
  void longDescriptionsStayInCommentsWithoutExpandingTheTemplateRows() throws Exception {
    var base = model(1, 1, 0);
    var rows = new ArrayList<>(base.rows());
    String note = "长说明甲乙丙丁\n".repeat(200);
    rows.set(0, rows.getFirst().toBuilder().remark(note).build());
    var m =
        new JjbReadModel(base.identity(), base.header(), rows, List.of(), base.totals(), List.of());
    try (var wb = render(m)) {
      Sheet s = wb.getSheetAt(0);
      int first = find(s, rows.getFirst().code()).getRowIndex();
      Row row = s.getRow(first);
      assertThat(row.getCell(13).getCellComment().getString().getString()).contains(note);
      assertThat(row.getHeightInPoints()).isEqualTo(18);
      assertThat(s.getRow(first + 1).getCell(0).getStringCellValue()).isEqualTo("三项费用");
    }
  }

  @Test
  void matchesTheReferenceGridAndMergedCostBlocks() throws Exception {
    try (var wb = render(model(2, 1, 1))) {
      Sheet s = wb.getSheetAt(0);
      assertThat(s.getRow(0).getHeightInPoints()).isEqualTo(43);
      assertThat(s.getRow(5).getCell(0).getStringCellValue()).isEqualTo("子件编码");
      assertThat(s.getRow(5).getCell(14).getStringCellValue()).isEqualTo("备注-CMS\n来源信息");
      assertThat(s.getColumnWidth(0)).isEqualTo((int) Math.round(15.9166666666667 * 256));
      assertThat(s.getColumnWidth(14)).isEqualTo((int) Math.round(13.5833333333333 * 256));
      assertThat(s.getRow(6).getHeightInPoints()).isEqualTo(18);
      var merges = s.getMergedRegions().stream().map(Object::toString).toList();
      int three = find(s, "三项费用").getRowIndex();
      int fees = find(s, "新品费用").getRowIndex();
      int total = find(s, "不含税总成本1").getRowIndex();
      assertThat(merges).contains("org.apache.poi.ss.util.CellRangeAddress [B2:C2]");
      assertThat(s.getMergedRegions()).anyMatch(r -> r.getFirstRow() == three && r.getLastRow() == three + 2 && r.getFirstColumn() == 0 && r.getLastColumn() == 0);
      assertThat(s.getMergedRegions()).anyMatch(r -> r.getFirstRow() == fees && r.getLastRow() == fees + 3 && r.getFirstColumn() == 0 && r.getLastColumn() == 0);
      assertThat(s.getMergedRegions()).anyMatch(r -> r.getFirstRow() == total && r.getFirstColumn() == 0 && r.getLastColumn() == 2);
      assertThat(s.getRow(find(s, "模具费").getRowIndex()).getCell(2).getStringCellValue()).isEqualTo("模具费");
      assertThat(find(s, "产品属性").getColumnIndex()).isZero();
      assertThat(s.getRow(5).getCell(6).getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
    }
  }

  @Test
  void requestHeaderValuesHaveConsistentFontAndExplicitCopperUnits() throws Exception {
    var base = model(1, 0, 0);
    var h = base.header();
    var header = new JjbReadModel.Header(h.productName(), h.productModel(), h.customerName(),
        h.customerCode(), h.customerDrawing(), h.descriptionBasis(), h.financeCuPrice(), h.customerCuPrice(),
        h.copperUnit(), h.productProperty(), h.coefficient(), Map.of("businessType", "批量品", "copperPrice", "100000.000000", "customerCuPrice", "100.000000"));
    try (var wb = render(new JjbReadModel(base.identity(), header, base.rows(), base.monthlyPrices(), base.totals(), base.warnings()))) {
      var s = wb.getSheetAt(0);
      assertThat(wb.getFontAt(s.getRow(3).getCell(6).getCellStyle().getFontIndexAsInt()).getFontHeightInPoints()).isEqualTo((short) 11);
      assertThat(s.getRow(1).getCell(12).getCellComment().getString().getString()).contains("元/吨");
      amount(s.getRow(1).getCell(12), "100000.000000");
      amount(s.getRow(4).getCell(8), "100.000000");
    }
  }

  @Test
  void twoLineExplanationCodesRemainVisibleInTheirOwnRows() throws Exception {
    var base = model(1, 0, 0);
    var rows = new ArrayList<>(base.rows());
    rows.add(1, rows.getFirst().toBuilder().key("child").kind("EXPLANATION")
        .code("201500256\n【301220212】").amount(null).build());
    try (var wb = render(new JjbReadModel(base.identity(), base.header(), rows, base.monthlyPrices(), base.totals(), base.warnings()))) {
      var s = wb.getSheetAt(0);
      var code = find(s, "201500256\n【301220212】");
      assertThat(s.getRow(code.getRowIndex()).getHeightInPoints()).isGreaterThanOrEqualTo(28);
      assertThat(code.getCellStyle().getWrapText()).isTrue();
      assertThat(s.getRow(6).getHeightInPoints()).isEqualTo(18);
    }
  }

  @Test
  void untrustedSourceTextIsStoredAsTextNeverAsExcelFormula() throws Exception {
    var base = model(1, 0, 0);
    var rows = new ArrayList<>(base.rows());
    rows.set(0, rows.getFirst().toBuilder().name("=HYPERLINK(\"bad\")").build());
    try (var wb =
        render(
            new JjbReadModel(
                base.identity(), base.header(), rows, List.of(), base.totals(), List.of()))) {
      assertThat(find(wb.getSheetAt(0), "=HYPERLINK(\"bad\")").getCellType())
          .isEqualTo(CellType.STRING);
    }
  }
}
