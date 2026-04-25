package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PriceLinkedCalcServiceImplTest {

  @Test
  void page_returnsPagedRecords() {
    // T5.5：数据源切到 BomCostingRowMapper，条目也变成 BomCostingRow
    BomCostingRowMapper mapper = Mockito.mock(BomCostingRowMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            mapper,
            calcMapper,
            linkedItemMapper,
            variableMapper,
            oaFormMapper,
            financeBasePriceMapper,
            dynamicValueMapper,
            new LinkedParserProperties(),
            Mockito.mock(FormulaNormalizer.class),
            Mockito.mock(FactorVariableRegistry.class),
            new ObjectMapper(),
            Mockito.mock(PriceLinkedFormulaPreviewService.class));

    BomCostingRow record = new BomCostingRow();
    record.setOaNo("OA-001");
    record.setMaterialCode("MAT-1");

    Page<BomCostingRow> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);
    when(calcMapper.selectList(any())).thenReturn(List.of());

    var result = service.page("OA-001", null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals("OA-001", result.getRecords().get(0).getOaNo());
  }

  @Test
  void refresh_insertsCalculatedItems() {
    // T5.5：数据源切到 BomCostingRowMapper；bomQty 映射到 qtyPerTop
    BomCostingRowMapper mapper = Mockito.mock(BomCostingRowMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            mapper,
            calcMapper,
            linkedItemMapper,
            variableMapper,
            oaFormMapper,
            financeBasePriceMapper,
            dynamicValueMapper,
            new LinkedParserProperties(),
            Mockito.mock(FormulaNormalizer.class),
            Mockito.mock(FactorVariableRegistry.class),
            new ObjectMapper(),
            Mockito.mock(PriceLinkedFormulaPreviewService.class));

    BomCostingRow item = new BomCostingRow();
    item.setOaNo("OA-001");
    item.setMaterialCode("MAT-1");
    item.setShapeAttr("制造件");
    item.setQtyPerTop(new BigDecimal("2.5"));

    when(mapper.selectList(any())).thenReturn(List.of(item));
    when(calcMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of());
    when(variableMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(financeBasePriceMapper.selectList(any())).thenReturn(List.of());

    int inserted = service.refresh("OA-001");

    assertEquals(1, inserted);
    ArgumentCaptor<com.sanhua.marketingcost.entity.PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(com.sanhua.marketingcost.entity.PriceLinkedCalcItem.class);
    Mockito.verify(calcMapper).insert(captor.capture());
    var saved = captor.getValue();
    assertEquals(new BigDecimal("2.5"), saved.getBomQty());
    assertEquals(null, saved.getPartUnitPrice());
    assertEquals(null, saved.getPartAmount());
  }

  /**
   * {@code getTrace} 三分支覆盖 —— id=null / linked_item 不存在 / 存在。
   *
   * <p>重构后 trace 不再读 calc_item，而是按 linked_item.id 当场跑 preview；
   * 所以 mock 目标改成 {@code linkedItemMapper.selectById} + {@code previewService.preview}。
   */
  @Test
  void getTrace_covers_null_missing_and_present() {
    BomCostingRowMapper mapper = Mockito.mock(BomCostingRowMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedFormulaPreviewService previewService =
        Mockito.mock(PriceLinkedFormulaPreviewService.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            mapper,
            calcMapper,
            linkedItemMapper,
            variableMapper,
            oaFormMapper,
            financeBasePriceMapper,
            dynamicValueMapper,
            new LinkedParserProperties(),
            Mockito.mock(FormulaNormalizer.class),
            Mockito.mock(FactorVariableRegistry.class),
            new ObjectMapper(),
            previewService);

    // 分支 1：id=null 直接返 null，不触达任何依赖
    assertNull(service.getTrace(null));
    Mockito.verifyNoInteractions(linkedItemMapper);
    Mockito.verifyNoInteractions(previewService);

    // 分支 2：linked_item 不存在 —— selectById 返 null
    when(linkedItemMapper.selectById(999L)).thenReturn(null);
    assertNull(service.getTrace(999L));
    Mockito.verifyNoInteractions(previewService);

    // 分支 3：linked_item 存在 —— preview 产出结构化 trace，摊平成前端 schema
    PriceLinkedItem linked = new PriceLinkedItem();
    linked.setId(42L);
    linked.setFormulaExpr("Cu+process_fee");
    linked.setMaterialCode("MAT-42");
    linked.setPricingMonth("2026-02");
    when(linkedItemMapper.selectById(42L)).thenReturn(linked);

    PriceLinkedFormulaPreviewResponse previewResp = new PriceLinkedFormulaPreviewResponse();
    previewResp.setNormalizedExpr("[Cu]+[process_fee]");
    previewResp.setResult(new BigDecimal("93.73"));
    VariableDetail cu = new VariableDetail();
    cu.setCode("Cu");
    cu.setValue(new BigDecimal("88.73"));
    VariableDetail fee = new VariableDetail();
    fee.setCode("process_fee");
    fee.setValue(new BigDecimal("5.00"));
    previewResp.setVariables(List.of(cu, fee));
    when(previewService.preview(any(PriceLinkedFormulaPreviewRequest.class)))
        .thenReturn(previewResp);

    PriceLinkedCalcTraceResponse trace = service.getTrace(42L);
    assertThat(trace).isNotNull();
    assertThat(trace.getId()).isEqualTo(42L);
    // 前端期待的平铺 schema：rawExpr / normalizedExpr / variables / result
    assertThat(trace.getTraceJson()).contains("\"rawExpr\":\"Cu+process_fee\"");
    assertThat(trace.getTraceJson()).contains("\"normalizedExpr\":\"[Cu]+[process_fee]\"");
    assertThat(trace.getTraceJson()).contains("\"Cu\":88.73");
    assertThat(trace.getTraceJson()).contains("\"result\":93.73");
  }

  // ================== V28 收尾：OA 锁价 + 基价回落 单测 ==================

  /** 构造一个带所有必要 mock 依赖的 service 实例，便于多个 case 复用。 */
  private PriceLinkedCalcServiceImpl buildService(FinanceBasePriceMapper financeBasePriceMapper) {
    return new PriceLinkedCalcServiceImpl(
        Mockito.mock(BomCostingRowMapper.class),
        Mockito.mock(PriceLinkedCalcItemMapper.class),
        Mockito.mock(PriceLinkedItemMapper.class),
        Mockito.mock(PriceVariableMapper.class),
        Mockito.mock(OaFormMapper.class),
        financeBasePriceMapper,
        Mockito.mock(DynamicValueMapper.class),
        new LinkedParserProperties(),
        Mockito.mock(FormulaNormalizer.class),
        Mockito.mock(FactorVariableRegistry.class),
        new ObjectMapper(),
        Mockito.mock(PriceLinkedFormulaPreviewService.class));
  }

  /**
   * 反射调用 private applyOaLockPrice —— 不改可见性，避免只为测试污染生产 API。
   */
  private void invokeApplyOaLockPrice(
      PriceLinkedCalcServiceImpl service, VariableContext ctx, OaForm oaForm) throws Exception {
    Method m = PriceLinkedCalcServiceImpl.class
        .getDeclaredMethod("applyOaLockPrice", VariableContext.class, OaForm.class);
    m.setAccessible(true);
    m.invoke(service, ctx, oaForm);
  }

  /** 反射调用 private resolveFinanceFallback */
  private BigDecimal invokeResolveFinanceFallback(
      PriceLinkedCalcServiceImpl service, PriceVariable variable, PriceLinkedItem linkedItem)
      throws Exception {
    Method m = PriceLinkedCalcServiceImpl.class
        .getDeclaredMethod("resolveFinanceFallback", PriceVariable.class, PriceLinkedItem.class);
    m.setAccessible(true);
    return (BigDecimal) m.invoke(service, variable, linkedItem);
  }

  @Test
  void applyOaLockPrice_filledCopper_putsDivided() throws Exception {
    PriceLinkedCalcServiceImpl service = buildService(Mockito.mock(FinanceBasePriceMapper.class));

    // OA 单锁了 Cu=72000 元/吨（= 72 元/千克），其它金属未填
    OaForm oaForm = new OaForm();
    oaForm.setCopperPrice(new BigDecimal("72000"));

    VariableContext ctx = new VariableContext();
    invokeApplyOaLockPrice(service, ctx, oaForm);

    assertThat(ctx.getOverrides()).containsKey("Cu");
    assertThat(ctx.getOverrides().get("Cu")).isEqualByComparingTo(new BigDecimal("72"));
    // 未填字段不应出现在 overrides，让 FinanceBaseResolver 走基价回落
    assertThat(ctx.getOverrides()).doesNotContainKeys("Zn", "Al");
  }

  @Test
  void applyOaLockPrice_nullOaForm_noop() throws Exception {
    PriceLinkedCalcServiceImpl service = buildService(Mockito.mock(FinanceBasePriceMapper.class));
    VariableContext ctx = new VariableContext();
    invokeApplyOaLockPrice(service, ctx, null);
    assertThat(ctx.getOverrides()).isEmpty();
  }

  @Test
  void resolveFinanceFallback_hits_returnsPrice() throws Exception {
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("70"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    PriceLinkedCalcServiceImpl service = buildService(financeBasePriceMapper);

    // variable_code=Cu 对应 lp_finance_base_price.factor_code=Cu
    PriceVariable variable = new PriceVariable();
    variable.setVariableCode("Cu");

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setPricingMonth("2026-03");

    BigDecimal result = invokeResolveFinanceFallback(service, variable, linkedItem);
    assertThat(result).isEqualByComparingTo(new BigDecimal("70"));
  }

  @Test
  void resolveFinanceFallback_miss_returnsNull() throws Exception {
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    when(financeBasePriceMapper.selectOne(any())).thenReturn(null);

    PriceLinkedCalcServiceImpl service = buildService(financeBasePriceMapper);

    PriceVariable variable = new PriceVariable();
    variable.setVariableCode("Cu");
    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setPricingMonth("2026-03");

    assertNull(invokeResolveFinanceFallback(service, variable, linkedItem));
  }

  @Test
  void resolveFinanceFallback_missingMonth_returnsNull() throws Exception {
    PriceLinkedCalcServiceImpl service = buildService(Mockito.mock(FinanceBasePriceMapper.class));

    PriceVariable variable = new PriceVariable();
    variable.setVariableCode("Cu");
    // linkedItem.pricingMonth=null → 无法查基价，直接返 null，避免无条件全表扫
    PriceLinkedItem linkedItem = new PriceLinkedItem();

    assertNull(invokeResolveFinanceFallback(service, variable, linkedItem));
  }

  // ==================== 风险 2：vat_rate 缺失时 newCalculate 硬错 ====================

  /** 反射调 private calculatePartUnitPrice —— 不开 public API 供测试用。 */
  @SuppressWarnings("unchecked")
  private BigDecimal invokeCalculatePartUnitPrice(
      PriceLinkedCalcServiceImpl service,
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm) throws Exception {
    Method m = PriceLinkedCalcServiceImpl.class.getDeclaredMethod(
        "calculatePartUnitPrice",
        PriceLinkedItem.class,
        PriceLinkedCalcItem.class,
        OaForm.class,
        java.util.Map.class,
        java.util.Map.class);
    m.setAccessible(true);
    return (BigDecimal) m.invoke(
        service, linkedItem, calcItem, oaForm, java.util.Map.of(), java.util.Map.of());
  }

  /**
   * tax_included=0 + vat_rate 解析失败 → newCalculate 抛 IllegalStateException，
   * 被外层 catch 吞成 trace.error，calcItem.traceJson 里必须包含 vat_rate 错误线索，
   * 且返回 null（避免写错的 partUnitPrice）。
   *
   * <p>这是风险 2 的核心：以前 WARN 跳过会让结果偷偷偏高 13%；现在硬错，前端能看到。
   */
  @Test
  void newCalculate_taxIncludedZero_vatRateMissing_throwsWithClearError() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    // 让其他变量都能 resolve，只让 vat_rate 返回 empty —— 精准触发目标分支
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(inv -> {
          String code = inv.getArgument(0);
          if ("vat_rate".equals(code)) {
            return java.util.Optional.empty();
          }
          return java.util.Optional.of(new BigDecimal("88"));
        });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new"); // 纯 new 路径，避免 legacy 兜底掩盖 new 的错误

    PriceLinkedCalcServiceImpl service =
        buildServiceWithRealPreview(props, normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]");
    linkedItem.setMaterialCode("MAT-VAT-MISS");
    linkedItem.setTaxIncluded(0); // 触发不含税转换路径

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalculatePartUnitPrice(service, linkedItem, calcItem, null);

    // 硬错：返回 null（而非偷偷偏高的数）
    assertNull(result);
    // trace 写了明确错误原因 —— 前端弹窗红条能看到 "vat_rate"
    assertThat(calcItem.getTraceJson()).isNotNull();
    assertThat(calcItem.getTraceJson()).contains("vat_rate");
    assertThat(calcItem.getTraceJson()).contains("error");
  }

  /** 2026-04 重构后：newCalculate 委托给 previewService，测试需要真实 preview 实例。 */
  private PriceLinkedCalcServiceImpl buildServiceWithRealPreview(
      LinkedParserProperties props,
      FormulaNormalizer normalizer,
      FactorVariableRegistry registry) {
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper priceVariableMapperForPreview = Mockito.mock(PriceVariableMapper.class);
    when(priceVariableMapperForPreview.selectList(any()))
        .thenReturn(java.util.List.of()); // loadVariableMeta 空，用 token 做名字即可
    com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl previewService =
        new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
            normalizer, registry, priceVariableMapperForPreview, linkedItemMapper,
            Mockito.mock(com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker.class));
    return new PriceLinkedCalcServiceImpl(
        Mockito.mock(BomCostingRowMapper.class),
        Mockito.mock(PriceLinkedCalcItemMapper.class),
        linkedItemMapper,
        Mockito.mock(PriceVariableMapper.class),
        Mockito.mock(OaFormMapper.class),
        Mockito.mock(FinanceBasePriceMapper.class),
        Mockito.mock(DynamicValueMapper.class),
        props,
        normalizer,
        registry,
        new ObjectMapper(),
        previewService);
  }

  /**
   * tax_included=0 + vat_rate 解析成功 → 正常做 1/(1+vat_rate) 转换，返回不含税值。
   * 对照组，保证改动没破坏正常路径。
   */
  @Test
  void newCalculate_taxIncludedZero_vatRatePresent_appliesConversion() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(inv -> {
          String code = inv.getArgument(0);
          if ("vat_rate".equals(code)) {
            return java.util.Optional.of(new BigDecimal("0.13"));
          }
          // [Cu]=113 → 公式结果 113，/ 1.13 = 100
          return java.util.Optional.of(new BigDecimal("113"));
        });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new");

    PriceLinkedCalcServiceImpl service =
        buildServiceWithRealPreview(props, normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]");
    linkedItem.setMaterialCode("MAT-VAT-OK");
    linkedItem.setTaxIncluded(0);

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalculatePartUnitPrice(service, linkedItem, calcItem, null);

    // preview 内部 divide 保留 20 位精度，calculatePartUnitPrice 再 setScale(6) —— 期望 100.000000
    assertThat(result).isEqualByComparingTo(new BigDecimal("100.000000"));
    // 重构后 taxAdjusted/vatRate 这两个字段在 preview 层完成，不再写到 calc_item.trace_json
    // （trace 只保留 rawExpr/normalizedExpr/variables/result/error 5 个 front-end 消费键）
    // 所以这里只断言 result 本身正确 = 转换已生效
  }

  /**
   * tax_included=1（含税结算口径）+ vat_rate 即便缺失也不应触发硬错 —— 这个路径不做转换。
   */
  @Test
  void newCalculate_taxIncludedOne_skipsVatRateCheck() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(inv -> {
          String code = inv.getArgument(0);
          if ("vat_rate".equals(code)) {
            return java.util.Optional.empty(); // 故意让 vat_rate 空，证明不会触达这行
          }
          return java.util.Optional.of(new BigDecimal("50"));
        });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new");

    PriceLinkedCalcServiceImpl service =
        buildServiceWithRealPreview(props, normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]");
    linkedItem.setMaterialCode("MAT-TAX-INCLUDED");
    linkedItem.setTaxIncluded(1); // 含税口径，不触发 vat 转换

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalculatePartUnitPrice(service, linkedItem, calcItem, null);

    // 原值保留，不做除法
    assertThat(result).isEqualByComparingTo(new BigDecimal("50.000000"));
    // trace 不应有 taxAdjusted 键
    assertThat(calcItem.getTraceJson()).doesNotContain("taxAdjusted");
  }
}
