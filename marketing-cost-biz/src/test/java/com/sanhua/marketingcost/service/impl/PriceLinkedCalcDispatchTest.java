package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T11 dispatch/newCalculate 单测 —— 不起 SpringBootTest，反射直接调 private 方法。
 *
 * <p>覆盖：
 * <ul>
 *   <li>legacy 模式：仅走老路径，trace_json 不写</li>
 *   <li>new 模式：仅走新路径；trace_json 写入 {mode,normalizedExpr,variables,result}</li>
 *   <li>dual 模式：两路径并行；diff 超阈值写 WARN；返回 legacy 值</li>
 *   <li>new 模式失败：trace 写 error；返回 null</li>
 * </ul>
 */
class PriceLinkedCalcDispatchTest {

  /** 构造一个 service，各依赖按参数注入；2026-04 重构后 previewService 也要真实实例。 */
  private static PriceLinkedCalcServiceImpl build(
      LinkedParserProperties props,
      FormulaNormalizer normalizer,
      FactorVariableRegistry registry) {
    PriceLinkedItemMapper linkedItemMapper = mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapperForPreview = mock(PriceVariableMapper.class);
    when(variableMapperForPreview.selectList(any()))
        .thenReturn(java.util.List.of());
    com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl previewService =
        new com.sanhua.marketingcost.service.impl.PriceLinkedFormulaPreviewServiceImpl(
            normalizer, registry, variableMapperForPreview, linkedItemMapper,
            mock(com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker.class));
    return new PriceLinkedCalcServiceImpl(
        mock(BomCostingRowMapper.class),
        mock(PriceLinkedCalcItemMapper.class),
        linkedItemMapper,
        mock(PriceVariableMapper.class),
        mock(OaFormMapper.class),
        mock(FinanceBasePriceMapper.class),
        mock(DynamicValueMapper.class),
        props, normalizer, registry, new ObjectMapper(),
        previewService);
  }

  /** 反射调 private calculatePartUnitPrice 方法 */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static BigDecimal invokeCalc(
      PriceLinkedCalcServiceImpl svc,
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem) throws Exception {
    Method m = PriceLinkedCalcServiceImpl.class.getDeclaredMethod(
        "calculatePartUnitPrice",
        PriceLinkedItem.class,
        PriceLinkedCalcItem.class,
        com.sanhua.marketingcost.entity.OaForm.class,
        Map.class,
        Map.class);
    m.setAccessible(true);
    return (BigDecimal) m.invoke(svc, linkedItem, calcItem, null,
        new HashMap(), new HashMap());
  }

