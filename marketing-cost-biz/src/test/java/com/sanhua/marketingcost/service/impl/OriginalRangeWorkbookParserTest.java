package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class OriginalRangeWorkbookParserTest {
  private final OriginalRangeWorkbookParser parser = new OriginalRangeWorkbookParser();
  private final OriginalRangeRuleCalculator calculator = new OriginalRangeRuleCalculator();

  @Test
  void actualTaxFormulaAndUnderlyingPrecisionArePreserved() throws Exception {
    try (var book = workbook("90000-94999", "95000-99999", "100000-104999", "105000-109999")) {
      var row = book.getSheet("矩阵").getRow(3);
      row.getCell(4).setCellValue(0.123456789123);
      row.getCell(3).setCellFormula("E4/1.13");
      var style = book.createCellStyle();
      style.setDataFormat(book.createDataFormat().getFormat("0.00"));
      row.getCell(3).setCellStyle(style);
      var rule = parsed(book).rules().getFirst();
      var result = calculator.select(rule, new BigDecimal("90000"));
      assertThat(result.priceExcludingTax())
          .isEqualByComparingTo(
              new BigDecimal("0.123456789123")
                  .divide(new BigDecimal("1.13"), java.math.MathContext.DECIMAL128));
      assertThat(result.sourceBand().compiled().sources()).hasSize(2);
      assertThat(result.sourceBand().source().formula()).isEqualTo("E4/1.13");
      assertThat(result.sourceBand().source().numberFormat()).isEqualTo("0.00");
      assertThat(rule.identity().unit()).isEqualTo("只");
    }
  }

  @Test
  void closedAndHalfOpenBoundariesRemainDifferent() throws Exception {
    try (var book = workbook("89001-90000", "90001-91000", "91001-92000", "92001-93000")) {
      var rule = parsed(book).rules().getFirst();
      assertThat(calculator.select(rule, new BigDecimal("90000")).sourcePrice())
          .isEqualByComparingTo("1");
      assertThat(calculator.select(rule, new BigDecimal("90001")).sourcePrice())
          .isEqualByComparingTo("2");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("90000.5")))
          .hasMessageContaining("缺少行情档位");
    }
    try (var book =
        workbook(
            "90元/KG≤铜价<100元/KG",
            "100元/KG≤铜价<110元/KG",
            "110元/KG≤铜价<120元/KG",
            "120元/KG≤铜价<130元/KG")) {
      var rule = parsed(book).rules().getFirst();
      assertThat(calculator.select(rule, new BigDecimal("99.999")).sourcePrice())
          .isEqualByComparingTo("1");
      assertThat(calculator.select(rule, new BigDecimal("100")).sourcePrice())
          .isEqualByComparingTo("2");
      assertThat(calculator.select(rule, new BigDecimal("130")).sourcePrice())
          .isEqualByComparingTo("5");
    }
  }

  @Test
  void overlapCannotBeResolvedByTakingTheFirstBand() throws Exception {
    try (var book = workbook("55001-60000", "6000-65000", "65001-70000", "70001-75000")) {
      var rule = parsed(book).rules().getFirst();
      assertThat(rule.bands().get(1).bounds().low()).isEqualByComparingTo("6000");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("60000")))
          .hasMessageContaining("重叠档");
      assertThat(calculator.extrapolation(rule)).isNull();
    }
  }

  @Test
  void missingAndBrokenBandsAreRetainedAndDoNotBecomeZero() throws Exception {
    try (var book = workbook("90000-94999", "95000-99999", "100000-104999", "105000-109999")) {
      var row = book.getSheet("矩阵").getRow(3);
      row.getCell(3).setBlank();
      row.getCell(5).setCellFormula("G4");
      row.getCell(6).setCellFormula("F4");
      var rule = parsed(book).rules().getFirst();
      assertThat(rule.bands()).hasSize(4);
      assertThat(rule.bands().get(0).price()).isNull();
      assertThat(rule.bands().get(1).problem()).contains("循环引用");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("90000")))
          .hasMessageContaining("价格不可用");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("95000")))
          .hasMessageContaining("价格不可用");
      assertThat(calculator.select(rule, new BigDecimal("100000")).sourcePrice())
          .isEqualByComparingTo("3");
    }
  }

  @Test
  void upwardProofHasNoArtificialCeilingAndDoesNotFillFractionalGaps() throws Exception {
    try (var book = workbook("90000-94999", "95000-99999", "100000-104999", "105000-109999")) {
      var rule = parsed(book).rules().getFirst();
      var result = calculator.select(rule, new BigDecimal("150000"));
      assertThat(result.sourcePrice()).isEqualByComparingTo("13");
      assertThat(result.extrapolation().factorStep()).isEqualByComparingTo("5000");
      assertThat(result.extrapolation().priceStep()).isEqualByComparingTo("1");
      assertThat(result.extrapolation().evidence()).hasSize(4);
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("149999.5")))
          .hasMessageContaining("缺档");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("89999")))
          .hasMessageContaining("缺少行情档位");
      book.getSheet("矩阵").getRow(3).getCell(7).setCellValue(3.01);
      var unstable = parsed(book).rules().getFirst();
      assertThat(calculator.extrapolation(unstable)).isNull();
      assertThatThrownBy(() -> calculator.select(unstable, new BigDecimal("150000")))
          .hasMessageContaining("无可靠");
    }
  }

  @Test
  void sourceUnitTypoNeedsAnExplicitMatchingTonEndpointAndRemainsTraceable() throws Exception {
    try (var book =
        workbook(
            "45001元/Tg--50000元/T",
            "50001元/T--55000元/T",
            "55001元/T--60000元/T",
            "60001元/T--65000元/T")) {
      var parsed = parsed(book);
      assertThat(parsed.errors()).isEmpty();
      assertThat(parsed.rules().getFirst().bands().getFirst().header().rawValue())
          .isEqualTo("45001元/Tg--50000元/T");
      assertThat(
              calculator.select(parsed.rules().getFirst(), new BigDecimal("50000")).sourcePrice())
          .isEqualByComparingTo("1");
      book.getSheet("矩阵").getRow(1).getCell(3).setCellValue("45001元/未知--50000元/T");
      assertThat(parsed(book).errors().getFirst().message()).contains("无法识别原始区间边界");
      book.getSheet("矩阵").getRow(1).getCell(3).setCellValue("45001元/KG--50000元/T");
      assertThat(parsed(book).errors().getFirst().message()).contains("两端因素单位不一致");
    }
  }

  @Test
  void stableHighBandsNeedBothSourceStepAndMaterialDeltaWhenLowHeaderIsBroken() throws Exception {
    try (var book =
        workbook(
            "55001-60000",
            "6000-65000",
            "65001-70000",
            "70001-75000",
            "75001-80000",
            "80001-85000",
            "85001-90000")) {
      assertThat(calculator.extrapolation(parsed(book).rules().getFirst())).isNull();
      var sheet = book.getSheet("矩阵");
      sheet.createRow(6).createCell(0).setCellValue("每上涨5000元/吨调整一次");
      assertThat(calculator.extrapolation(parsed(book).rules().getFirst())).isNull();
      sheet.getRow(3).createCell(18).setCellValue(1.13);
      var proof = calculator.select(parsed(book).rules().getFirst(), new BigDecimal("130000"));
      assertThat(proof.sourcePrice()).isEqualByComparingTo("15");
      assertThat(proof.extrapolation().basis()).isEqualTo("SOURCE_NOTE_AND_STABLE_HIGH_BANDS");
      sheet.getRow(3).getCell(18).setCellValue(2.26);
      assertThat(calculator.extrapolation(parsed(book).rules().getFirst())).isNull();
    }
  }

  @Test
  void explicitSupplierMismatchAndDuplicateMatricesAreRejected() throws Exception {
    try (var book = workbook("90000-94999", "95000-99999", "100000-104999", "105000-109999")) {
      book.getSheet("矩阵").getRow(3).getCell(2).setCellValue("其他供应商");
      assertThat(parsed(book).errors()).hasSize(1);
      assertThat(parsed(book).rules()).isEmpty();
      book.getSheet("矩阵").getRow(3).getCell(2).setCellValue("测试供应商");
      book.cloneSheet(book.getSheetIndex("矩阵"));
      assertThat(parsed(book).errors().getFirst().message()).contains("候选数=2");
    }
  }

  private OriginalRangeWorkbookParser.ParsedWorkbook parsed(XSSFWorkbook book) throws Exception {
    // 不重算循环档，保持原稿的实际公式；其余测试只有直接常数或简单的除税公式。
    var formula = book.getSheet("矩阵").getRow(3).getCell(3);
    if (formula.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
      book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
    var out = new ByteArrayOutputStream();
    book.write(out);
    return parser.parse(out.toByteArray(), "完整原稿.xlsx", "COMMERCIAL");
  }

  private XSSFWorkbook workbook(String... headers) {
    var book = new XSSFWorkbook();
    var main = book.createSheet("ImportData");
    var header = main.createRow(0);
    String[] labels = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
    for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
    var row = main.createRow(1);
    String[] values = {"210", "M01", "测试供应商有限公司", "S01", "SPEC", "只"};
    for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    row.createCell(6).setCellValue(99);
    row.createCell(7).setCellValue(false);
    var sheet = book.createSheet("矩阵");
    sheet.createRow(0).createCell(0).setCellValue("铜价区间表");
    var h = sheet.createRow(1);
    h.createCell(0).setCellValue("U9代码");
    h.createCell(1).setCellValue("图号");
    h.createCell(2).setCellValue("供应商名称");
    // Matrix header, tax labels, and data must occupy separate rows.
    var tax = sheet.createRow(2);
    var data = sheet.createRow(3);
    data.createCell(0).setCellValue("M01");
    data.createCell(1).setCellValue("SPEC");
    data.createCell(2).setCellValue("测试供应商");
    for (int i = 0; i < headers.length; i++) {
      int c = 3 + i * 2;
      h.createCell(c).setCellValue(headers[i]);
      tax.createCell(c).setCellValue("不含税元/米");
      tax.createCell(c + 1).setCellValue("含13%税价");
      data.createCell(c).setCellValue(i + 1);
      data.createCell(c + 1).setCellValue((i + 1) * 1.13);
    }
    return book;
  }
}
