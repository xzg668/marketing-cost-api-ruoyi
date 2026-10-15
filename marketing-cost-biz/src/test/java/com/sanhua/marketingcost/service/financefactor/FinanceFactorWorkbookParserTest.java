package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;

class FinanceFactorWorkbookParserTest {
  private final FinanceFactorWorkbookParser parser =
      new FinanceFactorWorkbookParser(
          new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));

  @Test
  void readsAllFourRealCommercialSchemesAndPreservesSourcesWithoutGuessingFromColors()
      throws Exception {
    byte[] original = fixture("commercial");
    var result = parser.parse(original, "财务基价.xlsx", "210", null);
    assertThat(result.fileSha256())
        .isEqualTo("31c2e7328e7ff5150feb56ae7839df9de47554b9f88505f5d6bc88acdc577cc4");
    assertThat(result.problems()).isEmpty();
    assertThat(result.schemes())
        .extracting(FinanceFactorWorkbook.Scheme::code)
        .containsExactly("CU_90000", "CU_100000", "CU_110000", "CU_120000");
    assertThat(result.values()).hasSize(60);
    assertThat(result.values().stream().map(Value::canonicalKey).distinct()).hasSize(14);
    Value cu = value(result, "AVG|1#CU", "CU_90000", Condition.GENERAL);
    assertThat(cu.price()).isEqualByComparingTo("90");
    assertThat(cu.cellRef()).isEqualTo("G3");
    assertThat(cu.unit()).isEqualTo("CNY/KG");
    assertThat(
            result.values().stream()
                .filter(
                    v -> v.canonicalKey().equals("AVG|1#CU") && v.schemeCode().equals("CU_90000")))
        .hasSize(2);
    assertThat(value(result, "AVG|PCU", "CU_90000", Condition.GENERAL).price())
        .isEqualByComparingTo("115.979");
    var aluminum = value(result, "AVG|A00", "CU_90000", Condition.GENERAL);
    assertThat(aluminum.price()).isEqualByComparingTo("24.260");
    assertThat(aluminum.cellRef()).isEqualTo("E5");
    assertThat(aluminum.conversionBasis()).isEqualTo("CURRENT_WORKBOOK_PREVIOUS_MONTH_AVERAGE");
    var zinc = value(result, "AVG|1#ZN", "CU_90000", Condition.GENERAL);
    assertThat(zinc.price()).isEqualByComparingTo("21.6362912421045");
    assertThat(zinc.sourceFormula()).isEqualTo("E4*(1+$F$3)");
    assertThat(zinc.numberFormat()).contains("0.000");
    assertThat(value(result, "AVG|1#NI", "CU_90000", Condition.GENERAL).unit())
        .isEqualTo("CNY/TON");
    assertThat(value(result, "AVG|黄金AU9999", "CU_90000", Condition.GENERAL).unit())
        .isEqualTo("CNY/G");
    assertThat(value(result, "AVG|氧化钕", "CU_90000", Condition.GENERAL).price())
        .isEqualByComparingTo("364.187");
    assertThat(FinanceFactorWorkbookParser.sha256(original)).isEqualTo(result.fileSha256());
  }

  @Test
  void readsBothBoardConditionsAndNeverImportsCustomerInstructionsAsZero() throws Exception {
    var result = parser.parse(fixture("plate"), "板换.xlsx", "220", null);
    assertThat(result.fileSha256())
        .isEqualTo("d3d364fd006b83e9db8f2c4903791763719a534615bd95df24f139de1b56442c");
    assertThat(result.sheetName()).isEqualTo("板换");
    assertThat(result.problems()).isEmpty();
    assertThat(result.values()).hasSize(16);
    assertThat(result.values()).allMatch(v -> v.columnNumber() == 5 || v.columnNumber() == 6);
    var steel = value(result, "AVG|304/2B/1219", "BOARD", Condition.NON_TENDER);
    assertThat(steel.originalValue()).isEqualByComparingTo("20000");
    assertThat(steel.sourceValueText()).isEqualTo("20000");
    assertThat(steel.price()).isEqualByComparingTo("20");
    assertThat(steel.unit()).isEqualTo("CNY/KG");
    assertThat(steel.conversionBasis()).isEqualTo("BOARD_FIXED_SUS_VALUE_DIVIDE_1000");
    assertThat(value(result, "AVG|304/2B/1219", "BOARD", Condition.TENDER).price())
        .isEqualByComparingTo("15");
    assertThat(value(result, "AVG|316L/2B", "BOARD", Condition.TENDER).price())
        .isEqualByComparingTo("32.5");
    assertThat(value(result, "AVG|1#AG", "BOARD", Condition.TENDER).price())
        .isEqualByComparingTo("15772.86");
    assertThat(value(result, "AVG|1#ZN", "BOARD", Condition.NON_TENDER).price())
        .isEqualByComparingTo("21.636");
    assertThat(result.values().stream().map(Value::canonicalKey).distinct()).hasSize(8);
  }

  @Test
  void boardMissingFixedColumnCannotSilentlyUseHistoricalAverage() throws Exception {
    var result =
        parser.parse(
            modified("plate", wb -> wb.getSheet("板换").getRow(1).getCell(4).setBlank()),
            "test.xlsx",
            "220",
            null);
    assertThat(result.problems()).anyMatch(problem -> problem.cellRef().equals("E2"));
    assertThat(result.values())
        .noneMatch(
            value ->
                value.condition() == Condition.NON_TENDER
                    && value.canonicalKey().equals("AVG|304/2B/1219"));
    assertThat(value(result, "AVG|304/2B/1219", "BOARD", Condition.TENDER).price())
        .isEqualByComparingTo("15");
  }

  @Test
  void alreadyNormalizedBoardSteelIsNotConvertedTwice() throws Exception {
    byte[] bytes =
        modified(
            "plate",
            wb -> {
              set(wb, "板换", "E2", 20);
              set(wb, "板换", "F2", 15);
              set(wb, "板换", "E3", 32.5);
            });
    var result = parser.parse(bytes, "任意名称.xlsx", "220", null);
    assertThat(result.problems()).isEmpty();
    var value = value(result, "AVG|304/2B/1219", "BOARD", Condition.NON_TENDER);
    assertThat(value.price()).isEqualByComparingTo("20");
    assertThat(value.conversionBasis()).isEqualTo("BOARD_FIXED_SUS_VALUE_ALREADY_PER_KG");
    assertThat(value(result, "AVG|316L/2B", "BOARD", Condition.NON_TENDER).price())
        .isEqualByComparingTo("32.5");
  }

  @Test
  void zeroIsAnExplicitValueAndMissingDoesNotBecomeZero() throws Exception {
    var explicitZero =
        parser.parse(
            modified("commercial", wb -> set(wb, wb.getSheetName(0), "G5", 0)),
            "test.xlsx",
            "210",
            null);
    assertThat(value(explicitZero, "AVG|A00", "CU_90000", Condition.GENERAL).price()).isZero();
    assertThat(value(explicitZero, "AVG|A00", "CU_90000", Condition.GENERAL).cellRef())
        .isEqualTo("G5");
    var missing =
        parser.parse(
            modified("commercial", wb -> wb.getSheetAt(0).getRow(4).getCell(4).setBlank()),
            "test.xlsx",
            "210",
            null);
    assertThat(missing.problems())
        .hasSize(4)
        .allMatch(p -> p.cellRef().equals("E5") && p.message().contains("缺值"));
    assertThat(missing.values()).noneMatch(v -> v.canonicalKey().equals("AVG|A00"));
  }

  @Test
  void conflictingCopperAliasValuesAreReportedWithBothCells() throws Exception {
    var result =
        parser.parse(
            modified("commercial", wb -> set(wb, wb.getSheetName(0), "G7", 99)),
            "test.xlsx",
            "210",
            null);
    assertThat(result.problems()).hasSize(1);
    assertThat(result.problems().getFirst().cellRef()).isEqualTo("G7");
    assertThat(result.problems().getFirst().message()).contains("G3", "AVG|1#CU");
  }

  @Test
  void reevaluatesSelectedFormulasAndRejectsFormulaErrors() throws Exception {
    var result =
        parser.parse(
            modified(
                "commercial", wb -> wb.getSheetAt(0).getRow(3).getCell(6).setCellFormula("1/0")),
            "test.xlsx",
            "210",
            null);
    assertThat(result.problems())
        .anyMatch(p -> p.cellRef().equals("G4") && p.message().contains("公式"));
    assertThat(result.values()).noneMatch(v -> v.cellRef().equals("G4"));
  }

  @Test
  void rejectsUnknownUnitsInsteadOfGuessingByMagnitude() throws Exception {
    var result =
        parser.parse(
            modified(
                "commercial", wb -> wb.getSheetAt(0).getRow(12).getCell(3).setCellValue("未知单位")),
            "test.xlsx",
            "210",
            null);
    assertThat(result.problems()).hasSize(4).allMatch(p -> p.message().contains("未确认的因素单位"));
  }

  @Test
  void sheetNameAndFileMonthAreNotHardCodedAndAmbiguousSheetsRequireSelection() throws Exception {
    byte[] bytes =
        modified(
            "commercial",
            wb -> {
              wb.setSheetName(0, "2030年新方案");
              wb.cloneSheet(0);
            });
    assertThatThrownBy(() -> parser.parse(bytes, "2030年价格.xlsx", "210", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("唯一");
    assertThat(parser.parse(bytes, "2030年价格.xlsx", "210", "2030年新方案").problems()).isEmpty();
  }

  @Test
  void blankRowsDoNotSilentlyTruncateFollowingFactorsAndUnusedSheetErrorsAreIgnored()
      throws Exception {
    byte[] bytes =
        modified(
            "commercial",
            wb -> {
              var sheet = wb.getSheetAt(0);
              sheet.shiftRows(14, sheet.getLastRowNum(), 1);
              wb.createSheet("无关附表").createRow(0).createCell(0).setCellFormula("1/0");
            });
    var result = parser.parse(bytes, "test.xlsx", "210", null);
    assertThat(result.problems()).isEmpty();
    assertThat(result.values()).hasSize(60);
  }

  public static byte[] fixture(String name) throws IOException {
    try (var input =
        FinanceFactorWorkbookParserTest.class.getResourceAsStream(
            "/finance-factor/" + name + "-original.xlsx")) {
      if (input == null)
        throw new IOException("Missing committed financial workbook fixture: " + name);
      return input.readAllBytes();
    }
  }

  private static byte[] modified(String name, Consumer<Workbook> edit) throws Exception {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture(name)));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      edit.accept(workbook);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static void set(Workbook workbook, String sheet, String address, double number) {
    var ref = new CellReference(address);
    var row = workbook.getSheet(sheet).getRow(ref.getRow());
    var cell = row.getCell(ref.getCol());
    if (cell == null) cell = row.createCell(ref.getCol());
    cell.setBlank();
    cell.setCellValue(number);
  }

  private static Value value(
      FinanceFactorWorkbook result, String key, String scheme, Condition condition) {
    return result.values().stream()
        .filter(
            v ->
                v.canonicalKey().equals(key)
                    && v.schemeCode().equals(scheme)
                    && v.condition() == condition)
        .findFirst()
        .orElseThrow();
  }
}
