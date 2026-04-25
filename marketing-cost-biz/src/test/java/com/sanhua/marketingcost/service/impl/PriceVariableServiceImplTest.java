package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceVariableRequest;
import com.sanhua.marketingcost.dto.VariableCatalogResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PriceVariableServiceImpl 单测 —— 聚焦 {@link PriceVariableServiceImpl#catalog()} 的分组与 enrichment 逻辑。
 *
 * <p>覆盖：
 * <ol>
 *   <li>按 {@code factor_type} 三分组，CONST 被丢弃</li>
 *   <li>status != 'active' 的行由 Mapper 层 WHERE 兜住（测试里只注入 active 行，验证 catalog 不再二次过滤）</li>
 *   <li>FINANCE_FACTOR 会批量带出最新月的 {@code price/unit/source/pricingMonth}；
 *       未在财务表命中的因素 4 字段为 null</li>
 *   <li>PART_CONTEXT 透传 {@code context_binding_json}；FORMULA_REF 透传 {@code formulaExpr}</li>
 * </ol>
 */
class PriceVariableServiceImplTest {

  private PriceVariableMapper priceVariableMapper;
  private FinanceBasePriceMapper financeBasePriceMapper;
  private FactorVariableRegistryImpl registry;
  private FormulaNormalizer formulaNormalizer;
  private FormulaValidator formulaValidator;
  private PriceVariableServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    // MP Lambda Wrapper 依赖 TableInfo 缓存；非 Spring 启动场景手工预热。
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
  }

  @BeforeEach
  void setUp() {
    priceVariableMapper = mock(PriceVariableMapper.class);
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    registry = mock(FactorVariableRegistryImpl.class);
    formulaNormalizer = mock(FormulaNormalizer.class);
    formulaValidator = mock(FormulaValidator.class);
    // 默认 pass-through：normalize 原样返回，validate 不抛。
    // 需要模拟错误的用例在各自 @Test 内覆盖 stub。
    when(formulaNormalizer.normalize(any())).thenAnswer(inv -> inv.getArgument(0));
    service = new PriceVariableServiceImpl(
        priceVariableMapper, financeBasePriceMapper, registry, new ObjectMapper(),
        formulaNormalizer, formulaValidator);
  }

  @Test
  @DisplayName("catalog：按 factor_type 三分组，CONST 被丢弃")
  void catalog_groupsByFactorTypeAndDropsConst() {
    List<PriceVariable> active = new ArrayList<>();
    active.add(variable("Cu", "电解铜", "FINANCE_FACTOR", null, null));
    active.add(variable("blank_weight", "下料重量", "PART_CONTEXT",
        null, "{\"source\":\"ENTITY\",\"field\":\"blank_weight\"}"));
    active.add(variable("Cu_excl", "不含税电解铜", "FORMULA_REF",
        "[Cu]/(1+[vat_rate])", null));
    active.add(variable("vat_rate", "增值税率", "CONST", null, null));
    stubVariableSelect(active);
    stubFinanceSelect(List.of());

    VariableCatalogResponse resp = service.catalog();

    assertThat(resp.getFinanceFactors()).hasSize(1);
    assertThat(resp.getFinanceFactors().get(0).getCode()).isEqualTo("Cu");
    assertThat(resp.getPartContexts()).hasSize(1);
    assertThat(resp.getPartContexts().get(0).getCode()).isEqualTo("blank_weight");
    assertThat(resp.getPartContexts().get(0).getBinding()).contains("\"ENTITY\"");
    assertThat(resp.getFormulaRefs()).hasSize(1);
    assertThat(resp.getFormulaRefs().get(0).getFormulaExpr()).isEqualTo("[Cu]/(1+[vat_rate])");
  }

  @Test
  @DisplayName("catalog：FINANCE_FACTOR 批量带出最新月价格；未命中的因素 4 字段为 null")
  void catalog_enrichesFinanceFactorWithLatestPrice() {
    List<PriceVariable> active = new ArrayList<>();
    active.add(variable("Cu", "电解铜", "FINANCE_FACTOR", null, null));
    active.add(variable("Zn", "电解锌", "FINANCE_FACTOR", null, null));
    active.add(variable("us_brass_price", "美国柜装黄铜", "FINANCE_FACTOR", null, null));
    stubVariableSelect(active);

    // 财务表：Cu 有两个月，验证取最新月（已由 SQL 排序保证，这里按顺序给）；Zn 只有一月；黄铜无记录。
    List<FinanceBasePrice> rows = new ArrayList<>();
    rows.add(financePrice("电解铜", new BigDecimal("91.20"), "元/kg", "上海有色网", "2024-03"));
    rows.add(financePrice("电解铜", new BigDecimal("88.10"), "元/kg", "上海有色网", "2024-02"));
    rows.add(financePrice("电解锌", new BigDecimal("22.50"), "元/kg", "上海有色网", "2024-03"));
    stubFinanceSelect(rows);

    VariableCatalogResponse resp = service.catalog();

    assertThat(resp.getFinanceFactors()).hasSize(3);
    VariableCatalogResponse.FinanceFactor cu = findByCode(resp.getFinanceFactors(), "Cu");
    assertThat(cu.getCurrentPrice()).isEqualByComparingTo("91.20");
    assertThat(cu.getPricingMonth()).isEqualTo("2024-03");
    assertThat(cu.getUnit()).isEqualTo("元/kg");
    assertThat(cu.getSource()).isEqualTo("上海有色网");

    VariableCatalogResponse.FinanceFactor zn = findByCode(resp.getFinanceFactors(), "Zn");
    assertThat(zn.getCurrentPrice()).isEqualByComparingTo("22.50");

    VariableCatalogResponse.FinanceFactor brass =
        findByCode(resp.getFinanceFactors(), "us_brass_price");
    assertThat(brass.getCurrentPrice()).isNull();
    assertThat(brass.getPricingMonth()).isNull();
    assertThat(brass.getUnit()).isNull();
    assertThat(brass.getSource()).isNull();
  }

  @Test
  @DisplayName("catalog：PriceVariable 表全空时，三组都是空 list（非 null）")
  void catalog_emptyVariables_returnsEmptyLists() {
    stubVariableSelect(List.of());
    stubFinanceSelect(List.of());

    VariableCatalogResponse resp = service.catalog();

    assertThat(resp.getFinanceFactors()).isEmpty();
    assertThat(resp.getPartContexts()).isEmpty();
    assertThat(resp.getFormulaRefs()).isEmpty();
  }

  // ======================== CRUD 校验用例（T9a）========================

  @Test
  @DisplayName("create：FINANCE 合法参数写入并刷新 registry 缓存")
  void create_financeHappy_invalidatesCache() {
    when(priceVariableMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

    PriceVariableRequest req = baseRequest("Cu", "电解铜", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("factorCode", "Cu", "priceSource", "平均价", "buScoped", true));

    service.create(req);

    verify(priceVariableMapper, atLeastOnce()).insert(any(PriceVariable.class));
    verify(registry).invalidate();
  }

  @Test
  @DisplayName("create：variableCode 重复 → 抛 IllegalArgumentException，不落库")
  void create_duplicateCode_throws() {
    when(priceVariableMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

    PriceVariableRequest req = baseRequest("Cu", "电解铜", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("factorCode", "Cu", "priceSource", "平均价"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("变量编码已存在");
    verify(priceVariableMapper, never()).insert(any(PriceVariable.class));
    verify(registry, never()).invalidate();
  }

  @Test
  @DisplayName("create：FINANCE 缺 priceSource → 抛 IllegalArgumentException")
  void create_financeMissingPriceSource_throws() {
    PriceVariableRequest req = baseRequest("Cu", "电解铜", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("factorCode", "Cu"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceSource 必填");
  }

  @Test
  @DisplayName("create：FINANCE factorCode 和 shortName 都缺 → 抛 IllegalArgumentException")
  void create_financeNoIdentity_throws() {
    PriceVariableRequest req = baseRequest("Cu", "电解铜", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("priceSource", "平均价"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("factorCode 和 shortName 至少填一个");
  }

  @Test
  @DisplayName("create：ENTITY 缺 field → 抛 IllegalArgumentException")
  void create_entityMissingField_throws() {
    PriceVariableRequest req = baseRequest("blank_weight", "下料重量", "PART_CONTEXT", "ENTITY");
    req.setResolverParams(map("entity", "linkedItem"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("field 必填");
  }

  @Test
  @DisplayName("create：DERIVED strategy 非法值 → 抛 IllegalArgumentException")
  void create_derivedInvalidStrategy_throws() {
    PriceVariableRequest req = baseRequest("derived_x", "派生 X", "PART_CONTEXT", "DERIVED");
    req.setResolverParams(map("strategy", "UNKNOWN"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strategy");
  }

  @Test
  @DisplayName("create：DERIVED FORMULA_REF 缺 formulaRef → 抛 IllegalArgumentException")
  void create_derivedFormulaRefMissing_throws() {
    PriceVariableRequest req = baseRequest("derived_x", "派生 X", "PART_CONTEXT", "DERIVED");
    req.setResolverParams(map("strategy", "FORMULA_REF"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("formulaRef 必填");
  }

  @Test
  @DisplayName("create：FORMULA 缺 expr → 抛 IllegalArgumentException")
  void create_formulaMissingExpr_throws() {
    PriceVariableRequest req = baseRequest("Cu_excl", "不含税铜", "FORMULA_REF", "FORMULA");
    req.setResolverParams(new HashMap<>());

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expr 必填");
  }

  @Test
  @DisplayName("create：CONST 缺 value → 抛 IllegalArgumentException")
  void create_constMissingValue_throws() {
    PriceVariableRequest req = baseRequest("vat_rate", "增值税率", "CONST", "CONST");
    req.setResolverParams(new HashMap<>());

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value 必填");
  }

  @Test
  @DisplayName("create：FORMULA 公式规范化后以 [code] 形式落库")
  void create_formulaNormalizedAndPersisted() {
    when(priceVariableMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    // Normalizer 把中文别名改写成 [code]，Service 应保留改写后的结果写入 resolver_params
    when(formulaNormalizer.normalize("电解铜*0.59+电解锌*0.41"))
        .thenReturn("[Cu]*0.59+[Zn]*0.41");

    PriceVariableRequest req = baseRequest("copper_zinc_mix", "铜锌混合", "FORMULA_REF", "FORMULA");
    req.setResolverParams(map("expr", "电解铜*0.59+电解锌*0.41"));

    service.create(req);

    // 确认 validator 收到的是规范化后的串
    verify(formulaValidator).validate("[Cu]*0.59+[Zn]*0.41");
    // 抓落库 entity，resolverParams 里 expr 已替换成 [code] 形态
    ArgumentCaptor<PriceVariable> captor = ArgumentCaptor.forClass(PriceVariable.class);
    verify(priceVariableMapper).insert(captor.capture());
    assertThat(captor.getValue().getResolverParams()).contains("[Cu]*0.59+[Zn]*0.41");
    assertThat(captor.getValue().getResolverParams()).doesNotContain("电解铜");
  }

  @Test
  @DisplayName("create：FORMULA 含未知 token → Normalizer 抛 → 转译成 IllegalArgumentException")
  void create_formulaNormalizerRejects() {
    when(formulaNormalizer.normalize("unknown_alias*2"))
        .thenThrow(new FormulaSyntaxException("未识别的中文 token"));

    PriceVariableRequest req = baseRequest("bad", "坏公式", "FORMULA_REF", "FORMULA");
    req.setResolverParams(map("expr", "unknown_alias*2"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FORMULA.expr")
        .hasMessageContaining("未识别的中文 token");
    verify(priceVariableMapper, never()).insert(any(PriceVariable.class));
    verify(registry, never()).invalidate();
  }

  @Test
  @DisplayName("create：FORMULA 结构错（相邻 var 缺算符）→ Validator 抛 → 转 IllegalArgumentException")
  void create_formulaValidatorRejects() {
    // Normalizer pass-through（默认 stub），Validator 抛
    doThrow(new FormulaSyntaxException("缺运算符"))
        .when(formulaValidator).validate(eq("[Cu][Zn]"));

    PriceVariableRequest req = baseRequest("bad", "坏公式", "FORMULA_REF", "FORMULA");
    req.setResolverParams(map("expr", "[Cu][Zn]"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FORMULA.expr")
        .hasMessageContaining("缺运算符");
    verify(priceVariableMapper, never()).insert(any(PriceVariable.class));
  }

  @Test
  @DisplayName("create：DERIVED/FORMULA_REF 的 formulaRef 同样走 normalize+validate，落库为 [code]")
  void create_derivedFormulaRefNormalized() {
    when(priceVariableMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    when(formulaNormalizer.normalize("电解铜*0.6+电解锌*0.4"))
        .thenReturn("[Cu]*0.6+[Zn]*0.4");

    PriceVariableRequest req = baseRequest("mix_ref", "派生混合", "PART_CONTEXT", "DERIVED");
    req.setResolverParams(map("strategy", "FORMULA_REF", "formulaRef", "电解铜*0.6+电解锌*0.4"));

    service.create(req);

    verify(formulaValidator).validate("[Cu]*0.6+[Zn]*0.4");
    ArgumentCaptor<PriceVariable> captor = ArgumentCaptor.forClass(PriceVariable.class);
    verify(priceVariableMapper).insert(captor.capture());
    assertThat(captor.getValue().getResolverParams()).contains("[Cu]*0.6+[Zn]*0.4");
    assertThat(captor.getValue().getResolverParams()).doesNotContain("电解铜");
  }

  @Test
  @DisplayName("create：resolverKind 不合法 → 抛 IllegalArgumentException")
  void create_invalidResolverKind_throws() {
    PriceVariableRequest req = baseRequest("x", "x", "FINANCE_FACTOR", "BOGUS");
    req.setResolverParams(map("k", "v"));

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolverKind 非法");
  }

  @Test
  @DisplayName("update：修改 variableCode → 抛 IllegalArgumentException")
  void update_changeCode_throws() {
    PriceVariable existing = variable("Cu", "电解铜", "FINANCE_FACTOR", null, null);
    existing.setId(1L);
    when(priceVariableMapper.selectById(1L)).thenReturn(existing);

    PriceVariableRequest req = baseRequest("Cu2", "电解铜", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("factorCode", "Cu", "priceSource", "平均价"));

    assertThatThrownBy(() -> service.update(1L, req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("variableCode 不允许修改");
    verify(registry, never()).invalidate();
  }

  @Test
  @DisplayName("update：合法更新 → updateById + registry invalidate")
  void update_happy_invalidatesCache() {
    PriceVariable existing = variable("Cu", "电解铜", "FINANCE_FACTOR", null, null);
    existing.setId(1L);
    when(priceVariableMapper.selectById(1L)).thenReturn(existing);

    PriceVariableRequest req = baseRequest("Cu", "电解铜 (更名)", "FINANCE_FACTOR", "FINANCE");
    req.setResolverParams(map("factorCode", "Cu", "priceSource", "平均价"));

    service.update(1L, req);

    verify(priceVariableMapper).updateById(any(PriceVariable.class));
    verify(registry).invalidate();
  }

  @Test
  @DisplayName("softDelete：status 置 inactive + registry invalidate")
  void softDelete_setsInactiveAndInvalidates() {
    PriceVariable existing = variable("Cu", "电解铜", "FINANCE_FACTOR", null, null);
    existing.setId(1L);
    when(priceVariableMapper.selectById(1L)).thenReturn(existing);

    service.softDelete(1L);

    assertThat(existing.getStatus()).isEqualTo("inactive");
    verify(priceVariableMapper).updateById(existing);
    verify(registry).invalidate();
  }

  @Test
  @DisplayName("getById：id 不存在 → 抛 IllegalArgumentException")
  void getById_notFound_throws() {
    when(priceVariableMapper.selectById(999L)).thenReturn(null);

    assertThatThrownBy(() -> service.getById(999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("变量不存在");
  }

  // ------- helpers -------

  @SuppressWarnings("unchecked")
  private void stubVariableSelect(List<PriceVariable> rows) {
    when(priceVariableMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      // 把 Lambda Wrapper 的 SQL 片段求值一次，避免懒初始化在 verify 时炸
      AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) inv.getArgument(0);
      w.getSqlSegment();
      return rows;
    });
  }

  @SuppressWarnings("unchecked")
  private void stubFinanceSelect(List<FinanceBasePrice> rows) {
    when(financeBasePriceMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) inv.getArgument(0);
      w.getSqlSegment();
      return rows;
    });
  }

  private static PriceVariable variable(
      String code, String name, String factorType, String formulaExpr, String binding) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setStatus("active");
    v.setFactorType(factorType);
    v.setFormulaExpr(formulaExpr);
    v.setContextBindingJson(binding);
    return v;
  }

  private static FinanceBasePrice financePrice(
      String shortName, BigDecimal price, String unit, String source, String month) {
    FinanceBasePrice fp = new FinanceBasePrice();
    fp.setShortName(shortName);
    fp.setPrice(price);
    fp.setUnit(unit);
    fp.setPriceSource(source);
    fp.setPriceMonth(month);
    return fp;
  }

  private static VariableCatalogResponse.FinanceFactor findByCode(
      List<VariableCatalogResponse.FinanceFactor> list, String code) {
    return list.stream()
        .filter(f -> code.equals(f.getCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到因素 code=" + code));
  }

  /** 构造最小合法 request —— 每个用例按需补 resolverParams。 */
  private static PriceVariableRequest baseRequest(
      String code, String name, String factorType, String kind) {
    PriceVariableRequest req = new PriceVariableRequest();
    req.setVariableCode(code);
    req.setVariableName(name);
    req.setFactorType(factorType);
    req.setResolverKind(kind);
    return req;
  }

  /** 变参构造 Map<String,Object>，避免每个用例都写 new HashMap + put */
  private static Map<String, Object> map(Object... kv) {
    if (kv.length % 2 != 0) {
      throw new IllegalArgumentException("key-value 成对");
    }
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i].toString(), kv[i + 1]);
    }
    return m;
  }
}
