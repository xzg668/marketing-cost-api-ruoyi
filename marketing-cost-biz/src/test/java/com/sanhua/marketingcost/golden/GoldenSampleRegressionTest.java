package com.sanhua.marketingcost.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 金标回归测试 —— 样本产品 1079900000536 / SHF-AA-79 端到端断言。
 *
 * <p>三层断言（严格度自下而上）：
 * <ul>
 *   <li><b>L0 夹具完整性</b>：JSON 能读、行数对、关键字段非空</li>
 *   <li><b>L1 部品价源命中</b>：35 行部品的 (formAttr, priceSource, unitPrice) 与 Excel 一致
 *       —— 待 CostRunPartItemServiceImpl 改 6 桶后启用</li>
 *   <li><b>L2 中间汇总</b>：材料费/直接人工/净损失/制造成本/调整后制造成本/三项费用合计
 *       —— 待 CostRunCostItemServiceImpl 校准水电口径与产品属性系数后启用</li>
 *   <li><b>L3 最终成本</b>：不含税总成本 152.503 ± 0.01
 *       —— 全链路改造完成后必须命中</li>
 * </ul>
 *
 * <p>当前状态：L0 直接跑（验证夹具）；L1/L2/L3 用 {@code goldenAssertion(...)} 折叠成
 * 断言桩，实现层就绪后逐个解开 {@code @Disabled}（不删，方便回滚对照）。
 *
 * <p>依赖：纯 JUnit 5 + Jackson；不启动 Spring，不连数据库。
 */
@DisplayName("金标回归 - SHF-AA-79")
class GoldenSampleRegressionTest {

