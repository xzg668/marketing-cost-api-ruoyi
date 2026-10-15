package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表达式求值器测试 —— 覆盖 ASCII / 中文 / 混合三种变量风格。
 */
class ExpressionEvaluatorTest {

  private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

  @Test
  @DisplayName("extractVariables：纯 ASCII 表达式抽取")
  void asciiExtract() {
    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables("Cu * blank_weight + fee");
    assertThat(tokens).containsExactly("Cu", "blank_weight", "fee");
  }

  @Test
  @DisplayName("extractVariables：中文 [变量] 显式声明")
  void chineseBracketExtract() {
    LinkedHashSet<String> tokens =
        ExpressionEvaluator.extractVariables("[铜不含税] / (1 + [税率])");
    assertThat(tokens).containsExactly("铜不含税", "税率");
  }

  @Test
  @DisplayName("extractVariables：中英混合 —— 先抽全部 bracket 后再抽 ASCII")
  void mixedExtract() {
    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables(
        "[铜不含税] * net_weight + [税率] * Cu");
    // 抽取顺序：先全部 [中文]，再剩余串 ASCII；测试集合内容与去重，对顺序不敏感
    assertThat(tokens).containsExactlyInAnyOrder("铜不含税", "net_weight", "税率", "Cu");
  }

  @Test
  @DisplayName("evaluate：纯 ASCII 表达式")
  void asciiEvaluate() {
    Map<String, BigDecimal> values = new HashMap<>();
    values.put("Cu", new BigDecimal("60"));
    values.put("blank_weight", new BigDecimal("0.5"));
    values.put("fee", new BigDecimal("12.89"));
    BigDecimal r = ExpressionEvaluator.evaluate("Cu * blank_weight + fee", values);
    assertThat(r).isCloseTo(new BigDecimal("42.89"), within(TOLERANCE));
  }

  @Test
  @DisplayName("evaluate：中文括号变量按映射代入")
  void chineseEvaluate() {
    Map<String, BigDecimal> values = new HashMap<>();
    values.put("铜不含税", new BigDecimal("80"));
    values.put("税率", new BigDecimal("0.13"));
    // 80 / (1 + 0.13) = 70.79646...
    BigDecimal r = ExpressionEvaluator.evaluate("[铜不含税] / (1 + [税率])", values);
    assertThat(r).isCloseTo(new BigDecimal("70.7964"), within(TOLERANCE));
  }

  @Test
  @DisplayName("evaluate：缺失变量明确失败，真实0与一元负号可计算")
  void missingAndUnary() {
    Map<String, BigDecimal> values = Map.of("a", new BigDecimal("10"));
    assertThatThrownBy(() -> ExpressionEvaluator.evaluate("-a + missing", values))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
    assertThat(ExpressionEvaluator.evaluate("--a + -+0", values)).isEqualByComparingTo("10");
  }

  @Test
  @DisplayName("evaluate：除零明确失败")
  void divideByZero() {
    Map<String, BigDecimal> values = Map.of("a", new BigDecimal("10"), "b", BigDecimal.ZERO);
    assertThatThrownBy(() -> ExpressionEvaluator.evaluate("a / b", values))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("除数为0");
  }

  @Test
  void functionNamesAndScientificNotationAreNotVariables() {
    assertThat(ExpressionEvaluator.extractVariables("ROUND([Cu]*1e-3+INT(-Zn),4)+[Cu]"))
        .containsExactly("Cu", "Zn");
  }

  @Test
  void roundingStaysAtItsOriginalPositionBeforeTaxDivision() {
    assertThat(ExpressionEvaluator.evaluate("ROUND(20 + 1.23456,4) / 1.13", Map.of()))
        .isEqualByComparingTo(new BigDecimal("21.2346").divide(new BigDecimal("1.13"), java.math.MathContext.DECIMAL128));
    assertThat(ExpressionEvaluator.evaluate("ROUND(-1.235,2)+INT(-1.2)", Map.of()))
        .isEqualByComparingTo("-3.24");
    assertThat(ExpressionEvaluator.evaluate("ROUND(150,-2)+ROUND(2.345,2)", Map.of()))
        .isEqualByComparingTo("202.35");
  }

  @Test
  void rejectsUnsupportedOrIncompleteSyntaxInsteadOfIgnoringCharacters() {
    for (String expression : java.util.List.of("1$+2", "1 2", "(1+2", "1+2)", "ROUND(1)",
        "INT(1,2)", "SUM(1,2)", "[Cu", "Cu()", "1e+", "1.2.3", "1+")) {
      assertThatThrownBy(() -> ExpressionEvaluator.validate(expression))
          .as(expression).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> ExpressionEvaluator.evaluate("ROUND(1,2.2)", Map.of()))
        .hasMessageContaining("整数");
  }

  @Test
  void numericFunctionsAgreeWithIndependentExcelEvaluation() throws Exception {
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      var row = workbook.createSheet("计算").createRow(0);
      java.util.List<String> formulas = java.util.List.of("ROUND(20+1.23456,4)/1.13",
          "ROUND(ROUND(90*0.01967,5)*1.18,4)/1.13", "INT(90/7)+0.3", "INT(-1.2)",
          "ROUND(-1.235,2)", "ROUND(150,-2)", "ROUND(2.345,2)", "1/3*3", "1.25E-3*90000");
      for (int i = 0; i < formulas.size(); i++) {
        var cell = row.createCell(i);
        cell.setCellFormula(formulas.get(i));
        assertThat(ExpressionEvaluator.evaluate(formulas.get(i), Map.of()).doubleValue())
            .as(formulas.get(i)).isCloseTo(evaluator.evaluate(cell).getNumberValue(), within(1e-12));
      }
    }
  }
}
