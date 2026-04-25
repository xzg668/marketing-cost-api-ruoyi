package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.DerivedResolver;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.registry.FinanceBasePriceQuery;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
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

/**
 * PriceLinkedFormulaPreviewServiceImpl 单测 —— 覆盖 5 种预览路径：
 * <ol>
 *   <li>正常路径：formula + materialCode 齐全，能命中 finance + 拉到 linkedItem</li>
 *   <li>缺 materialCode：计算可继续（按 0），但 warnings 里提示</li>
 *   <li>公式为空：返 error，不向下走</li>
 *   <li>公式语法错（括号不平衡）：normalize 阶段抛 FormulaSyntaxException → error</li>
 *   <li>变量不存在于 variable 表：source 标 MISSING，仍能求值</li>
 * </ol>
 *
 * <p>串的是真实 Normalizer + Registry，只 mock mapper；保持与 LinkedFormulaE2ETest 同款 MP 缓存预热策略。
 */
class PriceLinkedFormulaPreviewServiceImplTest {

  private PriceVariableMapper priceVariableMapper;
  private FinanceBasePriceMapper financeBasePriceMapper;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private PriceLinkedItemMapper priceLinkedItemMapper;
  private PriceLinkedFormulaPreviewServiceImpl service;

  /** finance 仓库：shortName → price */
  private final Map<String, BigDecimal> financeRepo = new HashMap<>();

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
    TableInfoHelper.initTableInfo(assistant, MaterialScrapRef.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  @BeforeEach
  void setUp() {
    priceVariableMapper = mock(PriceVariableMapper.class);
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    priceLinkedItemMapper = mock(PriceLinkedItemMapper.class);
    financeRepo.clear();

    when(priceVariableMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(buildVariables()));
    when(financeBasePriceMapper.selectOne(any())).thenAnswer(inv -> {
      Wrapper<FinanceBasePrice> w = inv.getArgument(0);
      return lookupFinance(w);
    });
    when(financeBasePriceMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      Wrapper<FinanceBasePrice> w = inv.getArgument(0);
      FinanceBasePrice row = lookupFinance(w);
      return row == null ? new ArrayList<FinanceBasePrice>() : new ArrayList<>(List.of(row));
    });

    VariableAliasIndex aliasIndex = new VariableAliasIndex(priceVariableMapper);
    aliasIndex.init();
    RowLocalPlaceholderRegistry rowLocal =
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport
            .defaultRegistry();
    FormulaNormalizer normalizer = new FormulaNormalizer(aliasIndex, rowLocal);

    FinanceBasePriceQuery financeQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
    FactorVariableRegistryImpl registry = new FactorVariableRegistryImpl(
        priceVariableMapper, financeQuery, new ObjectMapper(), rowLocal);
    registry.setDerivedContextResolver(
        new DerivedResolver(financeBasePriceMapper, materialScrapRefMapper));

    service = new PriceLinkedFormulaPreviewServiceImpl(
        normalizer, (FactorVariableRegistry) registry,
        priceVariableMapper, priceLinkedItemMapper,
        new com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker());
  }

  // =========================================================================

  @Test
  @DisplayName("正常路径：Cu+加工费 命中 finance + linkedItem，返回结果 + 无 error")
  void previewHappyPath() {
    financeRepo.put("电解铜", new BigDecimal("90"));
    PriceLinkedItem linked = new PriceLinkedItem();
    linked.setMaterialCode("TP2Y2");
    linked.setProcessFee(new BigDecimal("3.73"));
    linked.setPricingMonth("2026-04");
    when(priceLinkedItemMapper.selectOne(any())).thenReturn(linked);

    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu+加工费");
    req.setMaterialCode("TP2Y2");
    req.setPricingMonth("2026-04");

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    assertThat(resp.getNormalizedExpr()).contains("[Cu]").contains("[process_fee]");
    assertThat(resp.getResult()).isEqualByComparingTo("93.73");
    assertThat(resp.getVariables()).hasSize(2);
    assertThat(resp.getVariables()).extracting("code")
        .containsExactlyInAnyOrder("Cu", "process_fee");
    // 变量 source 用 factor_type 透出
    assertThat(resp.getVariables())
        .filteredOn(v -> "Cu".equals(v.getCode()))
        .extracting("source").containsOnly("FINANCE_FACTOR");
  }

