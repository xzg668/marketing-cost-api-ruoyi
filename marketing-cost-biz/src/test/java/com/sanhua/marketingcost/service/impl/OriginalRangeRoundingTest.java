package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.OriginalRuleSignature;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class OriginalRangeRoundingTest {
  private final OriginalRangeWorkbookParser parser = new OriginalRangeWorkbookParser();
  private final OriginalRangeRuleCalculator calculator = new OriginalRangeRuleCalculator();

  @Test
  void explicitGrossIncrementIsAppliedBeforeTaxAndFinalRounding() throws Exception {
    try (var book = workbook(true, true)) {
      var rule = parse(book);
      assertThat(calculator.select(rule, new BigDecimal("120000")).priceExcludingTax())
          .isEqualByComparingTo("0.3177");
      assertThat(calculator.select(rule, new BigDecimal("111000")).sourcePrice())
          .isEqualByComparingTo("0.3018");
      assertThat(calculator.select(rule, new BigDecimal("120001")).priceExcludingTax())
          .isEqualByComparingTo("0.3195");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("120000.5")))
          .hasMessageContaining("缺档");
      assertThatThrownBy(() -> calculator.select(rule, new BigDecimal("100000")))
          .hasMessageContaining("缺少行情档位");
    }
  }

  @Test
  void completeUnroundedFormulaSeriesCanProveTheStepWithoutANote() throws Exception {
    try (var book = workbook(false, false)) {
      assertThat(calculator.select(parse(book), new BigDecimal("120000")).sourcePrice())
          .isEqualByComparingTo("0.3177");
    }
  }

  @Test
  void explicitLastFormulaCanProveHighTailWithoutBorrowingAnotherMaterialsNote() throws Exception {
    try (var book = workbook(true, false)) {
      assertThat(calculator.select(parse(book), new BigDecimal("120000")).sourcePrice())
          .isEqualByComparingTo("0.3177");
    }
  }

  @Test
  void notesUsingChangesOrAdjustmentsAndDifferentStepSizesAreRecognized() throws Exception {
    for (String wording : new String[] {"变化", "上调", "上涨或者下浮"}) {
      try (var book = workbook(true, true)) {
        var sheet = book.getSheet("矩阵");
        sheet.getRow(3).getCell(16).setCellValue("铜价每" + wording + "1000元/吨，引线价格变化0.002元/米");
        // A constant final gross cell: the note and stable pre-rounding tail still prove the rule.
        number(sheet.getRow(3).getCell(14), .341);
        assertThat(calculator.select(parse(book), new BigDecimal("120000")).sourcePrice())
            .isEqualByComparingTo("0.3177");
      }
    }
  }

  @Test
  void roundedConstantsWithoutTheirFormulaAreNotGuessed() throws Exception {
    try (var book = workbook(false, false)) {
      var data = book.getSheet("矩阵").getRow(3);
      for (int i = 0; i < 6; i++) number(data.getCell(3 + 2*i), .2929 + .0018*i - .0001*(i/2));
      assertThat(calculator.extrapolation(parse(book))).isNull();
    }
  }

  @Test
  void wrongMaterialIncrementCannotAuthorizeAnIrregularTail() throws Exception {
    try (var book = workbook(true, true)) {
      var data = book.getSheet("矩阵").getRow(3);
      number(data.getCell(14), .341);
      data.getCell(15).setCellValue(.003);
      data.getCell(16).setCellValue("铜价每变化1000元/吨，引线价格变化0.003元/米");
      assertThat(calculator.extrapolation(parse(book))).isNull();
    }
  }

  @Test
  void anotherRowsNoteDoesNotProveThisRowsTail() throws Exception {
    try (var book = workbook(true, false)) {
      var sheet = book.getSheet("矩阵");
      number(sheet.getRow(3).getCell(14), .341);
      sheet.createRow(4).createCell(16).setCellValue("铜价每变化1000元/吨，引线价格变化0.002元/米");
      assertThat(calculator.extrapolation(parse(book))).isNull();
    }
  }

  @Test
  void genuineBrokenHighBandStillFails() throws Exception {
    try (var book = workbook(false, true)) {
      book.getSheet("矩阵").getRow(3).getCell(13).setBlank();
      assertThatThrownBy(() -> calculator.select(parse(book), new BigDecimal("120000")))
          .hasMessageContaining("无可靠");
      assertThatThrownBy(() -> calculator.select(parse(book), new BigDecimal("111000")))
          .hasMessageContaining("价格不可用");
    }
  }

  @Test
  void equalRoundedPricesMustNotBecomeAZeroIncrement() throws Exception {
    try (var book = workbook(false, false)) {
      var data = book.getSheet("矩阵").getRow(3);
      for (int i = 0; i < 6; i++) {
        number(data.getCell(4 + 2*i), 1.000001 + .000001*i);
        data.getCell(3 + 2*i).setCellFormula("ROUND(" + col(4 + 2*i) + "4,4)");
      }
      assertThat(calculator.select(parse(book), new BigDecimal("211000")).sourcePrice())
          .isEqualByComparingTo("1.0001");
    }
  }

  @Test
  void calculationEvidenceSurvivesJsonAndRuleSignatureRetainsRounding() throws Exception {
    try (var book = workbook(false, true)) {
      var rule = parse(book);
      var result = calculator.select(rule, new BigDecimal("120000"));
      var json = new ObjectMapper();
      var saved = json.readValue(json.writeValueAsString(result), OriginalRangeRuleCalculator.Result.class);
      assertThat(saved).isEqualTo(result);
      var signature = new OriginalRuleSignature(json, calculator);
      assertThat(signature.describe(rule)).contains("ROUND");
      book.getSheet("矩阵").getRow(3).getCell(13).setCellFormula("ROUND(O4/1.13,5)");
      assertThat(signature.range(parse(book))).isNotEqualTo(signature.range(rule));
    }
  }

  @Test
  void eachTablesOwnFactorStepIsUsedAndExistingBandsStayExact() throws Exception {
    for (int step : new int[] {3000, 5000}) {
      try (var book = workbook(false, false)) {
        for (int i=0; i<6; i++) book.getSheet("矩阵").getRow(1).getCell(3+2*i)
            .setCellValue((80001+i*step)+"元/吨-"+(80000+(i+1)*step)+"元/吨");
        var rule=parse(book);
        for (var band:rule.bands()) assertThat(calculator.select(rule,band.bounds().high()).sourcePrice())
            .isEqualByComparingTo(band.price());
        assertThat(calculator.select(rule,new BigDecimal(80000+15*step)).sourcePrice())
            .isEqualByComparingTo("0.3177");
      }
    }
  }

  @Test
  void oldSavedCalculationWithoutRoundingFieldRemainsReadable() throws Exception {
    try(var book=workbook(false,false)) {
      var json=new ObjectMapper();
      var result=calculator.select(parse(book),new BigDecimal("120000"));
      var tree=json.valueToTree(result);
      ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("extrapolation")).remove("rounding");
      var restored=json.treeToValue(tree,OriginalRangeRuleCalculator.Result.class);
      assertThat(restored.extrapolation().rounding()).isNull();
      assertThat(restored.priceExcludingTax()).isEqualByComparingTo(result.priceExcludingTax());
    }
  }

  @Test
  void nestedRoundingIsNotMistakenForAFinalLinearRounding() throws Exception {
    try(var book=workbook(false,false)) {
      book.getSheet("矩阵").getRow(3).getCell(13).setCellFormula("ROUND(ROUND(O4,3)/1.13,4)");
      assertThat(calculator.extrapolation(parse(book))).isNull();
    }
  }

  private OriginalRangeWorkbookParser.Rule parse(XSSFWorkbook book) throws Exception {
    book.getCreationHelper().createFormulaEvaluator().evaluateAll();
    var bytes = new ByteArrayOutputStream(); book.write(bytes);
    var parsed = parser.parse(bytes.toByteArray(), "区间原稿.xlsx", "COMMERCIAL");
    assertThat(parsed.errors()).isEmpty();
    return parsed.rules().getFirst();
  }

  private XSSFWorkbook workbook(boolean badLow, boolean note) {
    var book = new XSSFWorkbook();
    var main = book.createSheet("ImportData");
    var head = main.createRow(0);
    String[] labels = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
    for (int i = 0; i < labels.length; i++) head.createCell(i).setCellValue(labels[i]);
    var m = main.createRow(1);
    String[] values = {"210", "201850040", "测试供应商有限公司", "S01", "SPEC", "米"};
    for (int i = 0; i < values.length; i++) m.createCell(i).setCellValue(values[i]);
    m.createCell(6).setCellValue(.3); m.createCell(7).setCellValue(false);
    var sheet = book.createSheet("矩阵");
    sheet.createRow(0).createCell(0).setCellValue("铜价区间表");
    var h = sheet.createRow(1); h.createCell(0).setCellValue("U9代码");
    h.createCell(1).setCellValue("规格"); h.createCell(2).setCellValue("供应商名称");
    var tax = sheet.createRow(2); var data = sheet.createRow(3);
    data.createCell(0).setCellValue("201850040"); data.createCell(1).setCellValue("SPEC");
    data.createCell(2).setCellValue("测试供应商");
    for (int i = 0; i < 6; i++) {
      int c = 3 + 2*i;
      h.createCell(c).setCellValue((105001+1000*i)+"元/吨-"+(106000+1000*i)+"元/吨");
      tax.createCell(c).setCellValue("不含税元/米"); tax.createCell(c+1).setCellValue("含13%税价");
      data.createCell(c+1).setCellValue(.331 + .002*i);
      data.createCell(c).setCellFormula((i>=4?"ROUND(":"")+col(c+1)+"4/1.13"+(i>=4?",4)":""));
    }
    if (badLow) data.getCell(4).setCellValue(.25);
    data.getCell(14).setCellFormula("M4+P4");
    data.createCell(15).setCellValue(.002);
    if (note) data.createCell(16).setCellValue("铜价每上调或者下降1000元/吨，引线价格上调或下降0.002元/米");
    return book;
  }
  private void number(org.apache.poi.ss.usermodel.Cell cell, double value) {cell.setBlank(); cell.setCellValue(value);}
  private String col(int value) {return org.apache.poi.ss.util.CellReference.convertNumToColString(value);}
}
