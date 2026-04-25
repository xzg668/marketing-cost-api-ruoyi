package com.sanhua.marketingcost.formula.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FormulaUnitConsistencyChecker} 单测 —— 覆盖 kg 口径静态检查的命中与误报。
 */
class FormulaUnitConsistencyCheckerTest {

  private final FormulaUnitConsistencyChecker checker = new FormulaUnitConsistencyChecker();

  @Test
  @DisplayName("纯 kg 口径公式 —— 不产生 warning")
  void kgFormulaHasNoWarning() {
    String expr =
        "[blank_weight]*(([Cu]*0.15+[Zn]*0.1+[us_yellow_copper_price]*0.75*1.05)*1.02+0.05)"
            + "-([blank_weight]-[net_weight])*([Cu]*0.59+[Zn]*0.41)*0.915+[process_fee]";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).isEmpty();
  }

  @Test
  @DisplayName("出现 /1000 —— 报一条'克→千克二次换算'警告")
  void div1000Warns() {
    String expr = "[blank_weight]*([Cu]*0.59)/1000+[process_fee]";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).contains("/1000").contains("千克");
  }

  @Test
  @DisplayName("出现 *1000 —— 报一条'反向乘 1000'警告")
  void mul1000Warns() {
    String expr = "[blank_weight]*1000*[Cu]";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).contains("*1000");
  }

  @Test
  @DisplayName("裸大常数 +50/+1100 —— 报'元/吨'疑似警告")
  void bareBigConstantWarns() {
    String expr = "[blank_weight]*(([Cu]*0.15)*1.02+50)-([Cu]*0.59+[Zn]*0.41)+1100";
    List<String> warnings = checker.check(expr);
    // 两个大常数合并成一条提示（列出 50、1100）
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).contains("50").contains("1100").contains("元/吨");
  }

  @Test
  @DisplayName("Excel 旧口径混合（/1000 + +50）—— 两条 warning 同时出现")
  void div1000AndBareConstantBothWarn() {
    String expr = "[blank_weight]*(([Cu]*0.15+[Zn]*0.1)*1.02+50)/1000+[process_fee]";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).hasSize(2);
    assertThat(warnings.get(0)).contains("/1000");
    assertThat(warnings.get(1)).contains("50");
  }

  @Test
  @DisplayName("合理小数系数 0.93、1.02、0.915 —— 不触发大常数警告")
  void smallFractionalCoefficientsNoWarn() {
    String expr = "[blank_weight]*([Cu]*0.59*1.02)*0.93-([blank_weight]-[net_weight])*0.915";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).isEmpty();
  }

  @Test
  @DisplayName("1000 作为 /1000 一部分出现 —— 不被第二条规则重复计数")
  void thousandOnlyReportedOnce() {
    String expr = "[blank_weight]*[Cu]/1000";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0)).contains("/1000");
  }

  @Test
  @DisplayName("空串 / null —— 返回空列表")
  void emptyInputReturnsEmpty() {
    assertThat(checker.check(null)).isEmpty();
    assertThat(checker.check("")).isEmpty();
  }

  @Test
  @DisplayName("负号不应把科学计数法（如 1e-5）切成疑似常数")
  void scientificNotationNotFalselyMatched() {
    // 当前 normalizer 不会产生 1e-5，此用例保险 —— 至少纯数字不跟符号开头不误报
    String expr = "[Cu]*0.000001";
    List<String> warnings = checker.check(expr);
    assertThat(warnings).isEmpty();
  }
}