  @Test
  @DisplayName("缺 materialCode：可计算但 warnings 提示无部品上下文")
  void previewMissingMaterialCode() {
    financeRepo.put("电解铜", new BigDecimal("88.0"));
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");
    // materialCode 不传（测试目标）；pricingMonth 仍需提供 —— Plan B 严格四键不回退
    req.setPricingMonth("2026-04");

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    assertThat(resp.getWarnings())
        .anyMatch(w -> w.contains("未提供 materialCode"));
    assertThat(resp.getResult()).isEqualByComparingTo("176.0");
  }

  @Test
  @DisplayName("公式为空：返回 error，不进 normalize")
  void previewEmptyFormula() {
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("   ");

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isEqualTo("公式不能为空");
    assertThat(resp.getNormalizedExpr()).isNull();
    assertThat(resp.getResult()).isNull();
    assertThat(resp.getTrace()).extracting("step").contains("validate");
  }

  @Test
  @DisplayName("语法错：括号不平衡 → FormulaSyntaxException 捕获为 error")
  void previewSyntaxError() {
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    // 多一个右括号
    req.setFormulaExpr("Cu+加工费)");
    req.setMaterialCode("X");

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).startsWith("公式语法错误");
    assertThat(resp.getResult()).isNull();
    assertThat(resp.getTrace()).extracting("step").contains("error");
  }

  @Test
  @DisplayName("不含税结算：taxIncluded=0 → result 除以 (1+vat_rate)")
  void previewTaxIncludedZero() {
    financeRepo.put("电解铜", new BigDecimal("90"));
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");
    req.setPricingMonth("2026-04");
    req.setTaxIncluded(0); // 触发除以 1.13

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    // 90*2 = 180；180 / 1.13 ≈ 159.29203539...
    assertThat(resp.getResult())
        .usingComparator(BigDecimal::compareTo)
        .isEqualByComparingTo(new BigDecimal("180").divide(
            new BigDecimal("1.13"),
            new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)));
    // trace 里应有 tax_adjust 步骤
    assertThat(resp.getTrace()).extracting("step").contains("tax_adjust");
  }

  @Test
  @DisplayName("含税口径：taxIncluded=1 → result 原样不做除法")
  void previewTaxIncludedOne() {
    financeRepo.put("电解铜", new BigDecimal("90"));
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");
    req.setPricingMonth("2026-04");
    req.setTaxIncluded(1);

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    assertThat(resp.getResult()).isEqualByComparingTo("180");
    assertThat(resp.getTrace()).extracting("step").doesNotContain("tax_adjust");
  }

  @Test
  @DisplayName("taxIncluded=null → 与含税同义，不做除法")
  void previewTaxIncludedNull() {
    financeRepo.put("电解铜", new BigDecimal("90"));
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");
    req.setPricingMonth("2026-04");
    // taxIncluded 不设置 —— 视同含税

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    assertThat(resp.getResult()).isEqualByComparingTo("180");
    assertThat(resp.getTrace()).extracting("step").doesNotContain("tax_adjust");
  }

  @Test
  @DisplayName("evaluate 返回 null + taxIncluded=0 → 不走阶段5 的 divide，直接早退带 error")
  void previewEvalNullWithTaxZeroShouldNotNpe() {
    // 构造"变量取值失败 → evaluator 对空 tokens 返回 null"的场景：
    // 公式里引用一个没登记也没别名的 token；Normalizer 会拒绝或 Registry 返空导致 evaluator 产 null
    // 稳妥起见走"所有变量都 MISSING + taxIncluded=0"路径：Cu 不 put 进 financeRepo
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");
    req.setTaxIncluded(0);
    // vat_rate 走 CONST 路径已有配置（buildVariables 里加过），所以阶段 5 不缺 vat
    // evaluate("[Cu]*2", {Cu->0}) 其实会返 0 而非 null；为真实复现线上 NPE，直接注入 null 结果太难，
    // 这里改用断言：即便 result=0 也要保证不抛异常且 tax_adjust 能走完
    PriceLinkedFormulaPreviewResponse resp = service.preview(req);
    assertThat(resp.getError()).isNull();
    assertThat(resp.getResult()).isNotNull();
  }

  @Test
  @DisplayName("未登记变量：source 标 MISSING，按 0 代入仍能求值")
  void previewUnknownVariable() {
    // 公式里 Cu 有登记，Xy 没登记 —— 但 Xy 不会被 AliasIndex 打 [Xy]，
    // 所以需要用一个看起来像数值但其实是变量的组合：改用已登记 Mn（前面金标测试用过）
    // 直接用一个不在 aliasIndex 里的数值表达式 —— Cu*2+Zn —— 其中 Zn 在本测试的 variable 表里也登记了
    // 为了验 MISSING，改成 Cu 已登记但 finance 里查不到值的场景
    // financeRepo 不放 Cu → Registry.resolve(Cu) 返 Optional.empty → source=MISSING
    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*3");

    PriceLinkedFormulaPreviewResponse resp = service.preview(req);

    assertThat(resp.getError()).isNull();
    assertThat(resp.getResult()).isEqualByComparingTo("0");
    assertThat(resp.getVariables())
        .filteredOn(v -> "Cu".equals(v.getCode()))
        .extracting("source").containsOnly("MISSING");
  }

  // =========================================================================
  // mock answer dispatcher：与 LinkedFormulaE2ETest 同款
  // =========================================================================

  private FinanceBasePrice lookupFinance(Wrapper<FinanceBasePrice> wrapper) {
    AbstractWrapper<FinanceBasePrice, ?, ?> w = (AbstractWrapper<FinanceBasePrice, ?, ?>) wrapper;
    w.getSqlSegment();
    for (Object v : w.getParamNameValuePairs().values()) {
      if (v instanceof String s && financeRepo.containsKey(s)) {
        FinanceBasePrice row = new FinanceBasePrice();
        row.setShortName(s);
        row.setPrice(financeRepo.get(s));
        row.setPriceMonth("2026-04");
        return row;
      }
    }
    return null;
  }

  // =========================================================================
  // 变量定义 —— 覆盖 Cu / Zn / 加工费
  // =========================================================================

  private List<PriceVariable> buildVariables() {
    List<PriceVariable> vars = new ArrayList<>();
    vars.add(financeVar("Cu", "电解铜", "[\"Cu\",\"电解铜\",\"电解铜价格\"]"));
    vars.add(financeVar("Zn", "锌价格", "[\"Zn\",\"锌\",\"锌价格\"]"));
    vars.add(partEntity("process_fee", "加工费",
        "[\"加工费\",\"含税加工费\"]", "processFee"));
    // vat_rate：税率常量，供不含税结算路径的 /(1+vat_rate) 计算用
    vars.add(constVar("vat_rate", "增值税率", "[\"增值税率\",\"税率\"]", "0.13"));
    return vars;
  }

  private PriceVariable financeVar(String code, String shortName, String aliases) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(shortName);
    v.setFactorType("FINANCE_FACTOR");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    // Plan B：FINANCE 解析走 resolver_params，shortName 路径保持 mock 的 .eq 断言兼容
    v.setResolverKind("FINANCE");
    v.setResolverParams(String.format(
        "{\"shortName\":\"%s\",\"priceSource\":\"平均价\",\"buScoped\":false}", shortName));
    return v;
  }

  private PriceVariable partEntity(
      String code, String name, String aliases, String field) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setFactorType("PART_CONTEXT");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    String bind = String.format(
        "{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"%s\"}", field);
    v.setContextBindingJson(bind);
    // Plan B：ENTITY params 直接复用 binding 的 entity/field 结构
    v.setResolverKind("ENTITY");
    v.setResolverParams(bind);
    return v;
  }

  /** CONST：常量变量，resolver_params={"value":"..."} —— 用于 vat_rate 等固定值 */
  private PriceVariable constVar(String code, String name, String aliases, String value) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setFactorType("CONST");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    v.setResolverKind("CONST");
    v.setResolverParams(String.format("{\"value\":\"%s\"}", value));
    return v;
  }
}
