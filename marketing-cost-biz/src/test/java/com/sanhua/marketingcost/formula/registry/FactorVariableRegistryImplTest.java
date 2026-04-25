package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FactorVariableRegistryImpl} 单测（Plan B 重写版） ——
 * 直接按 {@code resolver_kind} + {@code resolver_params} 种子喂变量，验证 5 种 kind
 * 的成功/失败分支，以及环检 / overrides / 缓存等交叉场景。
 *
 * <p>与旧版测试的关键差异：
 * <ul>
 *   <li>构造器入参改 {@link FinanceBasePriceQuery}（包裹 mapper），不再直接传 mapper；</li>
 *   <li>种子变量不再读 {@code sourceTable / variableName-as-shortName / formulaExpr /
 *       defaultValue / contextBindingJson}，统一用 {@code resolverKind / resolverParams}；</li>
 *   <li>FINANCE 分支被 {@link FinanceBasePriceQuery} 的严格校验前置：pricingMonth / priceSource
 *       /（buScoped 时）bu 任何一项缺失都直接 empty，不再尝试"最新回退"。</li>
 * </ul>
 */
class FactorVariableRegistryImplTest {

  private PriceVariableMapper priceVariableMapper;
  private FinanceBasePriceMapper financeBasePriceMapper;
  private FinanceBasePriceQuery financeBasePriceQuery;
  private ObjectMapper objectMapper;
  private FactorVariableRegistryImpl registry;

  @BeforeAll
  static void initTableInfo() {
    // MP Lambda wrapper 依赖 TableInfo 缓存；单测不起 Spring 需要手工预热。
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
  }

  @BeforeEach
  void setUp() {
    priceVariableMapper = mock(PriceVariableMapper.class);
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    financeBasePriceQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
    objectMapper = new ObjectMapper();
    // 本测试类不涉及行局部占位符（__material/__scrap），传空注册表即可——
    // 默认 isKnown() 返回 false，resolve 直接走常规 variable 分支
    registry = new FactorVariableRegistryImpl(
        priceVariableMapper, financeBasePriceQuery, objectMapper,
        mock(RowLocalPlaceholderRegistry.class));
  }

  // ============================ 种子工厂 ============================

  /** FINANCE 变量：resolver_params 走 factorCode 路径 */
  private static PriceVariable financeByFactorCode(String code, String factorCode) {
    PriceVariable v = baseVar(code);
    v.setResolverKind("FINANCE");
    v.setResolverParams(String.format(
        "{\"factorCode\":\"%s\",\"priceSource\":\"平均价\",\"buScoped\":false}", factorCode));
    return v;
  }

  /** ENTITY 变量：反射读 linkedItem.field（可带 unitScale） */
  private static PriceVariable entityVar(String code, String field, String unitScale) {
    PriceVariable v = baseVar(code);
    v.setResolverKind("ENTITY");
    v.setResolverParams(unitScale == null
        ? String.format("{\"entity\":\"linkedItem\",\"field\":\"%s\"}", field)
        : String.format("{\"entity\":\"linkedItem\",\"field\":\"%s\",\"unitScale\":\"%s\"}",
            field, unitScale));
    return v;
  }

  /** DERIVED 变量：params 直接塞 strategy + 附加字段，由 DerivedResolver 消费 */
  private static PriceVariable derivedVar(String code, String strategy) {
    PriceVariable v = baseVar(code);
    v.setResolverKind("DERIVED");
    v.setResolverParams(String.format("{\"strategy\":\"%s\"}", strategy));
    return v;
  }

  /** FORMULA 变量：params.expr 为递归表达式 */
  private static PriceVariable formulaVar(String code, String expr) {
    PriceVariable v = baseVar(code);
    v.setResolverKind("FORMULA");
    // 注意 expr 里的双引号要转义 —— 测试里避免用内部双引号
    v.setResolverParams(String.format("{\"expr\":\"%s\"}", expr));
    return v;
  }

  /** CONST 变量：params.value 为数字 */
  private static PriceVariable constVar(String code, String value) {
    PriceVariable v = baseVar(code);
    v.setResolverKind("CONST");
    v.setResolverParams(String.format("{\"value\":\"%s\"}", value));
    return v;
  }

