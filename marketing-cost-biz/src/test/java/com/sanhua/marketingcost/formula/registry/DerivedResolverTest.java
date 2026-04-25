package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DerivedResolver 单元测试 —— 覆盖 4 种派生策略 + 各自 fallback/缺数据分支。
 */
class DerivedResolverTest {

  private FinanceBasePriceMapper financeBasePriceMapper;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private DerivedResolver resolver;

  @BeforeEach
  void setUp() {
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    resolver = new DerivedResolver(financeBasePriceMapper, materialScrapRefMapper);
  }

  private static PriceVariable var(String code) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setFactorType("PART_CONTEXT");
    return v;
  }

  @Test
  @DisplayName("MAIN_MATERIAL_FINANCE：按 materialCode 查 short_name 命中")
  void mainMaterialByShortName() {
    FinanceBasePrice hit = new FinanceBasePrice();
    hit.setPrice(new BigDecimal("95.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(hit);

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("美国柜装黄铜");
    VariableContext ctx = new VariableContext()
        .pricingMonth("2026-04")
        .linkedItem(item);

    Optional<BigDecimal> result = resolver.resolve(
        var("material_price_incl"), ctx,
        Map.of("source", "DERIVED", "strategy", "MAIN_MATERIAL_FINANCE"),
        null);
    assertThat(result).contains(new BigDecimal("95.00"));
  }

  @Test
  @DisplayName("MAIN_MATERIAL_FINANCE：linkedItem 为空返回 empty")
  void mainMaterialNoLinkedItem() {
    Optional<BigDecimal> result = resolver.resolve(
        var("material_price_incl"), new VariableContext(),
        Map.of("strategy", "MAIN_MATERIAL_FINANCE"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("SCRAP_REF：部品→废料映射命中 × ratio=0.92")
  @SuppressWarnings("unchecked")
  void scrapRefWithRatio() {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setScrapCode("铜沫");
    ref.setRatio(new BigDecimal("0.92"));
    when(materialScrapRefMapper.selectOne(any())).thenReturn(ref);

    // scrap_ref 查到后，先走 shortName 路径 selectOne
    FinanceBasePrice scrap = new FinanceBasePrice();
    scrap.setPrice(new BigDecimal("60.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(scrap);

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("HPb59-1");
    VariableContext ctx = new VariableContext()
        .pricingMonth("2026-04")
        .linkedItem(item);

    Optional<BigDecimal> result = resolver.resolve(
        var("scrap_price_incl"), ctx,
        Map.of("strategy", "SCRAP_REF"), null);
    // 60 × 0.92 = 55.20
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo(new BigDecimal("55.20"));
  }

  @Test
  @DisplayName("SCRAP_REF：映射表无记录返回 empty")
  void scrapRefMissingMapping() {
    when(materialScrapRefMapper.selectOne(any())).thenReturn(null);

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("未知部品");
    VariableContext ctx = new VariableContext().linkedItem(item);

    Optional<BigDecimal> result = resolver.resolve(
        var("scrap_price_incl"), ctx, Map.of("strategy", "SCRAP_REF"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("SCRAP_REF：ratio 为空时按 1 处理（直接返 finance 价）")
  @SuppressWarnings("unchecked")
  void scrapRefNullRatio() {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setScrapCode("铜沫");
    ref.setRatio(null);
    when(materialScrapRefMapper.selectOne(any())).thenReturn(ref);

    FinanceBasePrice scrap = new FinanceBasePrice();
    scrap.setPrice(new BigDecimal("60.00"));
    // 带 pricingMonth 时走 selectOne；本用例 ctx 未设月份 → 直接 selectList 回退路径
    when(financeBasePriceMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(scrap)));

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("X");
    VariableContext ctx = new VariableContext().linkedItem(item);

    assertThat(resolver.resolve(
        var("scrap_price_incl"), ctx, Map.of("strategy", "SCRAP_REF"), null))
        .contains(new BigDecimal("60.00"));
  }

  @Test
  @DisplayName("FORMULA_REF：[Cu]*0.59+[Zn]*0.41 with Cu=90 Zn=21.68 ≈ 61.0")
  void formulaRefCopperScrap() {
    VariableContext ctx = new VariableContext()
        .override("Cu", new BigDecimal("90"))
        .override("Zn", new BigDecimal("21.68"));
    Optional<BigDecimal> result = resolver.resolve(
        var("copper_scrap_price"), ctx,
        Map.of("strategy", "FORMULA_REF", "formulaRef", "[Cu]*0.59+[Zn]*0.41"), null);
    // 90 × 0.59 = 53.1; 21.68 × 0.41 = 8.8888; 合计 61.9888
    assertThat(result).isPresent();
    assertThat(result.get().doubleValue()).isBetween(61.5, 62.2);
  }

  @Test
  @DisplayName("FORMULA_REF：子变量未 override 时按 0 处理")
  void formulaRefMissingSubVar() {
    Optional<BigDecimal> result = resolver.resolve(
        var("copper_scrap_price"), new VariableContext(),
        Map.of("strategy", "FORMULA_REF", "formulaRef", "[Cu]*10"), null);
    assertThat(result).isPresent().contains(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("FORMULA_REF：formulaRef 空返 empty")
  void formulaRefMissingExpr() {
    Optional<BigDecimal> result = resolver.resolve(
        var("copper_scrap_price"), new VariableContext(),
        Map.of("strategy", "FORMULA_REF"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("FINANCE_FACTOR 直指：按 binding.factorCode 查")
  void financeFactorDirect() {
    FinanceBasePrice hit = new FinanceBasePrice();
    hit.setPrice(new BigDecimal("78.5"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(hit);

    Optional<BigDecimal> result = resolver.resolve(
        var("us_yellow_copper_price"), new VariableContext().pricingMonth("2026-04"),
        Map.of("strategy", "FINANCE_FACTOR", "factorCode", "美国柜装黄铜"), null);
    assertThat(result).contains(new BigDecimal("78.5"));
  }

  @Test
  @DisplayName("FINANCE_FACTOR 直指：精确月无命中时回退最新")
  @SuppressWarnings("unchecked")
  void financeFactorDirectFallback() {
    when(financeBasePriceMapper.selectOne(any())).thenReturn(null);
    FinanceBasePrice latest = new FinanceBasePrice();
    latest.setPrice(new BigDecimal("80.0"));
    when(financeBasePriceMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(latest)));

    Optional<BigDecimal> result = resolver.resolve(
        var("us_yellow_copper_price"), new VariableContext().pricingMonth("2099-12"),
        Map.of("strategy", "FINANCE_FACTOR", "factorCode", "美国柜装黄铜"), null);
    assertThat(result).contains(new BigDecimal("80.0"));
  }

  @Test
  @DisplayName("未知 strategy 返 empty")
  void unknownStrategy() {
    Optional<BigDecimal> result = resolver.resolve(
        var("weird"), new VariableContext(), Map.of("strategy", "NOT_A_STRATEGY"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("binding 缺 strategy 返 empty")
  void missingStrategy() {
    Optional<BigDecimal> result = resolver.resolve(
        var("weird"), new VariableContext(), Map.of("source", "DERIVED"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("MAIN_MATERIAL_FINANCE：short_name 无命中时回退 factor_code")
  @SuppressWarnings("unchecked")
  void mainMaterialFallbackToFactorCode() {
    // 第一次 selectOne (by short_name 精确月) 无；第二次 selectOne (by factor_code 精确月) 命中
    FinanceBasePrice hit = new FinanceBasePrice();
    hit.setPrice(new BigDecimal("50"));
    // short_name 路径：精确月无；最新月也无 → selectList 返空
    // factor_code 路径：精确月命中
    when(financeBasePriceMapper.selectOne(any()))
        .thenReturn(null)    // short_name 精确月
        .thenReturn(hit);    // factor_code 精确月
    when(financeBasePriceMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>()); // short_name 最新月回退也无

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("X-CODE");
    VariableContext ctx = new VariableContext()
        .pricingMonth("2026-04").linkedItem(item);

    assertThat(resolver.resolve(var("material_price_incl"), ctx,
        Map.of("strategy", "MAIN_MATERIAL_FINANCE"), null))
        .contains(new BigDecimal("50"));
  }
}
