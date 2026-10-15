package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceVariableContext;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PriceLinkedCalcServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  @Test
  void resultPage_returnsPersistedRowsWithoutCreatingCalcItems() {
    BomCostingRowMapper mapper = Mockito.mock(BomCostingRowMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            calcMapper,
            linkedItemMapper,
            oaFormMapper,
            new ObjectMapper(),
            Mockito.mock(PriceLinkedFormulaPreviewService.class));

    PriceLinkedCalcItem quote = new PriceLinkedCalcItem();
    quote.setId(1L);
    quote.setSourcePriceRecordId(11L);
    quote.setOaNo("OA-001");
    quote.setBusinessUnitType("COMMERCIAL");
    quote.setItemCode("MAT-1");
    quote.setCalcScene("QUOTE");
    quote.setPricingMonth("2026-05");
    quote.setFactorSource("OA_LOCKED");
    quote.setCalcStatus("OK");
    quote.setTraceJson("{\"variableDetails\":[{\"code\":\"Cu\",\"source\":\"OA_LOCKED\"}]}");

    PriceLinkedCalcItem monthly = new PriceLinkedCalcItem();
    monthly.setId(2L);
    monthly.setSourcePriceRecordId(12L);
    monthly.setOaNo("OA-001");
    monthly.setBusinessUnitType("COMMERCIAL");
    monthly.setItemCode("MAT-1");
    monthly.setCalcScene("MONTHLY_ADJUST");
    monthly.setPricingMonth("2026-05");
    monthly.setFactorSource("MONTHLY_FACTOR");
    monthly.setCalcStatus("FAILED");
    monthly.setCalcMessage("影响因素缺失");

    Page<PriceLinkedCalcItem> calcPage = new Page<>(1, 20);
    calcPage.setTotal(2);
    calcPage.setRecords(List.of(quote, monthly));
    when(calcMapper.selectPage(any(), any())).thenReturn(calcPage);

    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCustomer("客户A");
    when(oaFormMapper.selectList(any())).thenReturn(List.of(form));

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setMaterialCode("MAT-1");
    linkedItem.setId(11L);
    linkedItem.setSupplierName("供应商A");
    linkedItem.setMaterialName("联动价物料");
    linkedItem.setBusinessUnitType("COMMERCIAL");
    linkedItem.setPricingMonth("2026-05");
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem));

    Page<PriceLinkedCalcRow> result =
        service.resultPage(null, null, null, "MAT-1", null, null, null, null, 1, 20);

    assertEquals(2, result.getTotal());
    assertEquals("联动价物料", result.getRecords().get(0).getMaterialName());
    assertEquals("供应商A", result.getRecords().get(0).getSupplierName());
    assertNull(result.getRecords().get(1).getMaterialName());
    assertEquals("QUOTE", result.getRecords().get(0).getCalcScene());
    assertEquals("OA_LOCKED", result.getRecords().get(0).getFactorSource());
    assertEquals(1, result.getRecords().get(0).getVariableSourceSummary().get("OA_LOCKED"));
    assertEquals("MONTHLY_ADJUST", result.getRecords().get(1).getCalcScene());
    assertEquals("影响因素缺失", result.getRecords().get(1).getCalcMessage());
    Mockito.verify(calcMapper, never()).insert(any(PriceLinkedCalcItem.class));
    Mockito.verify(calcMapper, never()).updateById(any(PriceLinkedCalcItem.class));
    Mockito.verifyNoInteractions(mapper);
  }

  @Test
  void resultPage_preservesHistoricalRowsWithoutSourceRuleIds() {
    var calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    var ruleMapper = Mockito.mock(PriceLinkedItemMapper.class);
    var oaMapper = Mockito.mock(OaFormMapper.class);
    var preview = Mockito.mock(PriceLinkedFormulaPreviewService.class);
    var service = new PriceLinkedCalcServiceImpl(
        calcMapper, ruleMapper, oaMapper, new ObjectMapper(), preview);

    var saved = new PriceLinkedCalcItem();
    saved.setId(995L);
    saved.setItemCode("301070047");
    saved.setPartUnitPrice(new BigDecimal("12.345678"));
    saved.setCalcStatus("OK");
    saved.setTraceJson("{\"rawExpr\":\"[Cu]*0.965\",\"variableDetails\":[{\"code\":\"Cu\",\"source\":\"OA_LOCKED\"}]}");
    var failed = new PriceLinkedCalcItem();
    failed.setId(960L);
    failed.setItemCode("9990000050429");
    failed.setCalcStatus("FAILED");
    failed.setCalcMessage("联动价公式不存在或为空");
    var page = new Page<PriceLinkedCalcItem>(28, 20);
    page.setTotal(1105);
    page.setRecords(List.of(saved, failed));
    when(calcMapper.selectPage(any(), any())).thenReturn(page);

    var result = service.resultPage(null, null, null, null, null, null, null, null, 28, 20);

    assertEquals(1105, result.getTotal());
    assertEquals(2, result.getRecords().size());
    assertEquals("[Cu]*0.965", result.getRecords().getFirst().getFormulaExpr());
    assertEquals(saved.getPartUnitPrice(), result.getRecords().getFirst().getPartUnitPrice());
    assertEquals(1, result.getRecords().getFirst().getVariableSourceSummary().get("OA_LOCKED"));
    assertEquals("FAILED", result.getRecords().getLast().getCalcStatus());
    assertEquals(failed.getCalcMessage(), result.getRecords().getLast().getCalcMessage());
    assertNull(result.getRecords().getLast().getPartUnitPrice());
    Mockito.verifyNoInteractions(ruleMapper, preview);
    Mockito.verify(calcMapper, never()).insert(any(PriceLinkedCalcItem.class));
    Mockito.verify(calcMapper, never()).updateById(any(PriceLinkedCalcItem.class));
  }

  @Test
  void calculateMonthlyAdjustItemForEnsureUsesMonthlyAdjustVariableContext() {
    PriceLinkedFormulaPreviewService previewService =
        Mockito.mock(PriceLinkedFormulaPreviewService.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            Mockito.mock(PriceLinkedCalcItemMapper.class),
            Mockito.mock(PriceLinkedItemMapper.class),
            Mockito.mock(OaFormMapper.class),
            new ObjectMapper(),
            previewService);
    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setMaterialCode("MAT-1");
    linkedItem.setPricingMonth("2026-05");
    linkedItem.setFormulaExpr("[Cu]+1");

    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
    response.setResult(new BigDecimal("12.3456784"));
    when(previewService.previewForRefresh(
            any(PriceLinkedItem.class), any(LinkedPriceVariableContext.class), Mockito.isNull()))
        .thenReturn(response);

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
    calcItem.setBusinessUnitType("COMMERCIAL");
    calcItem.setPricingMonth("2026-06");
    calcItem.setItemCode("MAT-1");
    calcItem.setCalcScene("MONTHLY_ADJUST");

    service.calculateMonthlyAdjustItemForEnsure(calcItem, linkedItem);

    ArgumentCaptor<LinkedPriceVariableContext> contextCaptor =
        ArgumentCaptor.forClass(LinkedPriceVariableContext.class);
    Mockito.verify(previewService)
        .previewForRefresh(any(PriceLinkedItem.class), contextCaptor.capture(), Mockito.isNull());
    LinkedPriceVariableContext context = contextCaptor.getValue();
    assertEquals("MONTHLY_ADJUST", context.getCalcScene().getCode());
    assertEquals("2026-06", context.getPricingMonth());
    assertEquals("MONTHLY_FACTOR", calcItem.getFactorSource());
    assertEquals("OK", calcItem.getCalcStatus());
    assertThat(calcItem.getPartUnitPrice()).isEqualByComparingTo("12.345678");
  }

  @Test
  void calculateQuoteItemForEnsureUsesCalcItemPricingMonthForVariables() {
    PriceLinkedFormulaPreviewService previewService =
        Mockito.mock(PriceLinkedFormulaPreviewService.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            Mockito.mock(PriceLinkedCalcItemMapper.class),
            Mockito.mock(PriceLinkedItemMapper.class),
            Mockito.mock(OaFormMapper.class),
            new ObjectMapper(),
            previewService);
    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setMaterialCode("MAT-1");
    linkedItem.setPricingMonth("2026-05");
    linkedItem.setFormulaExpr("[Cu]+1");

    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
    response.setResult(new BigDecimal("12.3456784"));
    when(previewService.previewForRefresh(
            any(PriceLinkedItem.class), any(LinkedPriceVariableContext.class), Mockito.any()))
        .thenReturn(response);

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
    calcItem.setBusinessUnitType("COMMERCIAL");
    calcItem.setPricingMonth("2026-06");
    calcItem.setItemCode("MAT-1");

    OaForm oaForm = new OaForm();
    service.calculateQuoteItemForEnsure(calcItem, linkedItem, oaForm);

    ArgumentCaptor<LinkedPriceVariableContext> contextCaptor =
        ArgumentCaptor.forClass(LinkedPriceVariableContext.class);
    Mockito.verify(previewService)
        .previewForRefresh(
            any(PriceLinkedItem.class), contextCaptor.capture(), Mockito.same(oaForm));
    LinkedPriceVariableContext context = contextCaptor.getValue();
    assertEquals("QUOTE", context.getCalcScene().getCode());
    assertEquals("2026-06", context.getPricingMonth());
    assertEquals("OK", calcItem.getCalcStatus());
    assertThat(calcItem.getPartUnitPrice()).isEqualByComparingTo("12.345678");
  }

  @Test
  void quoteCalculationWithPriorFormulaResolvesVariablesByQuoteMonth() {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    VariableContext[] capturedContext = new VariableContext[1];
    when(normalizer.normalize(any())).thenReturn("[Cu]");
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            invocation -> {
              capturedContext[0] = invocation.getArgument(1);
              return java.util.Optional.of(new BigDecimal("12.3456784"));
            });

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setMaterialCode("MAT-1");
    linkedItem.setPricingMonth("2026-05");
    linkedItem.setFormulaExpr("[Cu]");

    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
    calcItem.setBusinessUnitType("COMMERCIAL");
    calcItem.setPricingMonth("2026-06");
    calcItem.setItemCode("MAT-1");

    service.calculateQuoteItemForEnsure(calcItem, linkedItem, new OaForm());

    assertThat(capturedContext[0]).isNotNull();
    assertThat(capturedContext[0].getPricingMonth()).isEqualTo("2026-06");
    assertThat(calcItem.getTraceJson()).contains("\"formulaPriceMonth\":\"2026-05\"");
    assertThat(calcItem.getTraceJson()).contains("\"calculationPriceMonth\":\"2026-06\"");
    assertThat(calcItem.getPartUnitPrice()).isEqualByComparingTo("12.345678");
  }

  @Test
  void getTrace_covers_null_missing_and_present() {
    BomCostingRowMapper mapper = Mockito.mock(BomCostingRowMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedFormulaPreviewService previewService =
        Mockito.mock(PriceLinkedFormulaPreviewService.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            calcMapper, linkedItemMapper, oaFormMapper, new ObjectMapper(), previewService);

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
    linked.setSupplierCode("S001301");
    linked.setProcessFee(new BigDecimal("4.350001"));
    when(linkedItemMapper.selectById(42L)).thenReturn(linked);

    PriceLinkedFormulaPreviewResponse previewResp = new PriceLinkedFormulaPreviewResponse();
    previewResp.setNormalizedExpr("[Cu]+[process_fee]");
    previewResp.setResult(new BigDecimal("93.73"));
    VariableDetail cu = new VariableDetail();
    cu.setCode("Cu");
    cu.setName("铜基价");
    cu.setValue(new BigDecimal("88.73"));
    cu.setSource("FINANCE");
    VariableDetail fee = new VariableDetail();
    fee.setCode("process_fee");
    fee.setName("加工费");
    fee.setValue(new BigDecimal("5.00"));
    fee.setSource("PART_CONTEXT");
    previewResp.setVariables(List.of(cu, fee));
    when(previewService.previewForRefresh(linked, Map.of())).thenReturn(previewResp);

    PriceLinkedCalcTraceResponse trace = service.getTrace(42L);
    assertThat(trace).isNotNull();
    assertThat(trace.getId()).isEqualTo(42L);
    // 前端期待的平铺 schema：rawExpr / normalizedExpr / variables / result
    assertThat(trace.getTraceJson()).contains("\"rawExpr\":\"Cu+process_fee\"");
    assertThat(trace.getTraceJson()).contains("\"normalizedExpr\":\"[Cu]+[process_fee]\"");
    assertThat(trace.getTraceJson()).contains("\"Cu\":88.73");
    assertThat(trace.getTraceJson()).contains("\"variableDetails\"");
    assertThat(trace.getTraceJson()).contains("\"name\":\"铜基价\"");
    assertThat(trace.getTraceJson()).contains("\"source\":\"FINANCE\"");
    assertThat(trace.getTraceJson()).contains("\"result\":93.73");
    Mockito.verify(previewService).previewForRefresh(linked, Map.of());
    linked.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    Mockito.clearInvocations(previewService);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getTrace(42L))
        .hasMessageContaining("财务有效价详情");
    Mockito.verifyNoInteractions(previewService);
  }

  // ================== V28 收尾：OA 锁价 + 基价回落 单测 ==================

  /** 构造一个带所有必要 mock 依赖的 service 实例，便于多个 case 复用。 */
  @Test
  void quoteSceneRecalculateChangesWhenOaCopperChanges() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(normalizer.normalize(any())).thenReturn("[Cu]");
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              VariableContext ctx = inv.getArgument(1);
              if (ctx.getOverrides().containsKey(code)) {
                return java.util.Optional.of(ctx.getOverrides().get(code));
              }
              return java.util.Optional.of(new BigDecimal("999"));
            });

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]");
    linkedItem.setMaterialCode("MAT-CU");

    OaForm firstOa = new OaForm();
    firstOa.setCopperPrice(new BigDecimal("90000"));
    BigDecimal first =
        invokeCalculatePartUnitPrice(service, linkedItem, new PriceLinkedCalcItem(), firstOa);

    OaForm secondOa = new OaForm();
    secondOa.setCopperPrice(new BigDecimal("92000"));
    BigDecimal second =
        invokeCalculatePartUnitPrice(service, linkedItem, new PriceLinkedCalcItem(), secondOa);

    assertThat(first).isEqualByComparingTo("90.000000");
    assertThat(second).isEqualByComparingTo("92.000000");
  }

  @Test
  void quoteSceneIgnoresMonthlyFactorWhenOaLockExistsAndTraceMarksSource() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(normalizer.normalize(any())).thenReturn("[Cu]");
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              VariableContext ctx = inv.getArgument(1);
              if (ctx.getOverrides().containsKey(code)) {
                return java.util.Optional.of(ctx.getOverrides().get(code));
              }
              // 模拟同月影响因素价已变化；QUOTE 有 OA 锁价时不应读到这里的值。
              return java.util.Optional.of(new BigDecimal("999"));
            });

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]");
    linkedItem.setMaterialCode("MAT-CU");
    linkedItem.setPricingMonth("2026-05");
    PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
    OaForm oaForm = new OaForm();
    oaForm.setCopperPrice(new BigDecimal("90000"));

    BigDecimal result = invokeCalculatePartUnitPrice(service, linkedItem, calcItem, oaForm);

    assertThat(result).isEqualByComparingTo("90.000000");
    assertThat(calcItem.getTraceJson()).contains("\"source\":\"OA_LOCKED\"");
    assertThat(calcItem.getTraceJson()).doesNotContain("999");
  }

  @Test
  void financeQuoteUsesPublishedZnAlAndOnlyCustomerCu() {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(normalizer.normalize(any())).thenReturn("[Cu]+[Zn]+[Al]");
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              VariableContext ctx = inv.getArgument(1);
              if (ctx.getOverrides().containsKey("Zn")) assertThat(ctx.getOverrides().get("Zn")).isEqualByComparingTo("21.684");
              if (ctx.getOverrides().containsKey("Al")) assertThat(ctx.getOverrides().get("Al")).isEqualByComparingTo("18.88");
              if ("Cu".equals(code) && !ctx.isFinanceQuoteBase())
                return java.util.Optional.ofNullable(ctx.getOverrides().get(code));
              ctx.resolvedSource(code, "Cu".equals(code) ? "FINANCE_QUOTE_BASE" : "FINANCE_FACTOR");
              return java.util.Optional.of(switch (code) {
                case "Cu" -> new BigDecimal("90.000000");
                case "Zn" -> new BigDecimal("21.684000");
                case "Al" -> new BigDecimal("18.880000");
                default -> throw new AssertionError(code);
              });
            });

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);
    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Cu]+[Zn]+[Al]");
    linkedItem.setMaterialCode("MAT-CU-ZN-AL");
    linkedItem.setPricingMonth("2026-05");
    OaForm oaForm = new OaForm();
    oaForm.setCopperPrice(new BigDecimal("102039"));
    oaForm.setZincPrice(new BigDecimal("99999"));
    oaForm.setAluminumPrice(new BigDecimal("88888"));

    PriceLinkedCalcItem oa = new PriceLinkedCalcItem();
    oa.setPricingMonth("2026-05");
    service.calculateQuoteItemForEnsure(oa, linkedItem, oaForm);
    PriceLinkedCalcItem finance = new PriceLinkedCalcItem();
    finance.setPricingMonth("2026-05");
    service.calculateQuoteItemForEnsure(
        finance,
        linkedItem,
        oaForm,
        Map.of("Cu", new BigDecimal("90.000000")),
        "FINANCE_QUOTE_BASE");

    assertThat(oa.getPartUnitPrice()).isEqualByComparingTo("142.603000");
    assertThat(finance.getPartUnitPrice()).isEqualByComparingTo("130.564000");
    assertThat(oa.getPartUnitPrice().subtract(finance.getPartUnitPrice()))
        .isEqualByComparingTo("12.039000");
    assertThat(finance.getFactorSource()).isEqualTo("FINANCE_QUOTE_BASE");
    assertThat(finance.getTraceJson())
        .contains("\"Cu\":90.000000")
        .contains("\"Zn\":21.684000")
        .contains("\"Al\":18.880000")
        .contains("\"code\":\"Cu\"")
        .contains("\"source\":\"FINANCE_QUOTE_BASE\"")
        .contains("\"source\":\"FINANCE_FACTOR\"");
  }

  @Test
  void financeQuoteKeepsNonCuLinkedFormulaExactlyTheSame() {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(normalizer.normalize(any())).thenReturn("[Zn]+[Al]");
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              VariableContext ctx = inv.getArgument(1);
              if (ctx.getOverrides().containsKey("Zn")) assertThat(ctx.getOverrides().get("Zn")).isEqualByComparingTo("21.684");
              if (ctx.getOverrides().containsKey("Al")) assertThat(ctx.getOverrides().get("Al")).isEqualByComparingTo("18.88");
              if ("Cu".equals(code) && !ctx.isFinanceQuoteBase())
                return java.util.Optional.ofNullable(ctx.getOverrides().get(code));
              ctx.resolvedSource(code, "Cu".equals(code) ? "FINANCE_QUOTE_BASE" : "FINANCE_FACTOR");
              return java.util.Optional.of(switch (code) {
                case "Cu" -> new BigDecimal("90.000000");
                case "Zn" -> new BigDecimal("21.684000");
                case "Al" -> new BigDecimal("18.880000");
                default -> throw new AssertionError(code);
              });
            });

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);
    PriceLinkedItem linkedItem = new PriceLinkedItem();
    linkedItem.setFormulaExpr("[Zn]+[Al]");
    linkedItem.setMaterialCode("MAT-ZN-AL");
    linkedItem.setPricingMonth("2026-05");
    OaForm oaForm = new OaForm();
    oaForm.setCopperPrice(new BigDecimal("102039"));
    oaForm.setZincPrice(new BigDecimal("99999"));
    oaForm.setAluminumPrice(new BigDecimal("88888"));

    PriceLinkedCalcItem oa = new PriceLinkedCalcItem();
    oa.setPricingMonth("2026-05");
    service.calculateQuoteItemForEnsure(oa, linkedItem, oaForm);
    PriceLinkedCalcItem finance = new PriceLinkedCalcItem();
    finance.setPricingMonth("2026-05");
    service.calculateQuoteItemForEnsure(
        finance,
        linkedItem,
        oaForm,
        Map.of("Cu", new BigDecimal("90.000000")),
        "FINANCE_QUOTE_BASE");

    assertThat(oa.getPartUnitPrice()).isEqualByComparingTo("40.564000");
    assertThat(finance.getPartUnitPrice()).isEqualByComparingTo(oa.getPartUnitPrice());
    assertThat(finance.getTraceJson())
        .contains("\"Zn\":21.684000")
        .contains("\"Al\":18.880000")
        .doesNotContain("\"code\":\"Cu\"");
  }

  // ==================== 风险 2：vat_rate 缺失时 newCalculate 硬错 ====================

  /** 反射调 private calculatePartUnitPrice —— 不开 public API 供测试用。 */
  @SuppressWarnings("unchecked")
  private BigDecimal invokeCalculatePartUnitPrice(
      PriceLinkedCalcServiceImpl service,
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm)
      throws Exception {
    return service.calculateQuoteItemForEnsure(calcItem, linkedItem, oaForm).getPartUnitPrice();
  }

  /**
   * tax_included=0 + vat_rate 解析失败 → newCalculate 抛 IllegalStateException， 被外层 catch 吞成
   * trace.error，calcItem.traceJson 里必须包含 vat_rate 错误线索， 且返回 null（避免写错的 partUnitPrice）。
   *
   * <p>这是风险 2 的核心：以前 WARN 跳过会让结果偷偷偏高 13%；现在硬错，前端能看到。
   */
  @Test
  void newCalculate_taxIncludedZero_vatRateMissing_throwsWithClearError() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    // 让其他变量都能 resolve，只让 vat_rate 返回 empty —— 精准触发目标分支
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              if ("vat_rate".equals(code)) {
                return java.util.Optional.empty();
              }
              return java.util.Optional.of(new BigDecimal("88"));
            });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

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
      FormulaNormalizer normalizer, FactorVariableRegistry registry) {
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper priceVariableMapperForPreview = Mockito.mock(PriceVariableMapper.class);
    when(priceVariableMapperForPreview.selectList(any()))
        .thenReturn(java.util.List.of()); // loadVariableMeta 空，用 token 做名字即可
    com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl previewService =
        new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
            normalizer,
            registry,
            priceVariableMapperForPreview,
            linkedItemMapper,
            Mockito.mock(
                com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker.class));
    return new PriceLinkedCalcServiceImpl(
        Mockito.mock(PriceLinkedCalcItemMapper.class),
        linkedItemMapper,
        Mockito.mock(OaFormMapper.class),
        new ObjectMapper(),
        previewService);
  }

  /** tax_included=0 + vat_rate 解析成功 → 正常做 1/(1+vat_rate) 转换，返回不含税值。 对照组，保证改动没破坏正常路径。 */
  @Test
  void newCalculate_taxIncludedZero_vatRatePresent_appliesConversion() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              if ("vat_rate".equals(code)) {
                return java.util.Optional.of(new BigDecimal("0.13"));
              }
              // [Cu]=113 → 公式结果 113，/ 1.13 = 100
              return java.util.Optional.of(new BigDecimal("113"));
            });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

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

  /** tax_included=1（含税结算口径）+ vat_rate 即便缺失也不应触发硬错 —— 这个路径不做转换。 */
  @Test
  void newCalculate_taxIncludedOne_skipsVatRateCheck() throws Exception {
    FactorVariableRegistry registry = Mockito.mock(FactorVariableRegistry.class);
    FormulaNormalizer normalizer = Mockito.mock(FormulaNormalizer.class);
    when(registry.resolve(any(String.class), any(VariableContext.class)))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              if ("vat_rate".equals(code)) {
                return java.util.Optional.empty(); // 故意让 vat_rate 空，证明不会触达这行
              }
              return java.util.Optional.of(new BigDecimal("50"));
            });
    when(normalizer.normalize(any())).thenReturn("[Cu]");

    PriceLinkedCalcServiceImpl service = buildServiceWithRealPreview(normalizer, registry);

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