  private static PriceLinkedItem linkedWith(String exprCn) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("PART-A");
    item.setPricingMonth("2026-04");
    item.setFormulaExpr(exprCn); // 老字段
    item.setFormulaExprCn(exprCn); // 新字段优先
    return item;
  }

  @Test
  @DisplayName("legacy 模式：仅跑老路径，trace_json 不写入")
  void legacyModeOnly() throws Exception {
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("legacy");
    FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
    FactorVariableRegistry registry = mock(FactorVariableRegistry.class);

    PriceLinkedCalcServiceImpl svc = build(props, normalizer, registry);
    PriceLinkedItem item = linkedWith("[Cu]*1.0");
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalc(svc, item, calc);
    // 老路径变量找不到 → 按 0 → 结果 0；关键断言是 trace 不写
    assertThat(calc.getTraceJson()).isNull();
    // normalizer/registry 都不被调用
    org.mockito.Mockito.verifyNoInteractions(normalizer);
    org.mockito.Mockito.verifyNoInteractions(registry);
    assertThat(result).isNotNull();
  }

  @Test
  @DisplayName("new 模式：仅跑新路径；trace 含 normalizedExpr+result")
  void newModeWritesTrace() throws Exception {
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new");
    FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
    when(normalizer.normalize(anyString())).thenReturn("[Cu]*1.0");
    FactorVariableRegistry registry = mock(FactorVariableRegistry.class);
    when(registry.resolve(org.mockito.ArgumentMatchers.eq("Cu"), any(VariableContext.class)))
        .thenReturn(Optional.of(new BigDecimal("90")));

    PriceLinkedCalcServiceImpl svc = build(props, normalizer, registry);
    PriceLinkedItem item = linkedWith("[铜价]*1.0");
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalc(svc, item, calc);
    assertThat(result).isEqualByComparingTo(new BigDecimal("90.000000"));
    assertThat(calc.getTraceJson())
        .contains("\"normalizedExpr\":\"[Cu]*1.0\"")
        .contains("\"result\":90")
        .contains("\"mode\":\"new\"");
  }

  @Test
  @DisplayName("dual 模式：两路径都跑；trace 含 legacyResult+newResult+diff；返回 new 值（含 tax 转换）")
  void dualModeRunsBothAndReturnsNew() throws Exception {
    // 2026-04 改动：dual 模式下列表展示从 legacy 切换到 new，因为 new 路径含
    // tax_included=0 的不含税转换（和联动价结果页 preview 对齐）；legacy 无此转换，
    // 对 tax_included=0 的 OA 刷新会偷偷偏高 13%。legacy 仅在 new 返 null 时兜底。
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("dual");
    props.setDualWarnThreshold(new BigDecimal("0.01"));

    FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
    when(normalizer.normalize(anyString())).thenReturn("[Cu]*1.0");
    FactorVariableRegistry registry = mock(FactorVariableRegistry.class);
    when(registry.resolve(org.mockito.ArgumentMatchers.eq("Cu"), any(VariableContext.class)))
        .thenReturn(Optional.of(new BigDecimal("88"))); // new 算出 88

    PriceLinkedCalcServiceImpl svc = build(props, normalizer, registry);
    // tax_included 不设（默认 null）→ 跳过 tax 转换，new 结果就是 88
    PriceLinkedItem item = linkedWith("[铜价]*1.0");
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalc(svc, item, calc);
    // dual 模式现在返回 new 值（legacy=0 因变量找不到，new=88）
    assertThat(result).isEqualByComparingTo(new BigDecimal("88.000000"));
    assertThat(calc.getTraceJson())
        .contains("\"legacyResult\"")
        .contains("\"newResult\":88")
        .contains("\"diff\":88");
  }

  @Test
  @DisplayName("new 模式：新管线异常被捕获进 trace.error，返回 null")
  void newModeCapturesError() throws Exception {
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("new");
    FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
    when(normalizer.normalize(anyString()))
        .thenThrow(new RuntimeException("boom"));
    FactorVariableRegistry registry = mock(FactorVariableRegistry.class);

    PriceLinkedCalcServiceImpl svc = build(props, normalizer, registry);
    PriceLinkedItem item = linkedWith("[铜价]");
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();

    BigDecimal result = invokeCalc(svc, item, calc);
    assertThat(result).isNull();
    assertThat(calc.getTraceJson())
        .contains("\"error\":\"RuntimeException: boom\"");
  }

  @Test
  @DisplayName("linkedItem 无公式：直接返 null，不触发任一路径")
  void noFormula() throws Exception {
    LinkedParserProperties props = new LinkedParserProperties();
    props.setMode("dual");
    FormulaNormalizer normalizer = mock(FormulaNormalizer.class);
    FactorVariableRegistry registry = mock(FactorVariableRegistry.class);
    PriceLinkedCalcServiceImpl svc = build(props, normalizer, registry);

    PriceLinkedItem bare = new PriceLinkedItem();
    bare.setMaterialCode("X");
    assertThat(invokeCalc(svc, bare, new PriceLinkedCalcItem())).isNull();
    org.mockito.Mockito.verifyNoInteractions(normalizer);
    org.mockito.Mockito.verifyNoInteractions(registry);
  }
}
