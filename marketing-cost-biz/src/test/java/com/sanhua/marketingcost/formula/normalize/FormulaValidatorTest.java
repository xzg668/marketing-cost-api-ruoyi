package com.sanhua.marketingcost.formula.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FormulaValidator} 单测 —— 覆盖方案 C 对应的两类结构错。
 *
 * <p>白名单种子：Cu / Zn / blank_weight / net_weight / process_fee，
 * 加上 Normalizer 阶段 3 的行局部占位符 __material / __scrap（自动注入）。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>健康表达式 —— 各种括号/一元运算符 pass</li>
 *   <li>相邻 value 缺运算符 —— {@code [a][b]}、{@code [a](b)}、{@code 3[b]}、{@code (a)(b)}</li>
 *   <li>{@code [code]} 不在白名单 —— 把整段算式当变量名包方括号</li>
 *   <li>裸 ASCII 引用未知 code —— {@code foo*2}</li>
 *   <li>括号/方括号不平衡 & 非法字符</li>
 * </ol>
 */
class FormulaValidatorTest {

  private FormulaValidator validator;

  @BeforeEach
  void setUp() {
    PriceVariableMapper mapper = mock(PriceVariableMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        variable("Cu"),
        variable("Zn"),
        variable("blank_weight"),
        variable("net_weight"),
        variable("process_fee")));
    validator = new FormulaValidator(
        mapper,
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport.defaultRegistry());
    validator.init();
  }

  @Test
  @DisplayName("健康路径：括号 + 一元 +/- + 混合 [code]/裸 ASCII")
  void healthyFormulas() {
    assertThatCode(() -> validator.validate("[Cu]*0.59+[Zn]*0.41")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("-[Cu]+[blank_weight]")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("([Cu]*0.65+[Zn]*0.35)*[net_weight]+[process_fee]"))
        .doesNotThrowAnyException();
    // 裸 ASCII 在白名单里也放行
    assertThatCode(() -> validator.validate("Cu*0.62+Zn*0.38")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("空串 / null 直接通过（没东西可错）")
  void emptyFormulasPass() {
    assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("")).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("   ")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("相邻 value 缺运算符：[a][b] → 抛 '缺运算符'（id=13 的脏点）")
  void adjacentBracketsMissingOperator() {
    // 实际 id=13 的脏点：[copper_scrap_price]([Cu]...)
    assertThatThrownBy(() -> validator.validate("[Cu][Zn]"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("缺运算符");
  }

  @Test
  @DisplayName("相邻 value 缺运算符：[a](b) → 抛")
  void bracketFollowedByParenMissingOperator() {
    assertThatThrownBy(() -> validator.validate("[Cu](0.5+1)"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("缺运算符");
  }

  @Test
  @DisplayName("相邻 value 缺运算符：3[b] → 抛")
  void numberFollowedByBracketMissingOperator() {
    assertThatThrownBy(() -> validator.validate("3[Cu]"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("缺运算符");
  }

  @Test
  @DisplayName("相邻 value 缺运算符：(a)(b) → 抛")
  void parenFollowedByParenMissingOperator() {
    assertThatThrownBy(() -> validator.validate("([Cu]+1)([Zn]+2)"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("缺运算符");
  }

  @Test
  @DisplayName("方括号把整段算式错误包起来 → code 不在白名单（id=16 的脏点）")
  void bracketWrappingSubExpressionRejected() {
    // 实际 id=16：[(Cu*0.15+Zn*0.1+us_brass_price*0.75*1.06)*1.02+1100] ← 这种
    assertThatThrownBy(() -> validator.validate("[blank_weight]*[(Cu+Zn)*1.02]/1000"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("未知变量");
  }

  @Test
  @DisplayName("裸 ASCII 引用白名单外的变量 → 抛")
  void bareAsciiUnknownCode() {
    assertThatThrownBy(() -> validator.validate("foo*2"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("foo");
  }

  @Test
  @DisplayName("方括号未闭合 → 抛")
  void unclosedBracket() {
    assertThatThrownBy(() -> validator.validate("[Cu*2"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("方括号未闭合");
  }

  @Test
  @DisplayName("右括号过多 → 抛")
  void tooManyRightParens() {
    assertThatThrownBy(() -> validator.validate("[Cu]+1)"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("右括号");
  }

  @Test
  @DisplayName("表达式以运算符结尾 → 抛")
  void trailingOperator() {
    assertThatThrownBy(() -> validator.validate("[Cu]+"))
        .isInstanceOf(FormulaSyntaxException.class);
  }

  @Test
  @DisplayName("非法字符 → 抛（用 Normalizer 规范化后本不该剩，但校验兜底）")
  void illegalCharacter() {
    assertThatThrownBy(() -> validator.validate("[Cu]@2"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("非法字符");
  }

  @Test
  @DisplayName("行局部占位符 __material / __scrap 识别为合法 code")
  void rowLocalPlaceholdersAllowed() {
    assertThat(validator).isNotNull();
    assertThatCode(() -> validator.validate("[blank_weight]*[__material]-[__scrap]*[net_weight]"))
        .doesNotThrowAnyException();
  }

  private static PriceVariable variable(String code) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setStatus("active");
    return v;
  }
}
