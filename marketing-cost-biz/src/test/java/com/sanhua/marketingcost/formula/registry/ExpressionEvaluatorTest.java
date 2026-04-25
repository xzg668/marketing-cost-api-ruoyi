package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
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
  @DisplayName("evaluate：缺失变量按 0 处理 + 一元负号")
  void missingAndUnary() {
    Map<String, BigDecimal> values = Map.of("a", new BigDecimal("10"));
    BigDecimal r = ExpressionEvaluator.evaluate("-a + missing", values);
    assertThat(r).isCloseTo(new BigDecimal("-10"), within(TOLERANCE));
  }

  @Test
  @DisplayName("evaluate：除零返回 null")
  void divideByZero() {
    Map<String, BigDecimal> values = Map.of("a", new BigDecimal("10"), "b", BigDecimal.ZERO);
    assertThat(ExpressionEvaluator.evaluate("a / b", values)).isNull();
  }
}
