package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DerivedResolver 单元测试 —— 覆盖 4 种派生策略 + 各自 fallback/缺数据分支。 */
class DerivedResolverTest {

  private FinanceFactorVariableQuery financeQuery;
  private MaterialScrapRefMapper materialScrapRefMapper;
  private DerivedResolver resolver;
  private com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService scrapPrices;

  @BeforeEach
  void setUp() {
    financeQuery = mock(FinanceFactorVariableQuery.class);
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    scrapPrices = mock(com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService.class);
    when(scrapPrices.selectForFormula(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService.Selection("FIXED",
            new BigDecimal("60.00"), new com.sanhua.marketingcost.service.pricing.PriceResolveEvidence(
                1L, "SCRAP", null, null, null, null, null, null, false, null, "{}"), "废料月价"));
    resolver = new DerivedResolver(financeQuery, materialScrapRefMapper, scrapPrices);
  }

  private static PriceVariable var(String code) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setFactorType("PART_CONTEXT");
    v.setTaxMode("EXCL");
    return v;
  }

  @Test
  @DisplayName("MAIN_MATERIAL_FINANCE：按 materialCode 查 short_name 命中")
  void mainMaterialByShortName() {
    when(financeQuery.queryName(any(), any())).thenReturn(Optional.of(new BigDecimal("95.00")));

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("美国柜装黄铜");
    VariableContext ctx = new VariableContext().pricingMonth("2026-04").linkedItem(item);

    Optional<BigDecimal> result =
        resolver.resolve(
            var("material_price_incl"),
            ctx,
            Map.of("source", "DERIVED", "strategy", "MAIN_MATERIAL_FINANCE"),
            null);
    assertThat(result).contains(new BigDecimal("95.00"));
  }