  private static PriceVariable baseVar(String code) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(code);
    v.setStatus("active");
    return v;
  }

  @SuppressWarnings("unchecked")
  private void seedVariables(PriceVariable... variables) {
    when(priceVariableMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(variables)));
  }

  // ============================ FINANCE 分支 ============================

  @Test
  @DisplayName("FINANCE：factorCode + priceSource + pricingMonth 齐全 → 命中")
  void financeHit() {
    seedVariables(financeByFactorCode("Cu", "Cu"));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("90.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext().pricingMonth("2026-04");
    assertThat(registry.resolve("Cu", ctx)).contains(new BigDecimal("90.00"));
  }

  @Test
  @DisplayName("FINANCE：缺 pricingMonth → empty（严格模式不回退最新）")
  void financeMissingPricingMonth() {
    seedVariables(financeByFactorCode("Cu", "Cu"));
    // ctx.pricingMonth 不设置 → 严格模式直接拒绝
    assertThat(registry.resolve("Cu", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("FINANCE：resolver_params 缺 priceSource → empty")
  void financeMissingPriceSource() {
    PriceVariable v = baseVar("Cu");
    v.setResolverKind("FINANCE");
    v.setResolverParams("{\"factorCode\":\"Cu\",\"buScoped\":false}");
    seedVariables(v);

    VariableContext ctx = new VariableContext().pricingMonth("2026-04");
    assertThat(registry.resolve("Cu", ctx)).isEmpty();
  }

  @Test
  @DisplayName("FINANCE：mapper 未命中 → empty")
  void financeMapperMiss() {
    seedVariables(financeByFactorCode("Cu", "Cu"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(null);

    VariableContext ctx = new VariableContext().pricingMonth("2026-04");
    assertThat(registry.resolve("Cu", ctx)).isEmpty();
  }

  // ============================ ENTITY 分支 ============================

  @Test
  @DisplayName("ENTITY：反射读 linkedItem.blankWeight，应用 unitScale=0.001")
  void entityWithScale() {
    seedVariables(entityVar("blank_weight", "blankWeight", "0.001"));

    PriceLinkedItem item = new PriceLinkedItem();
    item.setBlankWeight(new BigDecimal("500")); // g → 0.5 kg
    VariableContext ctx = new VariableContext().linkedItem(item);

    Optional<BigDecimal> result = registry.resolve("blank_weight", ctx);
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  @DisplayName("ENTITY：无 unitScale 时原值返回")
  void entityWithoutScale() {
    seedVariables(entityVar("process_fee", "processFee", null));

    PriceLinkedItem item = new PriceLinkedItem();
    item.setProcessFee(new BigDecimal("3.73"));
    VariableContext ctx = new VariableContext().linkedItem(item);

    assertThat(registry.resolve("process_fee", ctx))
        .contains(new BigDecimal("3.73"));
  }

  @Test
  @DisplayName("ENTITY：字段值为 null → empty")
  void entityNullField() {
    seedVariables(entityVar("blank_weight", "blankWeight", null));

    VariableContext ctx = new VariableContext().linkedItem(new PriceLinkedItem());
    assertThat(registry.resolve("blank_weight", ctx)).isEmpty();
  }

  @Test
  @DisplayName("ENTITY：resolver_params 缺 entity/field → empty")
  void entityMissingParams() {
    PriceVariable bad = baseVar("bad");
    bad.setResolverKind("ENTITY");
    bad.setResolverParams("{\"entity\":\"linkedItem\"}");
    seedVariables(bad);

    assertThat(registry.resolve("bad", new VariableContext())).isEmpty();
  }

  // ============================ DERIVED 分支 ============================

  @Test
  @DisplayName("DERIVED：未装配 DerivedContextResolver 时返回 empty（不抛）")
  void derivedWithoutResolver() {
    seedVariables(derivedVar("material_price_incl", "MAIN_MATERIAL_FINANCE"));
    assertThat(registry.resolve("material_price_incl", new VariableContext()))
        .isEmpty();
  }

  @Test
  @DisplayName("DERIVED：装配后透传 params，binding.strategy 正确")
  void derivedWithResolver() {
    seedVariables(derivedVar("material_price_incl", "MAIN_MATERIAL_FINANCE"));
    registry.setDerivedContextResolver((variable, ctx, binding, sub) -> {
      assertThat(variable.getVariableCode()).isEqualTo("material_price_incl");
      assertThat(binding.get("strategy")).isEqualTo("MAIN_MATERIAL_FINANCE");
      assertThat(sub).isNotNull();
      return Optional.of(new BigDecimal("42"));
    });
    assertThat(registry.resolve("material_price_incl", new VariableContext()))
        .contains(new BigDecimal("42"));
  }

  // ============================ FORMULA 分支 ============================

  @Test
  @DisplayName("FORMULA：单层 [Cu]+[vat_rate] 合成值")
  void formulaSingleLevel() {
    seedVariables(
        financeByFactorCode("Cu", "Cu"),
        constVar("vat_rate", "0.13"),
        formulaVar("Cu_plus", "[Cu]+[vat_rate]"));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("90.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext().pricingMonth("2026-04");
    Optional<BigDecimal> result = registry.resolve("Cu_plus", ctx);
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo(new BigDecimal("90.13"));
  }

  @Test
  @DisplayName("FORMULA：多层 A=[B]*2, B=[C], C=CONST=7 → 14")
  void formulaMultiLevel() {
    seedVariables(
        constVar("C", "7"),
        formulaVar("B", "[C]"),
        formulaVar("A", "[B]*2"));

    assertThat(registry.resolve("A", new VariableContext()))
        .contains(new BigDecimal("14"));
  }

  @Test
  @DisplayName("FORMULA：环检 A=[B], B=[A] 抛 CircularFormulaException")
  void formulaCycle() {
    seedVariables(
        formulaVar("A", "[B]"),
        formulaVar("B", "[A]"));

    assertThatThrownBy(() -> registry.resolve("A", new VariableContext()))
        .isInstanceOf(CircularFormulaException.class)
        .hasMessageContaining("A")
        .hasMessageContaining("B");
  }

  @Test
  @DisplayName("FORMULA：resolver_params 缺 expr → empty")
  void formulaMissingExpr() {
    PriceVariable bad = baseVar("bad");
    bad.setResolverKind("FORMULA");
    bad.setResolverParams("{}");
    seedVariables(bad);
    assertThat(registry.resolve("bad", new VariableContext())).isEmpty();
  }

  // ============================ CONST 分支 ============================

  @Test
  @DisplayName("CONST：读 params.value")
  void constHit() {
    seedVariables(constVar("yield_ratio", "0.95"));
    assertThat(registry.resolve("yield_ratio", new VariableContext()))
        .contains(new BigDecimal("0.95"));
  }

  @Test
  @DisplayName("CONST：resolver_params 缺 value → empty")
  void constMissingValue() {
    PriceVariable bad = baseVar("bad");
    bad.setResolverKind("CONST");
    bad.setResolverParams("{}");
    seedVariables(bad);
    assertThat(registry.resolve("bad", new VariableContext())).isEmpty();
  }

  // ============================ 通用/边界 ============================

  @Test
  @DisplayName("未知 resolver_kind → empty + WARN（不抛）")
  void unknownKind() {
    PriceVariable weird = baseVar("weird");
    weird.setResolverKind("WEIRD");
    weird.setResolverParams("{}");
    seedVariables(weird);
    assertThat(registry.resolve("weird", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("resolver_kind 为 null（如 Al/Sn/Cn 数据未配置） → empty")
  void nullKind() {
    PriceVariable v = baseVar("Al");
    // 不设 resolverKind → 模拟 V31 回填里 Al/Sn/Cn 的场景
    seedVariables(v);
    assertThat(registry.resolve("Al", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("变量不存在：返回 empty 而非抛异常")
  void missingVariable() {
    seedVariables();
    assertThat(registry.resolve("nonexistent", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("overrides 优先级最高，跳过实际解析")
  void overridesWin() {
    seedVariables();
    VariableContext ctx = new VariableContext().override("Cu", new BigDecimal("100"));
    assertThat(registry.resolve("Cu", ctx)).contains(new BigDecimal("100"));
  }

  @Test
  @DisplayName("空/空白变量名：返回 empty")
  void blankCode() {
    seedVariables();
    assertThat(registry.resolve(null, new VariableContext())).isEmpty();
    assertThat(registry.resolve("", new VariableContext())).isEmpty();
    assertThat(registry.resolve("   ", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("resolver_params JSON 解析失败 → 各分支自行返回 empty，不抛")
  void invalidParamsJson() {
    PriceVariable bad = baseVar("bad");
    bad.setResolverKind("FORMULA");
    bad.setResolverParams("{not-a-valid-json");
    seedVariables(bad);
    assertThat(registry.resolve("bad", new VariableContext())).isEmpty();
  }

  @Test
  @DisplayName("单次 resolve 内 request cache 去重：Cu 在 [Cu]+[Cu] 公式里只查一次 finance")
  void requestCacheDedupes() {
    seedVariables(
        financeByFactorCode("Cu", "Cu"),
        formulaVar("combo", "[Cu]+[Cu]"));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("90"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext().pricingMonth("2026-04");
    assertThat(registry.resolve("combo", ctx)).contains(new BigDecimal("180"));
    // Cu 只查一次 finance —— request cache 生效
    org.mockito.Mockito.verify(financeBasePriceMapper, org.mockito.Mockito.times(1))
        .selectOne(any());
  }
}
