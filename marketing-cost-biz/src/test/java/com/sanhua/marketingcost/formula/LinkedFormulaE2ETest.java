package com.sanhua.marketingcost.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.DerivedResolver;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.registry.FinanceBasePriceQuery;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.impl.PriceLinkedCalcServiceImpl;
import java.lang.reflect.Method;
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
 * 联动价端到端 E2E 测试（T12）—— 把真实的 Normalizer + AliasIndex + FactorVariableRegistryImpl
 * + DerivedResolver + ExpressionEvaluator 串起来跑多种公式形态，只 mock mapper 层返回数据。
 *
 * <p>覆盖场景（取自生产 Excel fixtures 典型形态）：
 * <ol>
 *   <li>材料+废料+加工费：{@code 下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格+含税加工费}</li>
 *   <li>Cu/Zn 权重 + 加工费：{@code (Cu*0.59+Zn*0.41)*下料重量+加工费}</li>
 *   <li>单位内嵌（元/Kg）+ 中文括号：{@code 下料重量×（50元/Kg）+加工费}</li>
 *   <li>派生铜沫（formulaRef）：{@code (下料重量-产品净重)*铜沫价格} —— 复用 ctx.override 的 Cu/Zn</li>
 *   <li>多层派生（SCRAP_REF）ratio=0.92</li>
 *   <li>无公式 → null</li>
 *   <li>finance 空数据 → 按 0 处理，不抛异常</li>
 * </ol>
 *
 * <p>Mock 策略：financeBasePriceMapper.selectOne/List 用 {@code thenAnswer} 单一入口，
 * 通过检查 {@link Wrapper#getParamNameValuePairs()} 里的字符串值做 shortName / factorCode 分派，
 * 避免多条 {@code when()} 互相覆盖。
 */
class LinkedFormulaE2ETest {

  private PriceVariableMapper priceVariableMapper;
  private FinanceBasePriceMapper financeBasePriceMapper;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private PriceLinkedCalcServiceImpl service;

  /** 动态 finance 仓库：shortName / factorCode → price；mock answer 统一从这里查 */
  private final Map<String, BigDecimal> financeRepo = new HashMap<>();

  /** 动态 scrap_ref 仓库：materialCode → (scrapCode, ratio) */
  private final Map<String, MaterialScrapRef> scrapRefRepo = new HashMap<>();

  /** 断言容忍度：元；生产要求 ≤ 0.01 */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  /**
   * 预注册 MyBatis-Plus TableInfo 缓存 —— 让 LambdaQueryWrapper 能解析
   * {@code FinanceBasePrice::getShortName} 到列名，否则 Wrapper 构建时会报
   * {@code "can not find lambda cache for this entity"}。
   */
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
    financeRepo.clear();
    scrapRefRepo.clear();

    // 把变量定义灌进 mapper；AliasIndex 启动扫描时加载；Registry 懒加载时也读同一 mock
    seedVariables(buildVariables());

    // finance mapper：统一 thenAnswer 根据 wrapper params 里的字符串匹配 financeRepo
    when(financeBasePriceMapper.selectOne(any())).thenAnswer(inv -> {
      Wrapper<FinanceBasePrice> w = inv.getArgument(0);
      return lookupFinance(w);
    });
    when(financeBasePriceMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      Wrapper<FinanceBasePrice> w = inv.getArgument(0);
      FinanceBasePrice row = lookupFinance(w);
      return row == null ? new ArrayList<FinanceBasePrice>() : new ArrayList<>(List.of(row));
    });

    // scrap_ref mapper：按 materialCode 查表（同样需要触发 getSqlSegment 填充 params）
    when(materialScrapRefMapper.selectOne(any())).thenAnswer(inv -> {
      AbstractWrapper<MaterialScrapRef, ?, ?> w = inv.getArgument(0);
      w.getSqlSegment();
      for (Object v : w.getParamNameValuePairs().values()) {
        if (v instanceof String s && scrapRefRepo.containsKey(s)) {
          return scrapRefRepo.get(s);
        }
      }
      return null;
    });

    // 手动组装真实 Normalizer 链：AliasIndex → FormulaNormalizer
    VariableAliasIndex aliasIndex = new VariableAliasIndex(priceVariableMapper);
    aliasIndex.init();
    RowLocalPlaceholderRegistry rowLocal =
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport
            .defaultRegistry();
    FormulaNormalizer normalizer = new FormulaNormalizer(aliasIndex, rowLocal);

    // 真实 Registry + DerivedResolver 注入；FINANCE 分支经 FinanceBasePriceQuery 严格四键查
    FinanceBasePriceQuery financeQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
    FactorVariableRegistryImpl registry = new FactorVariableRegistryImpl(
        priceVariableMapper, financeQuery, new ObjectMapper(), rowLocal);
    DerivedResolver derivedResolver = new DerivedResolver(
        financeBasePriceMapper, materialScrapRefMapper);
    registry.setDerivedContextResolver(derivedResolver);

    // service 只依赖上述真实管线 + 其它 mapper mock（不会被新路径触达）
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new"); // 新管线断言

    // 2026-04 重构：newCalculate 委托给 previewService.previewForRefresh，
    // E2E 测试需要真实 preview 实例（用相同的 normalizer + registry），否则路径打不通
    PriceLinkedItemMapper linkedItemMapperMock = mock(PriceLinkedItemMapper.class);
    com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl previewService =
        new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
            normalizer, registry, priceVariableMapper, linkedItemMapperMock,
            mock(com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker.class));

    service = new PriceLinkedCalcServiceImpl(
        mock(BomCostingRowMapper.class),
        mock(PriceLinkedCalcItemMapper.class),
        linkedItemMapperMock,
        priceVariableMapper,
        mock(OaFormMapper.class),
        financeBasePriceMapper,
        mock(DynamicValueMapper.class),
        props, normalizer, registry, new ObjectMapper(),
        previewService);
  }

  // ==========================================================================
  // 测试用例
  // ==========================================================================

  @org.junit.jupiter.api.Disabled("V34 起 B 组 token（材料含税价格/废料含税价格）语义改为行局部绑定（lp_price_variable_binding）。"
      + "等 T5 evaluator 支持 [__material]/[__scrap] 后改造为 binding-driven 用例并去掉 @Disabled。")
  @Test
  @DisplayName("场景1 部品联动：下料重量×材料价-废料×回收价+加工费")
  void partMaterialScrapProcessFee() throws Exception {
    // material_price_incl 走 MAIN_MATERIAL_FINANCE → materialCode=C3604A科宇
    // scrap_price_incl 走 SCRAP_REF：C3604A科宇 → H65黄铜边料，ratio=1.0
    financeRepo.put("C3604A科宇", new BigDecimal("52.81885"));
    financeRepo.put("H65黄铜边料", new BigDecimal("57.119"));
    scrapRefRepo.put("C3604A科宇", scrapRef("C3604A科宇", "H65黄铜边料", new BigDecimal("1.0")));

    // 下料重量(kg) × 材料含税价格 - (下料重量-产品净重)(kg) × 废料含税价格 + 含税加工费
    //   blankWeight=300g=0.3kg; netWeight=150g=0.15kg; processFee=0.74806
    //   expected = 0.3 × 52.81885 - 0.15 × 57.119 + 0.74806
    //            = 15.845655 - 8.56785 + 0.74806 = 8.025865
    PriceLinkedItem item = partPart(
        "C3604A科宇",
        "下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格+含税加工费",
        new BigDecimal("300"),
        new BigDecimal("150"),
        new BigDecimal("0.74806"));

    BigDecimal result = runCalc(item);
    assertThat(result).isNotNull();
    assertThat(result).isCloseTo(new BigDecimal("8.025865"), within(TOLERANCE));
  }

  @Test
  @DisplayName("场景2 Cu/Zn 权重：(Cu*0.59+Zn*0.41)×下料重量+加工费")
  void partCuZnWeightFormula() throws Exception {
    financeRepo.put("电解铜价格", new BigDecimal("90.0"));
    financeRepo.put("锌价格", new BigDecimal("21.6836"));

    // (90×0.59 + 21.6836×0.41) × 0.3 + 0.5
    //  = (53.1 + 8.890276) × 0.3 + 0.5
    //  = 61.990276 × 0.3 + 0.5 = 18.5970828 + 0.5 = 19.0970828
    PriceLinkedItem item = partPart(
        "X", "(Cu*0.59+Zn*0.41)*下料重量+加工费",
        new BigDecimal("300"), new BigDecimal("150"), new BigDecimal("0.5"));

    BigDecimal result = runCalc(item);
    assertThat(result).isCloseTo(new BigDecimal("19.097083"), within(TOLERANCE));
  }

  @Test
  @DisplayName("场景3 单位内嵌 + 中文括号：下料重量×（50元/Kg）+加工费")
  void partUnitAnnotationAndChineseBracket() throws Exception {
    // 纯常量公式 + 剥单位；0.3 × 50 + 0.2 = 15.2
    PriceLinkedItem item = partPart(
        "X", "下料重量*（50元/Kg）+加工费",
        new BigDecimal("300"), null, new BigDecimal("0.2"));

    BigDecimal result = runCalc(item);
    assertThat(result).isCloseTo(new BigDecimal("15.2"), within(TOLERANCE));
  }

  @Test
  @DisplayName("场景4 派生铜沫（formulaRef）：(下料重量-产品净重) × 铜沫价格")
  void partScrapFormulaRef() throws Exception {
    // 铜沫价格 binding 走 DERIVED FORMULA_REF: [Cu]*0.59+[Zn]*0.41
    financeRepo.put("电解铜价格", new BigDecimal("90.0"));
    financeRepo.put("锌价格", new BigDecimal("21.6836"));

    // 铜沫 = 90×0.59 + 21.6836×0.41 = 53.1 + 8.890276 = 61.990276
    // 结果 = (0.3 - 0.15) × 61.990276 = 9.298541
    PriceLinkedItem item = partPart(
        "X", "(下料重量-产品净重)*铜沫价格",
        new BigDecimal("300"), new BigDecimal("150"), null);

    BigDecimal result = runCalc(item);
    assertThat(result).isCloseTo(new BigDecimal("9.298541"), within(TOLERANCE));
  }

  @org.junit.jupiter.api.Disabled("V34 起 B 组 token 语义改为行局部绑定；等 T5 完成后重写为 binding-driven。")
  @Test
  @DisplayName("场景5 多层派生 SCRAP_REF：ratio=0.92 × 废料价")
  void partScrapRefWithRatio() throws Exception {
    financeRepo.put("C3604A科宇", new BigDecimal("52.81885"));
    financeRepo.put("H65黄铜边料", new BigDecimal("57.119"));
    scrapRefRepo.put("C3604A科宇", scrapRef("C3604A科宇", "H65黄铜边料", new BigDecimal("0.92")));

    // 下料重量×材料价 - (下料重量-产品净重)×(废料价×0.92)
    //   material=52.81885; scrap=57.119×0.92=52.54948
    //   = 0.3×52.81885 - 0.15×52.54948
    //   = 15.845655 - 7.882422 = 7.963233
    PriceLinkedItem item = partPart(
        "C3604A科宇",
        "下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格",
        new BigDecimal("300"), new BigDecimal("150"), null);

    BigDecimal result = runCalc(item);
    assertThat(result).isCloseTo(new BigDecimal("7.963233"), within(TOLERANCE));
  }

  @Test
  @DisplayName("场景6 无公式：返回 null")
  void emptyFormula() throws Exception {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("X");
    assertThat(runCalc(item)).isNull();
  }

  @Test
  @DisplayName("场景7 Cu 缺失（finance 查空）：按 0 处理，不抛异常")
  void missingFinancePrice() throws Exception {
    // financeRepo 不放 Cu；mock 的 answer 会返回 null（selectOne）+ 空列表（selectList）
    // Cu×2 + 加工费 = 0×2 + 0.5 = 0.5
    PriceLinkedItem item = partPart(
        "X", "Cu*2+加工费",
        new BigDecimal("100"), null, new BigDecimal("0.5"));

    BigDecimal result = runCalc(item);
    assertThat(result).isCloseTo(new BigDecimal("0.5"), within(TOLERANCE));
  }

  // ==========================================================================
  // Mock answer 分派：根据 wrapper 参数值查 financeRepo
  // ==========================================================================

  /**
   * 检查 wrapper 的 paramNameValuePairs 里是否有字符串匹配 financeRepo 的 key（shortName/factorCode），
   * 并要求有月份值（2026-04）或 wrapper 根本不含月份字段时才返回。
   * —— 简化实现：只要有 key 命中就返回对应 FinanceBasePrice。
   */
  private FinanceBasePrice lookupFinance(Wrapper<FinanceBasePrice> wrapper) {
    AbstractWrapper<FinanceBasePrice, ?, ?> w = (AbstractWrapper<FinanceBasePrice, ?, ?>) wrapper;
    // MP lambda wrapper 的参数是延迟填充的 —— 必须先触发一次 getSqlSegment() 才会把
    // eq() 过来的值写进 paramNameValuePairs（侧副作用），这是 MP 3.5.14 的实现行为。
    w.getSqlSegment();
    Map<String, Object> params = w.getParamNameValuePairs();
    for (Object v : params.values()) {
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

  // ==========================================================================
  // 变量定义构造 & mapper seed
  // ==========================================================================

  /** 构造完整变量定义表 —— 与 V24 seed 结构等价，够本测试用即可 */
  private List<PriceVariable> buildVariables() {
    List<PriceVariable> vars = new ArrayList<>();
    // FINANCE_FACTOR：Cu / Zn / 美国柜装黄铜
    vars.add(financeVar("Cu", "电解铜价格", "[\"Cu\",\"电解铜价格\",\"铜价\"]"));
    vars.add(financeVar("Zn", "锌价格", "[\"Zn\",\"锌价格\",\"锌\"]"));
    vars.add(financeVar("us_copper", "美国柜装黄铜",
        "[\"美国柜装黄铜\",\"美国柜装黄铜价格\"]"));

    // PART_CONTEXT ENTITY：下料重量 / 产品净重 / 加工费
    vars.add(partEntity("blank_weight", "下料重量",
        "[\"下料重量\"]", "blankWeight", "0.001"));
    vars.add(partEntity("net_weight", "产品净重",
        "[\"产品净重\"]", "netWeight", "0.001"));
    vars.add(partEntity("process_fee", "加工费",
        "[\"加工费\",\"含税加工费\"]", "processFee", null));

    // PART_CONTEXT DERIVED：材料含税价格 / 废料含税价格 / 铜沫价格
    vars.add(partDerived("material_price_incl", "材料含税价格",
        "[\"材料含税价格\"]",
        "{\"source\":\"DERIVED\",\"strategy\":\"MAIN_MATERIAL_FINANCE\"}"));
    vars.add(partDerived("scrap_price_incl", "废料含税价格",
        "[\"废料含税价格\"]",
        "{\"source\":\"DERIVED\",\"strategy\":\"SCRAP_REF\"}"));
    vars.add(partDerived("copper_scrap_price", "铜沫价格",
        "[\"铜沫价格\"]",
        "{\"source\":\"DERIVED\",\"strategy\":\"FORMULA_REF\",\"formulaRef\":\"[Cu]*0.59+[Zn]*0.41\"}"));

    return vars;
  }

  private static PriceVariable financeVar(String code, String shortName, String aliases) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(shortName);
    v.setFactorType("FINANCE_FACTOR");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    // Plan B：resolver_params 走 shortName 路径 —— 保持 mock 的 shortName.eq 断言不变
    v.setResolverKind("FINANCE");
    v.setResolverParams(String.format(
        "{\"shortName\":\"%s\",\"priceSource\":\"平均价\",\"buScoped\":false}", shortName));
    return v;
  }

  private static PriceVariable partEntity(
      String code, String name, String aliases, String field, String scale) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setFactorType("PART_CONTEXT");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    String bind = scale == null
        ? String.format("{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"%s\"}", field)
        : String.format("{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"%s\",\"unitScale\":\"%s\"}", field, scale);
    v.setContextBindingJson(bind);
    // Plan B：resolver_params 直接复用 context_binding_json 的 entity/field/unitScale 结构
    v.setResolverKind("ENTITY");
    v.setResolverParams(bind);
    return v;
  }

  private static PriceVariable partDerived(
      String code, String name, String aliases, String binding) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setFactorType("PART_CONTEXT");
    v.setStatus("active");
    v.setAliasesJson(aliases);
    v.setContextBindingJson(binding);
    // Plan B：DERIVED 的 params 原样复用 binding（含 strategy/formulaRef/factorCode 等键）
    v.setResolverKind("DERIVED");
    v.setResolverParams(binding);
    return v;
  }

  /** 把 variableMapper 全量 selectList 绑定到给定变量列表（AliasIndex + Registry 共享） */
  @SuppressWarnings("unchecked")
  private void seedVariables(List<PriceVariable> vars) {
    when(priceVariableMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(vars));
  }

  /** 构造联动部品；重量 g → 克，由 unitScale=0.001 换算为 kg */
  private PriceLinkedItem partPart(
      String materialCode, String formula,
      BigDecimal blankWeightG, BigDecimal netWeightG, BigDecimal processFee) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(materialCode);
    item.setPricingMonth("2026-04");
    item.setFormulaExpr(formula);
    item.setFormulaExprCn(formula);
    item.setBlankWeight(blankWeightG);
    item.setNetWeight(netWeightG);
    item.setProcessFee(processFee);
    return item;
  }

  private MaterialScrapRef scrapRef(String materialCode, String scrapCode, BigDecimal ratio) {
    MaterialScrapRef r = new MaterialScrapRef();
    r.setMaterialCode(materialCode);
    r.setScrapCode(scrapCode);
    r.setRatio(ratio);
    return r;
  }

  /** 反射调 private calculatePartUnitPrice */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private BigDecimal runCalc(PriceLinkedItem item) throws Exception {
    Method m = PriceLinkedCalcServiceImpl.class.getDeclaredMethod(
        "calculatePartUnitPrice",
        PriceLinkedItem.class, PriceLinkedCalcItem.class,
        com.sanhua.marketingcost.entity.OaForm.class,
        Map.class, Map.class);
    m.setAccessible(true);
    return (BigDecimal) m.invoke(service, item, new PriceLinkedCalcItem(), null,
        new HashMap(), new HashMap());
  }

  private static org.assertj.core.data.Offset<BigDecimal> within(BigDecimal tol) {
    return org.assertj.core.data.Offset.offset(tol);
  }
}