  @Test
  @DisplayName("MAIN_MATERIAL_FINANCE：linkedItem 为空返回 empty")
  void mainMaterialNoLinkedItem() {
    Optional<BigDecimal> result =
        resolver.resolve(
            var("material_price_incl"),
            new VariableContext(),
            Map.of("strategy", "MAIN_MATERIAL_FINANCE"),
            null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("SCRAP_REF：部品→废料映射命中 × ratio=0.92")
  @SuppressWarnings("unchecked")
  void scrapRefWithRatio() {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setScrapCode("铜沫");
    ref.setRatio(new BigDecimal("0.92"));
    when(materialScrapRefMapper.selectList(any())).thenReturn(java.util.List.of(ref));


    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("HPb59-1");
    item.setBusinessUnitType("COMMERCIAL");
    item.setOrgCode("220");
    var reviews = new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(171L)
        .forOrganization("220");
    var date = java.time.LocalDate.of(2026, 4, 19);
    VariableContext ctx = new VariableContext().pricingMonth("2026-04").linkedItem(item)
        .oaNo("CURRENT-OA").quoteDate(date).supplierReviews(reviews);

    Optional<BigDecimal> result =
        resolver.resolve(var("scrap_price_incl"), ctx, Map.of("strategy", "SCRAP_REF"), null);
    // 60 × 0.92 = 55.20
    assertThat(result).isPresent();
    assertThat(result.get()).isEqualByComparingTo(new BigDecimal("55.20"));
    org.mockito.Mockito.verify(scrapPrices).selectForFormula(
        "COMMERCIAL", "2026-04", "铜沫", "220", "CURRENT-OA", date, false, reviews);
  }

  @Test
  @DisplayName("SCRAP_REF：映射表无记录返回 empty")
  void scrapRefMissingMapping() {
    when(materialScrapRefMapper.selectList(any())).thenReturn(java.util.List.of());

    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("未知部品");
    VariableContext ctx = new VariableContext().pricingMonth("2026-10").linkedItem(item);

    Optional<BigDecimal> result =
        resolver.resolve(var("scrap_price_incl"), ctx, Map.of("strategy", "SCRAP_REF"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("SCRAP_REF：ratio 为空时按 1 处理（直接返独立废料价）")
  @SuppressWarnings("unchecked")
  void scrapRefNullRatio() {
    MaterialScrapRef ref = new MaterialScrapRef();
    ref.setScrapCode("铜沫");
    ref.setRatio(null);
    when(materialScrapRefMapper.selectList(any())).thenReturn(java.util.List.of(ref));


    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode("X");
    VariableContext ctx = new VariableContext().pricingMonth("2026-10").linkedItem(item);

    assertThat(
            resolver.resolve(var("scrap_price_incl"), ctx, Map.of("strategy", "SCRAP_REF"), null))
        .contains(new BigDecimal("60.00"));
  }

  @Test
  void declaredInclusiveVariableUsesConfiguredVatOnceAndRejectsAmbiguousMappings() {
    var ref = new MaterialScrapRef(); ref.setScrapCode("SCRAP");
    when(materialScrapRefMapper.selectList(any())).thenReturn(java.util.List.of(ref));
    var item = new PriceLinkedItem(); item.setMaterialCode("RAW");
    var context = new VariableContext().pricingMonth("2026-10").linkedItem(item);
    var variable = var("scrap_price_incl"); variable.setTaxMode("INCL");
    assertThat(resolver.resolve(variable, context, Map.of("strategy", "SCRAP_REF"),
        code -> Optional.of(new BigDecimal("0.13")))).contains(new BigDecimal("67.8000"));
    assertThat(context.getPrimitiveInputs().get("scrap_price_incl:taxMultiplier")).isEqualByComparingTo("1.13");
    assertThat(context.getScrapPriceInputs()).containsKey("scrap_price_incl");
    assertThatThrownBy(() -> resolver.resolve(variable, context, Map.of("strategy", "SCRAP_REF"), code -> Optional.empty()))
        .hasMessageContaining("vat_rate");
    var second = new MaterialScrapRef(); second.setScrapCode("OTHER");
    when(materialScrapRefMapper.selectList(any())).thenReturn(java.util.List.of(ref, second));
    assertThatThrownBy(() -> resolver.resolve(variable, context, Map.of("strategy", "SCRAP_REF"), null))
        .hasMessageContaining("多个有效映射");
    org.mockito.Mockito.verifyNoInteractions(financeQuery);
  }

  @Test
  @DisplayName("FORMULA_REF：[Cu]*0.59+[Zn]*0.41 with Cu=90 Zn=21.68 ≈ 61.0")
  void formulaRefCopperScrap() {
    VariableContext ctx =
        new VariableContext()
            .override("Cu", new BigDecimal("90"))
            .override("Zn", new BigDecimal("21.68"));
    Optional<BigDecimal> result =
        resolver.resolve(
            var("copper_scrap_price"),
            ctx,
            Map.of("strategy", "FORMULA_REF", "formulaRef", "[Cu]*0.59+[Zn]*0.41"),
            null);
    // 90 × 0.59 = 53.1; 21.68 × 0.41 = 8.8888; 合计 61.9888
    assertThat(result).isPresent();
    assertThat(result.get().doubleValue()).isBetween(61.5, 62.2);
  }

  @Test
  @DisplayName("FORMULA_REF：子变量缺失时报告依赖名称")
  void formulaRefMissingSubVar() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    var("copper_scrap_price"),
                    new VariableContext(),
                    Map.of("strategy", "FORMULA_REF", "formulaRef", "[Cu]*10"),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("copper_scrap_price")
        .hasMessageContaining("Cu");
  }

  @Test
  @DisplayName("FORMULA_REF：formulaRef 空返 empty")
  void formulaRefMissingExpr() {
    Optional<BigDecimal> result =
        resolver.resolve(
            var("copper_scrap_price"),
            new VariableContext(),
            Map.of("strategy", "FORMULA_REF"),
            null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("FINANCE_FACTOR 直指：按 binding.factorCode 查")
  void financeFactorDirect() {
    when(financeQuery.queryName(any(), any())).thenReturn(Optional.of(new BigDecimal("78.5")));

    Optional<BigDecimal> result =
        resolver.resolve(
            var("us_yellow_copper_price"),
            new VariableContext().pricingMonth("2026-04"),
            Map.of("strategy", "FINANCE_FACTOR", "factorCode", "美国柜装黄铜"),
            null);
    assertThat(result).contains(new BigDecimal("78.5"));
  }

  @Test
  @DisplayName("未知 strategy 返 empty")
  void unknownStrategy() {
    Optional<BigDecimal> result =
        resolver.resolve(
            var("weird"), new VariableContext(), Map.of("strategy", "NOT_A_STRATEGY"), null);
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("binding 缺 strategy 返 empty")
  void missingStrategy() {
    Optional<BigDecimal> result =
        resolver.resolve(var("weird"), new VariableContext(), Map.of("source", "DERIVED"), null);
    assertThat(result).isEmpty();
  }

  @Test
  void ambiguousPublishedFactorPropagatesInsteadOfGuessingAnotherSource() {
    when(financeQuery.queryName(any(), any()))
        .thenThrow(new IllegalArgumentException("财务因素存在多个价源"));
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    var("brass"),
                    new VariableContext(),
                    Map.of("strategy", "FINANCE_FACTOR", "factorCode", "黄铜"),
                    null))
        .hasMessageContaining("多个价源");
  }

  @Test
  void missingPublishedFactorRemainsMissing() {
    when(financeQuery.queryName(any(), any())).thenReturn(Optional.empty());
    assertThat(
            resolver.resolve(
                var("brass"),
                new VariableContext(),
                Map.of("strategy", "FINANCE_FACTOR", "factorCode", "黄铜"),
                null))
        .isEmpty();
  }
}
