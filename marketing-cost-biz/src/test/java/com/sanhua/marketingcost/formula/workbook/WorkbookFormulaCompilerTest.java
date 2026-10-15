package com.sanhua.marketingcost.formula.workbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import java.math.BigDecimal;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WorkbookFormulaCompilerTest {
  @Test
  void compilesOnlyReachableCellsAndPreservesPrecisionFunctionsAndSourceFormats() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var price = book.createSheet("ImportData").createRow(0).createCell(0);
      var inputs = book.createSheet("因素 单价").createRow(0);
      inputs.createCell(0).setCellValue(109.114);
      inputs.createCell(1).setCellValue(1.23456);
      var formatted = book.createCellStyle();
      formatted.setDataFormat(book.createDataFormat().getFormat("0.000"));
      inputs.getCell(1).setCellStyle(formatted);
      inputs.createCell(2).setCellFormula("ROUND(A1+B1,4)/1.13");
      inputs.createCell(3).setCellFormula("1/0"); // 无关附表错误不阻断主价格。
      price.setCellFormula("'因素 单价'!C1+INT(-1.2)");
      var compiler =
          new WorkbookFormulaCompiler(book, "原稿.xlsx", Map.of(new Address("因素 单价", "A1"), "Cu"));
      var rule = compiler.compile(new Address("ImportData", "A1"));
      assertThat(rule.expression()).contains("ROUND(", "INT(", "1.23456");
      assertThat(rule.sources()).hasSize(4).noneMatch(s -> s.address().cell().equals("D1"));
      assertThat(rule.sources())
          .anyMatch(s -> "0.000".equals(s.numberFormat()) && "1.23456".equals(s.rawValue()));
      assertThat(rule.sourceInputs()).containsEntry("Cu", new BigDecimal("109.114"));
      var p0 = ExpressionEvaluator.evaluate(rule.expression(), Map.of("Cu", new BigDecimal("90")));
      var p1 = ExpressionEvaluator.evaluate(rule.expression(), Map.of("Cu", new BigDecimal("100")));
      var evaluator = book.getCreationHelper().createFormulaEvaluator();
      inputs.getCell(0).setCellValue(90);
      evaluator.clearAllCachedResultValues();
      assertThat(p0.doubleValue())
          .isCloseTo(
              evaluator.evaluate(price).getNumberValue(),
              org.assertj.core.data.Offset.offset(1e-12));
      inputs.getCell(0).setCellValue(100);
      evaluator.clearAllCachedResultValues();
      assertThat(p1.doubleValue())
          .isCloseTo(
              evaluator.evaluate(price).getNumberValue(),
              org.assertj.core.data.Offset.offset(1e-12));
    }
  }

  @Test
  void missingFactorNeverBecomesZeroButAnOrdinaryBlankKeepsExcelSemantics() throws Exception {
    try (var book = new XSSFWorkbook()) {
      book.createSheet("价格").createRow(0).createCell(0).setCellFormula("B1+2");
      var root = new Address("价格", "A1");
      var ordinary = new WorkbookFormulaCompiler(book, "原稿.xlsx", Map.of()).compile(root);
      assertThat(ExpressionEvaluator.evaluate(ordinary.expression(), Map.of()))
          .isEqualByComparingTo("2");
      assertThat(ordinary.sources())
          .anyMatch(s -> s.address().cell().equals("B1") && "BLANK".equals(s.cellType()));
      assertThatThrownBy(
              () ->
                  new WorkbookFormulaCompiler(
                          book, "原稿.xlsx", Map.of(new Address("价格", "B1"), "Cu"))
                      .compile(root))
          .hasMessageContaining("原稿.xlsx / 价格!B1")
          .hasMessageContaining("Cu");
    }
  }

  @Test
  void rejectsCyclesAndUnknownNamesWithTheSourceLocation() throws Exception {
    try (var book = new XSSFWorkbook()) {
      var row = book.createSheet("价格").createRow(0);
      row.createCell(0).setCellFormula("B1+2");
      row.createCell(1).setCellFormula("A1");
      var compiler = new WorkbookFormulaCompiler(book, "原稿.xlsx", Map.of());
      assertThatThrownBy(() -> compiler.compile(new Address("价格", "A1")))
          .hasMessageContaining("循环引用")
          .hasMessageContaining("价格!A1");
      row.getCell(1).setBlank();
      row.getCell(1).setCellValue("未填");
      assertThatThrownBy(() -> compiler.compile(new Address("价格", "A1")))
          .hasMessageContaining("不是数字")
          .hasMessageContaining("价格!B1");
    }
  }
}
