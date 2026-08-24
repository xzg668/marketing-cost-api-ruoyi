package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import com.sanhua.marketingcost.service.pricing.RangePriceResolver;
import com.sanhua.marketingcost.service.pricing.RangePriceResolverTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("MFRP-09 行情因素区间价回归隔离")
class MarketFactorRangePriceRegressionIsolationTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
  }

  @Test
  @DisplayName("没有 factor_rule 的区间价在月度重价中继续按数量区间取价")
  void rangeWithoutFactorRuleKeepsQtyRangeInMonthlyReprice() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(itemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(qtyRange("MAT-QTY", "0", "10", "6.000000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-QTY",
            part("MAT-QTY", "6"),
            route("MAT-QTY", PriceTypeEnum.RANGE, "区间价"),
            monthlyContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("6.000000");
    assertThat(result.remark()).contains("区间命中", "qty=6");
    verifyNoInteractions(oaFormMapper);
    ArgumentCaptor<Wrapper<PriceRangeItem>> itemQuery =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(itemQuery.capture());
    assertThat(itemQuery.getValue().getCustomSqlSegment())
        .contains("material_code", "range_basis", "IS NULL", "effective_from")
        .doesNotContain("factor_rule_id", "effective_to", "current_flag");
    assertThat(paramValues(itemQuery.getValue())).contains(LocalDate.of(2026, 5, 26));
  }

  @Test
  @DisplayName("有 factor_rule 的区间价只查 FACTOR 当前明细，未命中时不回退 QTY")
  void factorRuleRangeDoesNotFallbackToQtyRange() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(factorRule(101L, "COMMERCIAL", "201850160", "CU")));
    OaForm form = new OaForm();
    form.setOaNo("OA-CU");
    form.setCopperPrice(new BigDecimal("98000"));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PriceResolveResult result =
        resolver.resolve(
            "OA-CU",
            part("201850160", "0.655"),
            route("201850160", PriceTypeEnum.RANGE, "区间价"),
            quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("未命中当前区间", "value=98000");
    ArgumentCaptor<Wrapper<PriceRangeItem>> itemQuery =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper, times(1)).selectList(itemQuery.capture());
    assertThat(itemQuery.getValue().getCustomSqlSegment())
        .contains("range_basis", "factor_rule_id", "effective_from")
        .doesNotContain("material_code", "current_flag", "effective_to");
    assertThat(paramValues(itemQuery.getValue())).contains(101L, new BigDecimal("98000"));
  }

  @Test
  @DisplayName("行情规则查询按 business_unit_type 隔离，且只取 current_flag=1")
  void factorRuleLookupIsIsolatedByBusinessUnitAndCurrentFlag() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(factorRule(202L, "HOUSEHOLD", "MAT-ZN", "ZN")));
    OaForm form = new OaForm();
    form.setOaNo("OA-ZN");
    form.setZincPrice(new BigDecimal("26000"));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(itemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(factorRange(902L, 202L, "20000", "30000", "0.120000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-ZN",
            part("MAT-ZN", "3"),
            route("MAT-ZN", PriceTypeEnum.RANGE, "区间价"),
            quoteContext("HOUSEHOLD"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.120000");
    ArgumentCaptor<Wrapper<PriceRangeFactorRule>> ruleQuery =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(ruleMapper).selectList(ruleQuery.capture());
    assertThat(ruleQuery.getValue().getCustomSqlSegment())
        .contains("business_unit_type", "material_code", "current_flag", "ORDER BY");
    assertThat(paramValues(ruleQuery.getValue())).contains("HOUSEHOLD", "MAT-ZN", 1);
    ArgumentCaptor<Wrapper<PriceRangeItem>> itemQuery =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(itemQuery.capture());
    assertThat(itemQuery.getValue().getCustomSqlSegment())
        .contains("range_basis", "factor_rule_id", "effective_from")
        .doesNotContain("current_flag", "effective_to");
    assertThat(paramValues(itemQuery.getValue())).contains(202L);
  }

  @Test
  @DisplayName("固定价/联动价/结算价路由不受 RANGE Resolver 注册顺序影响")
  void typedResolverDispatchKeepsFixedLinkedAndSettleIsolatedFromRangeResolver() {
    MaterialPriceRouterService routerService = mock(MaterialPriceRouterService.class);
    LinkedPriceEnsureService linkedEnsureService = mock(LinkedPriceEnsureService.class);
    PriceResolver rangeResolver = resolver(PriceTypeEnum.RANGE);
    PriceResolver linkedResolver = resolver(PriceTypeEnum.LINKED);
    PriceResolver fixedResolver = resolver(PriceTypeEnum.FIXED);
    NormalMaterialPricePrepareStrategyImpl strategy =
        new NormalMaterialPricePrepareStrategyImpl(
            routerService,
            linkedEnsureService,
            List.of(rangeResolver, linkedResolver, fixedResolver));
    PriceTypeRoute settleRoute = route("MAT-MIX", PriceTypeEnum.FIXED, "结算价");
    PriceTypeRoute linkedRoute = route("MAT-MIX", PriceTypeEnum.LINKED, "联动价");
    PriceTypeRoute rangeRoute = route("MAT-MIX", PriceTypeEnum.RANGE, "区间价");
    when(routerService.listCandidates(eq("MAT-MIX"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of(settleRoute, linkedRoute, rangeRoute));
    when(linkedEnsureService.ensure(any())).thenReturn(new LinkedPriceEnsureResult());
    when(fixedResolver.resolve(eq("OA-MIX"), any(CostRunPartItemDto.class), eq(settleRoute),
        any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.miss("结算固定价无记录"));
    when(linkedResolver.resolve(eq("OA-MIX"), any(CostRunPartItemDto.class), eq(linkedRoute),
        any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("7.000000"), "联动价"));
    when(rangeResolver.resolve(any(), any(), any(), any()))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("99.000000"), "区间价"));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-MIX", "COMMERCIAL", "2026-05", planItem("MAT-MIX", "2"));

    assertThat(PriceTypeEnum.fromDbText("结算价")).contains(PriceTypeEnum.FIXED);
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("7.000000");
    assertThat(result.getAmount()).isEqualByComparingTo("14.000000");
    assertThat(result.getResultRefType()).isEqualTo("LINKED_PRICE");
    InOrder order = inOrder(fixedResolver, linkedResolver);
    order.verify(fixedResolver).resolve(eq("OA-MIX"), any(CostRunPartItemDto.class),
        eq(settleRoute), any(CostRunContext.class));
    order.verify(linkedResolver).resolve(eq("OA-MIX"), any(CostRunPartItemDto.class),
        eq(linkedRoute), any(CostRunContext.class));
    verify(rangeResolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("批量 FACTOR 导入校验失败不会插入新版本或污染旧版本")
  void failedFactorImportDoesNotPolluteExistingVersions() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    PriceRangeItemServiceImpl service = new PriceRangeItemServiceImpl(
        itemMapper,
        ruleMapper,
        mock(com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.class));
    PriceRangeFactorRule existingRule = factorRule(301L, "COMMERCIAL", "MAT-FAIL", "CU");
    PriceRangeItem existingItem = factorRange(901L, 301L, "80000", "90000", "0.390000");
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        importRow("MAT-FAIL", "87501", "92500", "0.392035"),
        importRow("MAT-FAIL", "92400", "97500", "0.412035"));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠");

    assertThat(existingRule.getCurrentFlag()).isOne();
    assertThat(existingRule.getEffectiveTo()).isNull();
    assertThat(existingItem.getCurrentFlag()).isOne();
    assertThat(existingItem.getEffectiveTo()).isNull();
    verifyNoInteractions(ruleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  private static PriceResolver resolver(PriceTypeEnum priceType) {
    PriceResolver resolver = mock(PriceResolver.class);
    when(resolver.priceType()).thenReturn(priceType);
    return resolver;
  }

  private static CostRunPartItemDto part(String code, String qty) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(code);
    item.setPartQty(new BigDecimal(qty));
    return item;
  }

  private static PriceTypeRoute route(String materialCode, PriceTypeEnum priceType, String rawPriceType) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        priceType,
        1,
        LocalDate.of(2026, 5, 1),
        null,
        "manual",
        rawPriceType);
  }

  private static CostRunContext quoteContext(String businessUnitType) {
    return CostRunContext.quote(
        "OA",
        1L,
        "P-001",
        null,
        null,
        businessUnitType,
        "2026-05",
        LocalDateTime.of(2026, 5, 26, 10, 0),
        "OBJ-001");
  }

  private static CostRunContext monthlyContext(String businessUnitType) {
    return CostRunContext.monthlyReprice(
        "2026-05",
        88L,
        "MRP-001",
        businessUnitType,
        LocalDateTime.of(2026, 5, 26, 10, 0),
        CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        "OA-QTY",
        1L,
        "P-001",
        null,
        null,
        "OBJ-001");
  }

  private static PricePreparePlanItem planItem(String materialCode, String quantity) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-MIX");
    row.setTopProductCode("TOP-001");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setShapeAttr("采购件");
    row.setQtyPerTop(new BigDecimal(quantity));
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(row.getTopProductCode());
    item.setMaterialCode(materialCode);
    item.setMaterialName(row.getMaterialName());
    item.setItemType("NORMAL");
    item.setStatus("READY");
    return item;
  }

  private static PriceRangeItem qtyRange(String materialCode, String low, String high, String price) {
    PriceRangeItem item = new PriceRangeItem();
    item.setId(801L);
    item.setMaterialCode(materialCode);
    item.setRangeLow(new BigDecimal(low));
    item.setRangeHigh(new BigDecimal(high));
    item.setPriceInclTax(new BigDecimal(price));
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return item;
  }

  private static PriceRangeItem factorRange(
      Long id,
      Long ruleId,
      String low,
      String high,
      String price) {
    PriceRangeItem item = new PriceRangeItem();
    item.setId(id);
    item.setRangeBasis("FACTOR");
    item.setFactorRuleId(ruleId);
    item.setFactorCode("CU");
    item.setCurrentFlag(1);
    item.setRangeLow(new BigDecimal(low));
    item.setRangeHigh(new BigDecimal(high));
    item.setPriceExclTax(new BigDecimal(price));
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return item;
  }

  private static PriceRangeFactorRule factorRule(
      Long id,
      String businessUnitType,
      String materialCode,
      String factorCode) {
    PriceRangeFactorRule rule = new PriceRangeFactorRule();
    rule.setId(id);
    rule.setBusinessUnitType(businessUnitType);
    rule.setMaterialCode(materialCode);
    rule.setFactorCode(factorCode);
    rule.setVersionNo(1);
    rule.setCurrentFlag(1);
    rule.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return rule;
  }

  private static PriceRangeItemImportRequest factorRequest(
      String factorCode,
      PriceRangeItemImportRequest.PriceRangeItemImportRow... rows) {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRangeBasis("FACTOR");
    request.setFactorCode(factorCode);
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("元/米");
    request.setSourceFile("range-price.xlsx");
    request.setSourceSheet("区间铜价");
    request.setImportBatchNo("MFRP-09-FAIL");
    request.setRows(List.of(rows));
    return request;
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow importRow(
      String materialCode,
      String low,
      String high,
      String price) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setMaterialCode(materialCode);
    row.setMaterialName("测试物料");
    row.setSpecModel("SPEC");
    row.setRangeLow(new BigDecimal(low));
    row.setRangeHigh(new BigDecimal(high));
    row.setPriceExclTax(new BigDecimal(price));
    row.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    return row;
  }

  private static List<Object> paramValues(Wrapper<?> wrapper) {
    wrapper.getCustomSqlSegment();
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }
}
