package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.resolvers.ConstResolver;
import com.sanhua.marketingcost.formula.registry.resolvers.FormulaRefResolver;
import com.sanhua.marketingcost.formula.registry.resolvers.OaResolver;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 变量注册表单元测试 —— 覆盖路由分发、循环依赖检测、缓存。
 */
class VariableRegistryTest {

  private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

  /** CONST 类型变量：直接返回 default_value */
  @Test
  @DisplayName("路由：CONST 变量返回 default_value")
  void constResolverHit() {
    PriceVariable v = newVariable("vat_rate", "CONST");
    v.setDefaultValue(new BigDecimal("0.13"));
    VariableRegistry registry = buildRegistry(List.of(v));
    BigDecimal r = registry.resolve("vat_rate", new VariableContext());
    assertThat(r).isCloseTo(new BigDecimal("0.13"), within(TOLERANCE));
  }

  /** OA_FORM 类型变量：从 OaForm 反射读取 + 除以 1000 */
  @Test
  @DisplayName("路由：OA_FORM 变量按字段读 OaForm 并 ÷1000")
  void oaResolverHit() {
    PriceVariable v = newVariable("Cu", "OA_FORM");
    v.setSourceField("copper_price");
    OaForm form = new OaForm();
    form.setCopperPrice(new BigDecimal("90000")); // 90000 元/吨
    VariableRegistry registry = buildRegistry(List.of(v));
    BigDecimal r = registry.resolve("Cu", new VariableContext().oaForm(form));
    // 90000 / 1000 = 90 元/克
    assertThat(r).isCloseTo(new BigDecimal("90"), within(TOLERANCE));
  }

  /** FORMULA_REF 嵌套：Cu_excl = [Cu] / (1 + [vat_rate]) */
  @Test
  @DisplayName("路由：FORMULA_REF 递归解析嵌套变量")
  void formulaRefRecursion() {
    PriceVariable cu = newVariable("Cu", "CONST");
    cu.setDefaultValue(new BigDecimal("90"));
    PriceVariable vat = newVariable("vat_rate", "CONST");
    vat.setDefaultValue(new BigDecimal("0.13"));
    PriceVariable cuExcl = newVariable("Cu_excl", "FORMULA_REF");
    cuExcl.setFormulaExpr("[Cu] / (1 + [vat_rate])");

    VariableRegistry registry = buildRegistry(List.of(cu, vat, cuExcl));
    BigDecimal r = registry.resolve("Cu_excl", new VariableContext());
    // 90 / 1.13 = 79.6460...
    assertThat(r).isCloseTo(new BigDecimal("79.6460"), within(TOLERANCE));
  }

  /** 循环依赖检测：A→B→A，应抛 CircularFormulaException 且环路径正确 */
  @Test
  @DisplayName("循环：A → B → A 抛 CircularFormulaException 含环路径")
  void cycleDetected() {
    PriceVariable a = newVariable("A", "FORMULA_REF");
    a.setFormulaExpr("[B] + 1");
    PriceVariable b = newVariable("B", "FORMULA_REF");
    b.setFormulaExpr("[A] * 2");

    VariableRegistry registry = buildRegistry(List.of(a, b));

    assertThatThrownBy(() -> registry.resolve("A", new VariableContext()))
        .isInstanceOf(CircularFormulaException.class)
        .hasMessageContaining("A")
        .hasMessageContaining("B")
        .satisfies(ex -> {
          var cyc = ((CircularFormulaException) ex).getCyclePath();
          assertThat(cyc).startsWith("A").endsWith("A"); // 闭环首尾一致
        });
  }

  /** 缺失变量定义：返回 null（不抛异常） */
  @Test
  @DisplayName("未注册变量：返回 null")
  void unknownVariable() {
    VariableRegistry registry = buildRegistry(List.of());
    assertThat(registry.resolve("不存在", new VariableContext())).isNull();
  }

  /** 显式 override 优先级最高 */
  @Test
  @DisplayName("override：覆盖值优先于 resolver")
  void overrideWins() {
    PriceVariable v = newVariable("X", "CONST");
    v.setDefaultValue(new BigDecimal("1"));
    VariableRegistry registry = buildRegistry(List.of(v));
    BigDecimal r = registry.resolve("X",
        new VariableContext().override("X", new BigDecimal("999")));
    assertThat(r).isCloseTo(new BigDecimal("999"), within(TOLERANCE));
  }

  /** sourceType 没有对应 resolver：返回 null + WARN 日志 */
  @Test
  @DisplayName("路由：未知 sourceType 返回 null")
  void unknownSourceType() {
    PriceVariable v = newVariable("Y", "MY_CUSTOM_TYPE");
    VariableRegistry registry = buildRegistry(List.of(v));
    assertThat(registry.resolve("Y", new VariableContext())).isNull();
  }

  // ---------- 辅助 ----------

  @SuppressWarnings("unchecked")
  private VariableRegistry buildRegistry(List<PriceVariable> variables) {
    PriceVariableMapper mapper = mock(PriceVariableMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(variables);
    // 注册 3 个真实 resolver；其余 sourceType 走"未知 → null"路径
    return new VariableRegistry(
        mapper,
        List.of(new ConstResolver(), new OaResolver(), new FormulaRefResolver()));
  }

  private PriceVariable newVariable(String code, String sourceType) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setSourceType(sourceType);
    v.setStatus("active");
    v.setTaxMode("INCL");
    return v;
  }
}