  /**
   * MP LambdaQueryWrapper 需要 TableInfo 缓存才能解析 {@code Entity::getField} → 列名；
   * 本类在多个 Nested 里都会拿到 FinanceBasePrice / MaterialScrapRef 等实体构造 wrapper，
   * 一次性初始化并终身复用（MP 缓存是进程级单例）。
   */
  @BeforeAll
  static void initMybatisPlusTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
    TableInfoHelper.initTableInfo(assistant, MaterialScrapRef.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  /** L1 单价偏差容忍度（元） —— 部品级要求很严，几乎要求精确命中。 */
  private static final BigDecimal L1_TOLERANCE = new BigDecimal("0.001");

  /** L2 中间汇总偏差容忍度（元）。 */
  private static final BigDecimal L2_TOLERANCE = new BigDecimal("0.01");

  /** L3 最终成本偏差容忍度（元） —— 与设计文档验收口径一致。 */
  private static final BigDecimal L3_TOLERANCE = new BigDecimal("0.01");

  // =========================================================================
  // L0：夹具自检（无依赖，永远跑）
  // =========================================================================

  @Nested
  @DisplayName("L0 - 金标夹具完整性")
  class L0FixtureSanity {

    @Test
    @DisplayName("expected_summary 含 152.503 不含税总成本")
    void summaryHasFinalCost() {
      Map<String, Object> summary = GoldenSampleFixture.expectedSummary();
      assertThat(GoldenSampleFixture.big(summary, "totalCostExclTax"))
          .isCloseTo(GoldenSampleFixture.EXPECTED_TOTAL_COST_EXCL_TAX, within(L3_TOLERANCE));
      assertThat(GoldenSampleFixture.big(summary, "materialTotal"))
          .isCloseTo(new BigDecimal("93.493"), within(L2_TOLERANCE));
      assertThat(GoldenSampleFixture.big(summary, "manufactureCost"))
          .isCloseTo(new BigDecimal("111.316"), within(L2_TOLERANCE));
    }

    @Test
    @DisplayName("bom_items 共 29 行，且每行 unitPrice 非空")
    void bomItemsCountAndShape() {
      List<Map<String, Object>> items = GoldenSampleFixture.bomItems();
      assertThat(items).hasSize(29);
      assertThat(items)
          .allSatisfy(
              item -> {
                assertThat(item).containsKeys("partName", "drawingNo", "unitPrice", "qty",
                    "amount", "formAttr", "priceSource");
                assertThat(item.get("unitPrice")).isNotNull();
                assertThat(item.get("amount")).isNotNull();
              });
    }

    @Test
    @DisplayName("formAttr/priceSource 取值符合 Excel 实际口径")
    void formAttrAndSourceEnum() {
      // Excel 实际文案（与 design 文档枚举的映射，由 Router 服务负责翻译）：
      //   formAttr  : 采购 / 联动 / 自制 / 原材料联动 / 家用结算价
      //   priceSource: 固定采购价 / 联动价 / 自制件 / 原材料拆解 / 家用结算价
      List<Map<String, Object>> items = GoldenSampleFixture.bomItems();
      assertThat(items).extracting(i -> (String) i.get("formAttr"))
          .containsOnly("采购", "联动", "自制", "原材料联动", "家用结算价");
      assertThat(items).extracting(i -> (String) i.get("priceSource"))
          .containsOnly("固定采购价", "联动价", "自制件", "原材料拆解", "家用结算价");
    }

    @Test
    @DisplayName("aux_costs 包含 7 项产品级附加")
    void auxCostsShape() {
      Map<String, Object> aux = GoldenSampleFixture.auxCosts();
      assertThat(aux).containsKeys("pickling", "auxiliary", "solder", "packaging",
          "tooling", "overhaul", "waterPower");
    }

    @Test
    @DisplayName("labor_rate 含全部 7 个比率字段")
    void laborRateShape() {
      Map<String, Object> labor = GoldenSampleFixture.laborRate();
      assertThat(labor).containsKeys(
          "directLabor", "qualityLossRate", "manufactureRate",
          "productAttrCoefficient",
          "managementRate", "salesRate", "financeRate");
      // 产品属性系数 = 1（标准品）
      assertThat(GoldenSampleFixture.big(labor, "productAttrCoefficient"))
          .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("source 价源夹具至少含固定/联动/结算/自制/拆解 5 类")
    void sourceFixturesPresent() {
      assertThat(GoldenSampleFixture.priceFixed()).isNotEmpty();
      assertThat(GoldenSampleFixture.priceLinked()).isNotEmpty();
      assertThat(GoldenSampleFixture.priceSettle()).containsKey("rows");
      assertThat(GoldenSampleFixture.makePart()).containsKey("data");
      assertThat(GoldenSampleFixture.rawBreakdown()).containsKey("data");
    }
  }

  // =========================================================================
  // L1：部品价源命中（待 CostRunPartItemServiceImpl 改 6 桶后启用）
  // =========================================================================

  @Nested
  @DisplayName("L1 - 部品价源逐行命中")
  class L1PartPricing {

    @Test
    @DisplayName("[待启用] 35 行部品 (formAttr, priceSource, unitPrice) 与 Excel 一致")
    void allBomItemsMatchExcel() {
      // 启用条件：CostRunPartItemServiceImpl 完成 6 桶分发改造（任务 #5）
      // 执行思路：
      //   1) 用 fixtures/source/* 灌入 H2/Testcontainer
      //   2) 触发 CostRunPartItemService.refresh(oaNo)
      //   3) 查询 cost_run_part_item，与 GoldenSampleFixture.bomItems() 逐行比对
      //   4) 偏差 ≤ L1_TOLERANCE
      goldenAssertion("L1.allBomItemsMatchExcel");
    }
  }

  // =========================================================================
  // L1B：原材料联动 + 固定 17 行（T13 — 覆盖 raw_material_linked_fixed.json）
  // =========================================================================

  /**
   * 覆盖原材料联动表 17 条夹具，分为 3 类：
   * <ul>
   *   <li>联动 + 简单公式（{@code X+加工费}）：rows 1-3, 11-12 —— 逆推材料价后走真实管线，
   *       断言计算结果与夹具 {@code 单价} 严格一致（容忍度 {@link #L1_TOLERANCE}）</li>
   *   <li>联动 + 复杂公式（多材料加权）：rows 4-10 —— 不追求精确金标重算，改为校验
   *       {@link FormulaNormalizer} 能把 Excel 原文成功归一化且不抛
   *       {@link com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException}</li>
   *   <li>联动 + 废料引用（row 13，无公式）+ 固定（rows 14-16）：只做形态校验</li>
   * </ul>
   *
   * <p>DoD：17 行全绿 = 1 行表头元数据 + 16 行数据各自分桶通过。
   */
  @Nested
  @DisplayName("L1B - 原材料 17 行联动+固定")
  class L1RawMaterialLinkedFixed {

    /** 联动行主 token 的 finance 短名 —— 支持多种 Excel 写法（电解铜 / Cu） */
    private static final String TOKEN_COPPER = "电解铜";

    /** 简单公式的 token 固定为一个主材料 + 加工费 */
    private static final List<Integer> SIMPLE_LINKED_ROWS = List.of(1, 2, 3, 11, 12);

    /** 复杂多材料公式行（仅做 Normalizer parse 校验） */
    private static final List<Integer> COMPLEX_LINKED_ROWS = List.of(4, 5, 6, 7, 8, 9, 10);

    /** 废料联动行（无公式，靠 SCRAP_REF 派生） */
    private static final int SCRAP_LINKED_ROW = 13;

    /** 固定价行 */
    private static final List<Integer> FIXED_ROWS = List.of(14, 15, 16);

    private PriceVariableMapper priceVariableMapper;
    private FinanceBasePriceMapper financeBasePriceMapper;
    private MaterialScrapRefMapper materialScrapRefMapper;
    private PriceLinkedCalcServiceImpl service;
    private FormulaNormalizer normalizer;

    /** finance 仓库：shortName → price，简单场景动态 put。 */
    private final Map<String, BigDecimal> financeRepo = new HashMap<>();

    @BeforeEach
    void setUp() {
      priceVariableMapper = mock(PriceVariableMapper.class);
      financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
      materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
      financeRepo.clear();

      // 变量定义全量回灌给 mapper：AliasIndex 与 Registry 都从这里读
      when(priceVariableMapper.selectList(any(Wrapper.class)))
          .thenReturn(new ArrayList<>(buildRawMaterialVariables()));

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
      normalizer = new FormulaNormalizer(aliasIndex, rowLocal);

      FinanceBasePriceQuery financeQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
      FactorVariableRegistryImpl registry = new FactorVariableRegistryImpl(
          priceVariableMapper, financeQuery, new ObjectMapper(), rowLocal);
      DerivedResolver derivedResolver = new DerivedResolver(
          financeBasePriceMapper, materialScrapRefMapper);
      registry.setDerivedContextResolver(derivedResolver);

      LinkedParserProperties props = new LinkedParserProperties();
      props.setMode("new");

      // 2026-04 重构：newCalculate 委托给 previewService.previewForRefresh，
      // Golden 回归需要真实 preview 实例才能让路径走通
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

    @Test
    @DisplayName("夹具形态：17 行（row=0 表头 + 16 行数据），列数=24")
    @SuppressWarnings("unchecked")
    void shapeAndCount() {
      Map<String, Object> fixture = GoldenSampleFixture.rawMaterialLinkedFixed();
      Map<String, Object> dims = (Map<String, Object>) fixture.get("dimensions");
      assertThat(((Number) dims.get("rows")).intValue()).isEqualTo(17);
      assertThat(((Number) dims.get("cols")).intValue()).isEqualTo(24);

      List<Map<String, Object>> data = (List<Map<String, Object>>) fixture.get("data");
      assertThat(data).hasSize(17);

      // row 0 是表头，其 values 前 19 个与 Excel 字段名严格一致
      List<Object> headerValues = headerRow();
      assertThat(headerValues.get(6)).isEqualTo("物料代码");
      assertThat(headerValues.get(9)).isEqualTo("联动公式");
      assertThat(headerValues.get(14)).isEqualTo("单价");
      assertThat(headerValues.get(18)).isEqualTo("订单类型");
    }

    @Test
    @DisplayName("分类计数：13 条联动（含 1 条废料映射）+ 3 条固定 = 16 数据行")
    void classifyLinkedVsFixed() {
      int linked = 0, fixed = 0;
      for (int i = 1; i <= 16; i++) {
        // 数据行的"订单类型"落在 index=19（表头在 18，字段对齐相差一列是夹具原样）
        String orderType = stringAt(i, 19);
        if ("联动".equals(orderType)) {
          linked++;
        } else if ("固定".equals(orderType)) {
          fixed++;
        }
      }
      assertThat(linked).isEqualTo(13);
      assertThat(fixed).isEqualTo(3);
    }

    @Test
    @DisplayName("简单公式 5 行（电解铜+加工费 / Cu+加工费）端到端重算命中单价")
    void linkedSimpleFormulasReproduceUnitPrice() throws Exception {
      for (int rowIndex : SIMPLE_LINKED_ROWS) {
        String formula = stringAt(rowIndex, 9);
        BigDecimal processFee = decimalAt(rowIndex, 12);
        BigDecimal expectedUnitPrice = decimalAt(rowIndex, 14);
        assertThat(formula).as("row %d 公式", rowIndex).isNotBlank();
        assertThat(processFee).as("row %d 加工费", rowIndex).isNotNull();
        assertThat(expectedUnitPrice).as("row %d 单价", rowIndex).isNotNull();

        // 简单公式逆推：材料价 = 单价 - 加工费
        BigDecimal materialPrice = expectedUnitPrice.subtract(processFee);
        financeRepo.clear();
        // 两个主 token 都灌一下，哪个被公式 tag 到哪个就会命中
        financeRepo.put(TOKEN_COPPER, materialPrice);
        financeRepo.put("Cu", materialPrice);

        // 构造一个 PART：materialCode 用电解铜（主 token），下料重/净重给 1kg（公式不引用，但字段非 null 便于查 bind）
        PriceLinkedItem item = new PriceLinkedItem();
        item.setMaterialCode(TOKEN_COPPER);
        item.setPricingMonth("2026-04");
        item.setFormulaExpr(formula);
        item.setFormulaExprCn(formula);
        item.setProcessFee(processFee);
        // 公式本身不乘重量，所以下料重/净重不影响结果
        item.setBlankWeight(new BigDecimal("0"));
        item.setNetWeight(new BigDecimal("0"));

        BigDecimal actual = runCalc(item);
        assertThat(actual).as("row %d 单价重算", rowIndex).isNotNull();
        assertThat(actual)
            .as("row %d 公式=%s 加工费=%s 期望=%s", rowIndex, formula, processFee, expectedUnitPrice)
            .isCloseTo(expectedUnitPrice, within(L1_TOLERANCE));
      }
    }

    @Test
    @DisplayName("复杂公式 7 行：Normalizer 能成功归一化（不抛异常，且产出含 [var] 标签）")
    void linkedComplexFormulasNormalizable() {
      for (int rowIndex : COMPLEX_LINKED_ROWS) {
        String formula = stringAt(rowIndex, 9);
        assertThat(formula).as("row %d 公式", rowIndex).isNotBlank();

        String normalized = normalizer.normalize(formula);
        assertThat(normalized).as("row %d 归一化结果", rowIndex).isNotBlank();
        // 至少含一个 [xxx] 标签 —— 说明 AliasIndex 成功识别了至少一个变量
        assertThat(normalized).as("row %d 归一化含变量标签", rowIndex).contains("[");
        assertThat(normalized).as("row %d 归一化无全角括号残留", rowIndex)
            .doesNotContain("（").doesNotContain("）");
      }
    }

    @Test
    @DisplayName("废料联动行（row 13）：无公式，材料代码=A，单价 75.66 非空")
    void scrapLinkedRowShape() {
      String orderType = stringAt(SCRAP_LINKED_ROW, 19);
      String formula = stringAt(SCRAP_LINKED_ROW, 9);
      String materialCode = stringAt(SCRAP_LINKED_ROW, 6);
      BigDecimal unitPrice = decimalAt(SCRAP_LINKED_ROW, 14);

      assertThat(orderType).isEqualTo("联动");
      assertThat(formula).isNullOrEmpty();
      assertThat(materialCode).isEqualTo("A");
      assertThat(unitPrice).isNotNull();
      assertThat(unitPrice).isCloseTo(new BigDecimal("75.66"), within(new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("固定价 3 行：orderType=固定，无公式，单价非空")
    void fixedRowsShape() {
      for (int rowIndex : FIXED_ROWS) {
        String orderType = stringAt(rowIndex, 19);
        String formula = stringAt(rowIndex, 9);
        BigDecimal unitPrice = decimalAt(rowIndex, 14);

        assertThat(orderType).as("row %d orderType", rowIndex).isEqualTo("固定");
        assertThat(formula).as("row %d 公式应空", rowIndex).isNullOrEmpty();
        assertThat(unitPrice).as("row %d 单价非空", rowIndex).isNotNull();
        assertThat(unitPrice).as("row %d 单价为正", rowIndex)
            .isGreaterThan(BigDecimal.ZERO);
      }
    }

    // ========================== Mock answer / 夹具读取工具 ==========================

    /** 统一 finance 查询：从 wrapper 参数里摘出 String 值去 financeRepo 查。 */
    private FinanceBasePrice lookupFinance(Wrapper<FinanceBasePrice> wrapper) {
      AbstractWrapper<FinanceBasePrice, ?, ?> w = (AbstractWrapper<FinanceBasePrice, ?, ?>) wrapper;
      w.getSqlSegment(); // 触发 MP 惰性填充 paramNameValuePairs
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

    @SuppressWarnings("unchecked")
    private List<Object> headerRow() {
      Map<String, Object> fixture = GoldenSampleFixture.rawMaterialLinkedFixed();
      List<Map<String, Object>> data = (List<Map<String, Object>>) fixture.get("data");
      return (List<Object>) data.get(0).get("values");
    }

    /** 读第 {@code rowIndex} 行（0 为表头）第 {@code colIndex} 列的 String 值（null 返 null）。 */
    @SuppressWarnings("unchecked")
    private String stringAt(int rowIndex, int colIndex) {
      Map<String, Object> fixture = GoldenSampleFixture.rawMaterialLinkedFixed();
      List<Map<String, Object>> data = (List<Map<String, Object>>) fixture.get("data");
      List<Object> values = (List<Object>) data.get(rowIndex).get("values");
      Object v = values.get(colIndex);
      return v == null ? null : v.toString();
    }

    /** 读数值单元格为 BigDecimal（null 返 null），兼容 Jackson 解出的 Double/Integer/String。 */
    @SuppressWarnings("unchecked")
    private BigDecimal decimalAt(int rowIndex, int colIndex) {
      Map<String, Object> fixture = GoldenSampleFixture.rawMaterialLinkedFixed();
      List<Map<String, Object>> data = (List<Map<String, Object>>) fixture.get("data");
      List<Object> values = (List<Object>) data.get(rowIndex).get("values");
      Object v = values.get(colIndex);
      if (v == null) {
        return null;
      }
      return new BigDecimal(v.toString());
    }

    // ================================ 变量定义构造 ================================

    /** 构造原材料测试用变量定义表（覆盖所有 Excel 公式里出现过的 token）。 */
    private List<PriceVariable> buildRawMaterialVariables() {
      List<PriceVariable> vars = new ArrayList<>();
      // FINANCE_FACTOR 金属价格变量 —— alias 覆盖 Excel 中文/英文写法
      vars.add(financeVar("Cu", "电解铜", "[\"Cu\",\"电解铜\",\"电解铜价格\"]"));
      vars.add(financeVar("Zn", "锌价格", "[\"Zn\",\"锌价格\",\"锌\",\"电解锌\"]"));
      vars.add(financeVar("Ag", "白银价格", "[\"Ag\",\"白银\",\"白银价格\"]"));
      vars.add(financeVar("Sn", "锡价格", "[\"Sn\",\"锡\"]"));
      vars.add(financeVar("In", "铟价格", "[\"In\",\"铟\"]"));
      vars.add(financeVar("Pcu", "磷铜价格", "[\"Pcu\",\"磷铜\"]"));
      vars.add(financeVar("Mn", "锰价格", "[\"Mn\",\"锰\"]"));

      // PART_CONTEXT ENTITY：加工费 / 下料重 / 净重（PriceLinkedItem 字段映射）
      vars.add(partEntity("process_fee", "加工费",
          "[\"加工费\",\"含税加工费\"]", "processFee", null));
      vars.add(partEntity("blank_weight", "下料重量",
          "[\"下料重量\",\"下料重\"]", "blankWeight", "0.001"));
      vars.add(partEntity("net_weight", "产品净重",
          "[\"产品净重\",\"净重\"]", "netWeight", "0.001"));

      return vars;
    }

    private PriceVariable financeVar(String code, String shortName, String aliases) {
      PriceVariable v = new PriceVariable();
      v.setVariableCode(code);
      v.setVariableName(shortName);
      v.setFactorType("FINANCE_FACTOR");
      v.setStatus("active");
      v.setAliasesJson(aliases);
      // Plan B：FINANCE 走 resolver_params 的 shortName 路径（保持 mock 的 shortName.eq 断言兼容）
      v.setResolverKind("FINANCE");
      v.setResolverParams(String.format(
          "{\"shortName\":\"%s\",\"priceSource\":\"平均价\",\"buScoped\":false}", shortName));
      return v;
    }

    private PriceVariable partEntity(
        String code, String name, String aliases, String field, String scale) {
      PriceVariable v = new PriceVariable();
      v.setVariableCode(code);
      v.setVariableName(name);
      v.setFactorType("PART_CONTEXT");
      v.setStatus("active");
      v.setAliasesJson(aliases);
      String bind = scale == null
          ? String.format("{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"%s\"}", field)
          : String.format(
              "{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"%s\",\"unitScale\":\"%s\"}",
              field, scale);
      v.setContextBindingJson(bind);
      // Plan B：ENTITY params 直接复用 binding 的 entity/field/unitScale 结构
      v.setResolverKind("ENTITY");
      v.setResolverParams(bind);
      return v;
    }

    /** 反射调 PriceLinkedCalcServiceImpl#calculatePartUnitPrice（与 LinkedFormulaE2ETest 同款）。 */
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
  }

  // =========================================================================
  // L2：中间汇总（待水电口径 + 产品属性系数校准后启用）
  // =========================================================================

  @Nested
  @DisplayName("L2 - 中间汇总命中")
  class L2Subtotals {

    @Test
    @DisplayName("[待启用] materialTotal = 93.493（水电不计入）")
    void materialTotalExcludesWaterPower() {
      // 启用条件：任务 #9 把水电从材料费剔除
      goldenAssertion("L2.materialTotal");
    }

    @Test
    @DisplayName("[待启用] manufactureCost = 111.316（内含法 ÷(1-0.12)）")
    void manufactureCostByInternalContainmentFormula() {
      // (材料费 + 直接人工 + 净损失) / (1 - 制造费用率) = 111.316
      goldenAssertion("L2.manufactureCost");
    }

    @Test
    @DisplayName("[待启用] adjustedManufactureCost = manufactureCost × productAttrCoefficient")
    void adjustedManufactureCostByCoefficient() {
      // 启用条件：任务 #3 给 lp_product_property 加 coefficient + 任务 #9 应用系数
      goldenAssertion("L2.adjustedManufactureCost");
    }

    @Test
    @DisplayName("[待启用] 三项费用 = 调整后制造成本 × (0.10 + 0.25 + 0.02)")
    void threeExpensesByAdjustedCost() {
      goldenAssertion("L2.threeExpenses");
    }
  }

  // =========================================================================
  // L3：最终成本（全链路完成后必须命中）
  // =========================================================================

  @Nested
  @DisplayName("L3 - 不含税总成本 152.503 ± 0.01")
  class L3FinalCost {

    @Test
    @DisplayName("[待启用] 端到端核算命中 152.503")
    void endToEndTotalCostExclTax() {
      // 启用条件：任务 #3-#9 全部完成
      // 执行思路：
      //   1) 灌 fixtures/source/* + golden 表头到测试库
      //   2) 触发 CostRunTrialService.run(oaNo) 同步等结果
      //   3) 读 lp_cost_run_result.total_cost_excl_tax
      //   4) assertThat(actual).isCloseTo(152.503, within(0.01))
      goldenAssertion("L3.totalCostExclTax");
    }
  }

  /**
   * 占位断言桩 —— 实现层就绪前抛 {@link org.opentest4j.TestAbortedException}（JUnit "skipped"），
   * 而不是 fail。这样 CI 上能直观看到"待启用项还有几个"，又不会让人误以为已通过。
   */
  private static void goldenAssertion(String tag) {
    org.junit.jupiter.api.Assumptions.abort(
        "[" + tag + "] 待实现层就绪后启用，详见类注释");
  }

  // =========================================================================
  // L1C：T27 三模式金标 —— legacy / dual / new parity
  //
  // 设计动机：T27 原卡命令行走 Spring profile 覆盖 cost.linked.parser.mode，
  // 但现有 GoldenSampleRegressionTest 是纯 mockito 单元测试（不启动 Spring），
  // profile 不生效。本 Nested 通过构造期显式 setMode(...) 兑现三模式回归诉求。
  //
  // 公式形态：选用已归一化的 "[Cu]+[process_fee]" —— 这是 legacy/new 引擎共同
  // 识别的子集。legacy 的 VARIABLE_PATTERN 只认 ASCII 裸标识符或 [中文/英文]
  // 包裹；裸中文（如 "电解铜+加工费"）legacy 不识别，这是 legacy 的设计局限
  // 而非 bug，三模式 parity 只能在归一化语法上验证。
  //
  // DoD：
  //   - legacy/dual/new 三模式结果都 = 12.5（容差 L3_TOLERANCE）
  //   - dual 内部 diff = 0（由 legacy=new 的等价性隐式满足 → 不打 WARN）
  // =========================================================================
  @Nested
  @DisplayName("L1C - T27 三模式 parity（[Cu]+[process_fee]）")
  class L1CThreeModeParity {

    /** 预归一化公式，legacy/new 双管线都能解析 */
    private static final String EXPR = "[Cu]+[process_fee]";

    /** 金属价 10 + 加工费 2.5 = 12.5，三模式不变 */
    private static final BigDecimal EXPECTED = new BigDecimal("12.5");

    private static final String PRICING_MONTH = "2026-04";
    private static final BigDecimal CU_PRICE = new BigDecimal("10.0");
    private static final BigDecimal PROCESS_FEE = new BigDecimal("2.5");

    @org.junit.jupiter.params.ParameterizedTest(name = "mode={0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"legacy", "dual", "new"})
    @DisplayName("[Cu]+[process_fee] 三模式同值 12.5（dual 差异隐式为 0）")
    void threeModeParityOnNormalizedExpr(String mode) throws Exception {
      // 1) 变量定义：同时填 legacy 字段（sourceTable/sourceField）与 new 字段
      //    （factorType/aliases/contextBinding），让两条管线都能解析同一条 PriceVariable
      List<PriceVariable> variables = buildLegacyAndNewCompatibleVariables();

      // 2) mapper mock：variable list / finance selectList(IN) / finance selectOne(4-key)
      PriceVariableMapper priceVariableMapper = mock(PriceVariableMapper.class);
      when(priceVariableMapper.selectList(any(Wrapper.class)))
          .thenReturn(new ArrayList<>(variables));

      FinanceBasePriceMapper financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
      // new 管线走 selectOne（4-key 精确查）
      when(financeBasePriceMapper.selectOne(any())).thenAnswer(
          inv -> makeFinanceRowIfMatchesCu(inv.getArgument(0)));
      // legacy 管线走 selectList（IN 查 shortName）
      when(financeBasePriceMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
        FinanceBasePrice row = makeFinanceRowIfMatchesCu(inv.getArgument(0));
        return row == null ? new ArrayList<FinanceBasePrice>() : new ArrayList<>(List.of(row));
      });

      // 3) 组装新引擎组件（alias 索引 / normalizer / registry）—— 即使 mode=legacy，
      //    PriceLinkedCalcServiceImpl 构造仍需要这些 bean 非空（runLegacy/runNew 决定实际走谁）
      VariableAliasIndex aliasIndex = new VariableAliasIndex(priceVariableMapper);
      aliasIndex.init();
      RowLocalPlaceholderRegistry rowLocal2 =
          com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport
              .defaultRegistry();
      FormulaNormalizer normalizer = new FormulaNormalizer(aliasIndex, rowLocal2);

      FinanceBasePriceQuery financeQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
      FactorVariableRegistryImpl registry = new FactorVariableRegistryImpl(
          priceVariableMapper, financeQuery, new ObjectMapper(), rowLocal2);
      DerivedResolver derivedResolver = new DerivedResolver(
          financeBasePriceMapper, mock(MaterialScrapRefMapper.class));
      registry.setDerivedContextResolver(derivedResolver);

      // 4) mode 注入 —— T27 关键点
      LinkedParserProperties props = new LinkedParserProperties();
      props.setMode(mode);

      // 2026-04 重构：newCalculate 委托给 previewService.previewForRefresh —— 必须真实实例
      PriceLinkedItemMapper linkedItemMapperMock2 = mock(PriceLinkedItemMapper.class);
      com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl previewService2 =
          new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
              normalizer, registry, priceVariableMapper, linkedItemMapperMock2,
              mock(com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker.class));
      PriceLinkedCalcServiceImpl service = new PriceLinkedCalcServiceImpl(
          mock(BomCostingRowMapper.class),
          mock(PriceLinkedCalcItemMapper.class),
          linkedItemMapperMock2,
          priceVariableMapper,
          mock(OaFormMapper.class),
          financeBasePriceMapper,
          mock(DynamicValueMapper.class),
          props, normalizer, registry, new ObjectMapper(),
          previewService2);

      // 5) 预构造 legacy 需要的 variableMap / financePriceMap（new 管线不读这俩）
      Map<String, PriceVariable> variableMap = new HashMap<>();
      for (PriceVariable v : variables) {
        variableMap.put(v.getVariableCode(), v);
      }
      Map<String, Map<String, BigDecimal>> financePriceMap = new HashMap<>();
      Map<String, BigDecimal> cuByMonth = new HashMap<>();
      cuByMonth.put(PRICING_MONTH, CU_PRICE);
      cuByMonth.put("__latest__", CU_PRICE);
      financePriceMap.put("Cu", cuByMonth);

      // 6) 组装 linkedItem + 反射调用
      PriceLinkedItem item = new PriceLinkedItem();
      item.setMaterialCode("Cu");
      item.setPricingMonth(PRICING_MONTH);
      item.setFormulaExpr(EXPR);
      item.setFormulaExprCn(EXPR);
      item.setProcessFee(PROCESS_FEE);
      item.setBlankWeight(BigDecimal.ZERO);
      item.setNetWeight(BigDecimal.ZERO);

      Method m = PriceLinkedCalcServiceImpl.class.getDeclaredMethod(
          "calculatePartUnitPrice",
          PriceLinkedItem.class, PriceLinkedCalcItem.class,
          com.sanhua.marketingcost.entity.OaForm.class,
          Map.class, Map.class);
      m.setAccessible(true);
      BigDecimal actual = (BigDecimal) m.invoke(
          service, item, new PriceLinkedCalcItem(), null, variableMap, financePriceMap);

      // 7) 断言：三模式结果完全一致（dual 内部 legacy=new → diff=0 不 WARN）
      assertThat(actual).as("mode=%s 下 [Cu]+[process_fee] 应 = %s", mode, EXPECTED)
          .isNotNull()
          .isCloseTo(EXPECTED, within(L3_TOLERANCE));
    }

    /**
     * 构造同时满足 legacy 与 new 双管线字段需求的 PriceVariable 列表。
     * <ul>
     *   <li>Cu：legacy 用 sourceTable="lp_finance_base_price"+variableName 作 shortName；
     *           new 用 factorType=FINANCE_FACTOR + aliases + FINANCE_BASE 绑定</li>
     *   <li>process_fee：legacy 用 sourceTable="lp_price_linked_item"+sourceField="processFee"；
     *                     new 用 factorType=PART_CONTEXT + ENTITY 绑定</li>
     * </ul>
     */
    private List<PriceVariable> buildLegacyAndNewCompatibleVariables() {
      List<PriceVariable> vars = new ArrayList<>();

      PriceVariable cu = new PriceVariable();
      cu.setVariableCode("Cu");
      cu.setVariableName("Cu"); // legacy shortName → finance IN 查 + financePriceMap key
      cu.setSourceTable("lp_finance_base_price");
      cu.setFactorType("FINANCE_FACTOR");
      cu.setStatus("active");
      cu.setAliasesJson("[\"Cu\",\"电解铜\"]");
      cu.setContextBindingJson(
          "{\"factorCode\":\"Cu\",\"priceSource\":\"长江现货平均价\"}");
      // Plan B：new 管线读 resolver_params；factorCode 路径 + 精确 priceSource
      cu.setResolverKind("FINANCE");
      cu.setResolverParams(
          "{\"factorCode\":\"Cu\",\"priceSource\":\"长江现货平均价\",\"buScoped\":false}");
      vars.add(cu);

      PriceVariable processFee = new PriceVariable();
      processFee.setVariableCode("process_fee");
      processFee.setVariableName("加工费");
      processFee.setSourceTable("lp_price_linked_item");
      processFee.setSourceField("processFee");
      processFee.setFactorType("PART_CONTEXT");
      processFee.setStatus("active");
      processFee.setAliasesJson("[\"process_fee\",\"加工费\"]");
      processFee.setContextBindingJson(
          "{\"source\":\"ENTITY\",\"entity\":\"linkedItem\",\"field\":\"processFee\"}");
      // Plan B：new 管线读 resolver_params —— 直接复用 binding 的 entity/field 结构
      processFee.setResolverKind("ENTITY");
      processFee.setResolverParams(
          "{\"entity\":\"linkedItem\",\"field\":\"processFee\"}");
      vars.add(processFee);

      return vars;
    }

    /**
     * 统一 finance 查询：无论 selectOne / selectList(IN)，只要 wrapper 里任一 param
     * 是 "Cu"（legacy IN / new 4-key 都会带 factorCode 或 shortName），就返回 Cu 金标行。
     */
    private FinanceBasePrice makeFinanceRowIfMatchesCu(Object wrapperObj) {
      AbstractWrapper<FinanceBasePrice, ?, ?> w =
          (AbstractWrapper<FinanceBasePrice, ?, ?>) wrapperObj;
      w.getSqlSegment(); // 触发 MP 惰性填充 paramNameValuePairs
      boolean hitCu = false;
      for (Object v : w.getParamNameValuePairs().values()) {
        if (v instanceof String s && "Cu".equals(s)) {
          hitCu = true;
          break;
        }
      }
      if (!hitCu) {
        return null;
      }
      FinanceBasePrice row = new FinanceBasePrice();
      row.setFactorCode("Cu");
      row.setShortName("Cu");
      row.setPriceMonth(PRICING_MONTH);
      row.setPriceSource("长江现货平均价");
      row.setPrice(CU_PRICE);
      return row;
    }
  }
}
