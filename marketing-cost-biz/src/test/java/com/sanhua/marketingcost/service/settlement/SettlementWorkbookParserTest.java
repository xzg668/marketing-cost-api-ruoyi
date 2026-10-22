package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SettlementWorkbookParserTest {
  @Test void finalWorkbookPreservesAll46RowsAndDistinguishesRoundedFormulaFromHouseholdGivenPrice() throws Exception {
    byte[] bytes = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    var result = new SettlementWorkbookParser().parse(bytes, "final.xls");
    assertThat(result.sha256()).isEqualTo("cc01502625c678912275c7f2f4bba8d43426a8bbb264afa265fefa5944c5b239");
    assertThat(result.sourceRows()).hasSize(46);
    assertThat(result.rules()).hasSize(46);
    var linked = result.rules().stream().filter(r -> r.priceType() == SettlementPriceType.SETTLE_LINKED).toList();
    assertThat(linked).hasSize(11);
    assertThat(linked).allSatisfy(r -> {
      assertThat(r.formula().version()).isEqualTo("SETTLEMENT_CU_ZN_ROUND4_V2");
      assertThat(r.formula().expression()).startsWith("ROUND(").endsWith(", 4)");
      assertThat(r.sources().getFirst().resolvedSourceFormula()).startsWith("ROUND(");
    });
    assertThat(linked.stream().filter(r -> r.weights().originalZincG() == null)).hasSize(7);
    var valve = linked.stream().filter(r -> r.materialCode().equals("101303761")).findFirst().orElseThrow();
    assertThat(valve.givenPrice()).isEqualByComparingTo("8.7763");
    assertThat(valve.sources().getFirst().cells().get("L").number()).isEqualByComparingTo("8.7764");
    assertThat(result.referenceCu()).isEqualByComparingTo("108.352");
    assertThat(result.referenceZn()).isEqualByComparingTo("25.597");
  }

  @Test void roundedFormulaRejectsOtherPrecisionWrongRowsAndDifferentGrouping() throws Exception {
    byte[] original = getClass().getResourceAsStream("/settlement/final-20261021.xls").readAllBytes();
    for (String formula : java.util.List.of(
        "ROUND(F10+((($P$2-$O$2)*G10+($P$3-$O$3)*H10)/1000/1.13)*1.2,3)",
        "ROUND(F10+((($P$2-$O$2)*G14+($P$3-$O$3)*H10)/1000/1.13)*1.2,4)",
        "ROUND((F10+($P$2-$O$2)*G10+($P$3-$O$3)*H10)/1000/1.13*1.2,4)")) {
      try (var book = WorkbookFactory.create(new ByteArrayInputStream(original))) {
        book.getSheetAt(0).getRow(9).getCell(11).setCellFormula(formula);
        var output = new ByteArrayOutputStream(); book.write(output);
        assertThatThrownBy(() -> new SettlementWorkbookParser().parse(output.toByteArray(), "invalid.xls"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("L10").hasMessageContaining("公式");
      }
    }
  }

  @Test void actualXlsPreservesEverySourceAndSixGivenLinkedPrices() throws Exception {
    byte[] bytes;
    try (var file = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { bytes = file.readAllBytes(); }
    var result = new SettlementWorkbookParser().parse(bytes, "明细表导出_20261017.xls");
    assertThat(result.sourceRows()).hasSize(38);
    assertThat(result.rules()).hasSize(37);
    var linked = result.rules().stream().filter(r -> r.priceType() == SettlementPriceType.SETTLE_LINKED).toList();
    assertThat(linked).hasSize(6);
    assertThat(linked).extracting(r -> r.givenPrice().stripTrailingZeros().toPlainString())
        .containsExactly("4.841", "4.5114", "0.0266", "0.1099", "0.0461", "0.0827");
    assertThat(linked).allSatisfy(r -> {
      assertThat(r.formula().expression()).contains("copperWeightG", "zincWeightG", "taxDivisor");
      assertThat(r.weights().completeAndConfirmed()).isFalse();
      assertThat(r.sources().getFirst().formulaSourceCell()).isEqualTo("L10");
      assertThat(r.sources().getFirst().resolvedSourceFormula()).contains("$G$10", "$H$10");
    });
    assertThat(linked.getFirst().weights().originalCopperG()).isEqualByComparingTo("7.8");
    var duplicate = result.rules().stream().filter(r -> r.materialCode().equals("809633")).findFirst().orElseThrow();
    assertThat(duplicate.sources()).extracting(SettlementWorkbookParser.SourceRow::row).containsExactly(39, 40);
    assertThat(duplicate.remark()).contains("CA-SH-00420260801-0017", "CA-SH-00420260828-2824");
  }

  @Test void everySourceCellMatchesIndependentXlrdAuditIncludingUnroundedFormulaCaches() throws Exception {
    var result = new SettlementWorkbookParser().parse(original(), "原件.xls");
    var audit = new ObjectMapper().readTree(getClass().getResourceAsStream("/settlement/original-audit.json"));
    for (int r = 0; r < 38; r++) {
      var expected = audit.path("rows").get(r);
      var actual = result.sourceRows().get(r);
      assertThat(actual.row()).isEqualTo(expected.path("row").asInt());
      for (int c = 0; c < 13; c++) {
        var value = expected.path("values").get(c);
        var cell = actual.cells().get(CellReference.convertNumToColString(c));
        if (value.isNumber()) assertThat(cell.number()).as("row %s column %s", r + 4, c).isEqualByComparingTo(value.decimalValue());
        else assertThat(cell == null ? "" : cell.text()).as("row %s column %s", r + 4, c).isEqualTo(value.asText());
      }
    }
    assertThat(result.sha256()).isEqualTo("4d204983297cf79736b4f2063fa88831df100a0034acf2e97cc6e4bfc768a63e");
  }

  @Test void xlsxSupportsMovedHeaderLargeTextCodesExplicitZeroAndExtraRows() throws Exception {
    byte[] input = xlsx(sheet -> {
      sheet.getRow(3).getCell(0).setCellValue("1234567890123456789012345");
      sheet.getRow(11).getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(0);
      Row extra = sheet.createRow(41);
      for (int c = 0; c < 13; c++) copyCell(sheet.getRow(3).getCell(c), extra.createCell(c));
      extra.getCell(0).setCellValue("EXTRA-42");
      extra.getCell(5).setCellFormula("D42*E42");
      sheet.shiftRows(0, 41, 5);
    });
    var result = new SettlementWorkbookParser().parse(input, "移动表头.xlsx");
    assertThat(result.rules()).hasSize(38);
    assertThat(result.rules()).extracting(SettlementWorkbookParser.Rule::materialCode)
        .contains("1234567890123456789012345", "EXTRA-42");
    var row = result.rules().stream().filter(r -> r.materialCode().equals("203259534")).findFirst().orElseThrow();
    assertThat(row.weights().originalCopperG()).isZero();
    assertThat(row.weights().originalZincG()).isNull();
    assertThat(row.weights().completeAndConfirmed()).isFalse();
    assertThat(result.sourceRows()).hasSize(39);
  }

  @Test void unknownTypeMissingPriceAndDuplicateConflictPointToExactSourceCell() throws Exception {
    rejects(sheet -> sheet.getRow(3).getCell(10).setCellValue("猜测价"), "K4", "未知价格类型");
    rejects(sheet -> sheet.getRow(3).getCell(8).setBlank(), "I4", "非负数");
    rejects(sheet -> sheet.getRow(39).getCell(8).setCellValue(99), "A40", "冲突");
    rejects(sheet -> sheet.getRow(9).getCell(6).setCellValue(-1), "G10", "非负数");
  }

  @Test void differentFormulaGroupingAndWrongAbsoluteRowCannotSilentlyChangeCalculation() throws Exception {
    rejects(sheet -> sheet.getRow(9).getCell(11).setCellFormula("F10/0"), "L10", "公式");
    rejects(sheet -> sheet.getRow(9).getCell(11).setCellFormula("(F10+(($P$2-$O$2)*$G$10+($P$3-$O$3)*$H$10))/1000/1.13*1.2"), "L10", "公式");
    rejects(sheet -> sheet.getRow(11).getCell(11).setCellFormula("F12+(($P$2-$O$2)*$G$10+($P$3-$O$3)*$H$10)/1000/1.13*1.2"), "L12", "本行");
    rejects(sheet -> { sheet.getRow(9).getCell(11).setBlank(); sheet.getRow(9).getCell(11).setCellValue("联动公式相同"); }, "L10", "完整公式来源");
  }

  @Test void sourceTextPreservesOriginalSpacingWhileMaterialIdentityIsTrimmed() throws Exception {
    byte[] bytes = xlsx(sheet -> sheet.getRow(3).getCell(0).setCellValue(" 201809648 "));
    var result = new SettlementWorkbookParser().parse(bytes, "原文.xlsx");
    assertThat(result.sourceRows().getFirst().cells().get("A").text()).isEqualTo(" 201809648 ");
    assertThat(result.rules().getFirst().materialCode()).isEqualTo("201809648");
  }

  private void rejects(Consumer<Sheet> edit, String address, String reason) throws Exception {
    byte[] bytes = xlsx(edit);
    assertThatThrownBy(() -> new SettlementWorkbookParser().parse(bytes, "无效.xlsx"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无效.xlsx")
        .hasMessageContaining("零部件业务").hasMessageContaining(address).hasMessageContaining(reason);
  }

  private byte[] original() throws Exception {
    try (var file = getClass().getResourceAsStream("/settlement/original-20261017.xls")) { return file.readAllBytes(); }
  }

  private byte[] xlsx(Consumer<Sheet> edit) throws Exception {
    try (var source = WorkbookFactory.create(new ByteArrayInputStream(original())); var target = new XSSFWorkbook()) {
      Sheet sheet = target.createSheet("零部件业务");
      for (Row row : source.getSheetAt(0)) {
        Row copy = sheet.createRow(row.getRowNum());
        for (Cell cell : row) copyCell(cell, copy.createCell(cell.getColumnIndex()));
      }
      edit.accept(sheet);
      target.getCreationHelper().createFormulaEvaluator().evaluateAll();
      var bytes = new ByteArrayOutputStream(); target.write(bytes); return bytes.toByteArray();
    }
  }

  private void copyCell(Cell source, Cell target) {
    if (source == null) return;
    switch (source.getCellType()) {
      case STRING -> target.setCellValue(source.getStringCellValue());
      case NUMERIC -> target.setCellValue(source.getNumericCellValue());
      case FORMULA -> target.setCellFormula(source.getCellFormula());
      default -> target.setBlank();
    }
  }
}
