package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RangePriceResolverTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
  }

  @Test
  @DisplayName("T23：区间价按 price_as_of_time 和数量命中有效区间")
  void monthlyRangeUsesContextPriceAsOfTimeAndQuantity() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = RangePriceResolverTestSupport.create(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(range("0", "9", "5.000000"), range("10", "99", "7.500000")));
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 15, 9, 0);

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(),
            monthlyContext(priceAsOfTime));

    assertThat(result.unitPrice()).isEqualByComparingTo("7.500000");
    assertThat(result.priceSource()).isEqualTo("区间价");
    assertThat(result.remark()).contains("区间命中(10-99,qty=12");
    ArgumentCaptor<Wrapper<PriceRangeItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "effective_from")
        .contains("ORDER BY", "effective_from", "created_at", "DESC")
        .doesNotContain("effective_to");
    assertThat(paramValues(captor.getValue())).contains(LocalDate.of(2026, 5, 15));
  }

  @Test
  @DisplayName("MFRP-00：数量不落在任何区间时返回缺价")
  void quantityOutsideAllRangesReturnsMiss() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = RangePriceResolverTestSupport.create(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(range("0", "9", "5.000000"), range("20", "99", "7.500000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(),
            monthlyContext(LocalDateTime.of(2026, 5, 15, 9, 0)));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.priceSource()).isEmpty();
    assertThat(result.remark()).contains("lp_price_range_item 无有效区间价", "MAT-RANGE");
  }

  @Test
  @DisplayName("MFRP-00：无上下文时使用路由 effectiveFrom 过滤有效期")
  void routeEffectiveFromFiltersWhenContextMissing() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = RangePriceResolverTestSupport.create(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(range("0", "99", "3.140000")));
    LocalDate routeEffectiveFrom = LocalDate.of(2026, 6, 1);

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(routeEffectiveFrom),
            null);

    assertThat(result.unitPrice()).isEqualByComparingTo("3.140000");
    ArgumentCaptor<Wrapper<PriceRangeItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("effective_from", "<=")
        .contains("ORDER BY", "effective_from", "DESC", "id", "DESC")
        .doesNotContain("effective_to");
    assertThat(paramValues(captor.getValue())).contains(routeEffectiveFrom);
  }

  @Test
  @DisplayName("MFRP-00：数量区间价当前含税价优先")
  void rangePriceCurrentlyPrefersInclTaxPrice() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = RangePriceResolverTestSupport.create(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(range("0", "99", "12.340000", "10.920000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(),
            monthlyContext(LocalDateTime.of(2026, 5, 15, 9, 0)));

    assertThat(result.unitPrice()).isEqualByComparingTo("12.340000");
    assertThat(result.remark()).contains("field=price_incl_tax");
  }

  @Test
  @DisplayName("MFRP-00：含税价为空时使用不含税价")
  void rangePriceFallsBackToExclTaxWhenInclTaxMissing() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = RangePriceResolverTestSupport.create(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(range("0", "99", null, "10.920000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(),
            monthlyContext(LocalDateTime.of(2026, 5, 15, 9, 0)));

    assertThat(result.unitPrice()).isEqualByComparingTo("10.920000");
    assertThat(result.remark()).contains("field=price_excl_tax");
  }

  @Test
  @DisplayName("MFRP-05：CU 规则按报价单铜价命中行情因素区间价")
  void factorRangeUsesQuoteCopperPrice() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(factorRule("CU", 101L)));
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCopperPrice(new BigDecimal("90000"));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(itemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(factorRange("87501", "92500", "0.3920353982300885")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("201850160", "0.655"),
            route(),
            quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.3920353982300885");
    assertThat(result.resultRefId()).isEqualTo(601L);
    assertThat(result.priceSource()).isEqualTo("区间价");
    assertThat(result.remark()).contains("行情区间命中", "CU=90000", "87501-92500");
  }

  @Test
  @DisplayName("主供已过审批有效期仍沿用，并返回非阻断提醒")
  void factorRangeCarriesForwardExpiredPrimarySupplierPrice() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem supplierA = factorRange(
        701L, "供应商A", "SUP-A", "0.410000", LocalDate.of(2026, 7, 1), null);
    PriceRangeItem expiredSupplierB = factorRange(
        702L, "供应商B", "SUP-B", "0.390000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(itemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(supplierA, expiredSupplierB));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("供应商B", "SUP-B", "0.70"));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.390000");
    assertThat(result.resultRefId()).isEqualTo(702L);
    assertThat(result.carriedForward()).isTrue();
    assertThat(result.effectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(result.warningMessage()).contains("沿用历史价", "2026-06-30");
    ArgumentCaptor<Wrapper<PriceRangeItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("effective_from", "ORDER BY", "id", "DESC")
        .doesNotContain("effective_to <=", "effective_to >=");
    assertThat(paramValues(captor.getValue())).contains(LocalDate.of(2026, 7, 1));
  }

  @Test
  @DisplayName("RPI1-10：6月两个有效供应商按供货比例最大且按代码选择")
  void factorRangeSelectsHighestRatioSupplierByCode() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem supplierA = factorRange(
        702L, "同名供应商", "SUP-A", "0.420000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    PriceRangeItem supplierB = factorRange(
        701L, "同名供应商", "SUP-B", "0.380000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(supplierA, supplierB));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("同名供应商", "SUP-B", "0.7"));

    PriceResolveResult result = resolver.resolve(
        "OA-001",
        part("201850160", "1"),
        route(LocalDate.of(2026, 6, 15)),
        quoteContextAt("COMMERCIAL", LocalDateTime.of(2026, 6, 15, 9, 0)));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.380000");
    assertThat(result.resultRefId()).isEqualTo(701L);
    assertThat(result.remark()).contains("按主供应商供货比例匹配价格");
    assertThat(result.remark()).contains(
        "区间价取价底稿[",
        "物料代码=201850160",
        "影响因素代码=CU",
        "报价单行情值=90000",
        "命中区间=87501-92500",
        "候选供应商数量=2",
        "主供应商名称=同名供应商",
        "主供应商代码=SUP-B",
        "供货比例=0.7",
        "供应商匹配方式=供应商代码",
        "最终价格行ID=701",
        "最终不含税单价=0.38");
    verify(ratioService).resolve(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("RPI1-10：价格候选缺代码时按完整供应商名称匹配")
  void factorRangeFallsBackToSupplierNameWhenCandidateCodeMissing() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem supplierA = factorRange(702L, "供应商A", "SUP-A", "0.420000", null, null);
    PriceRangeItem supplierB = factorRange(
        701L, "吉林省 合信汽配有限公司", null, "0.380000", null, null);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(supplierA, supplierB));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("吉林省合信汽配有限公司", "SUP-B", "0.7"));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.380000");
    assertThat(result.resultRefId()).isEqualTo(701L);
    assertThat(result.remark()).contains("供应商匹配方式=供应商名称兜底");
  }

  @Test
  @DisplayName("主供代码与所有价格不同时阻断，不按原排序降级")
  void factorRangeBlocksWhenPrimarySupplierHasNoPrice() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem fallback = factorRange(702L, "同名供应商", "SUP-A", "0.420000", null, null);
    PriceRangeItem other = factorRange(701L, "同名供应商", "SUP-B", "0.380000", null, null);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(fallback, other));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("同名供应商", "SUP-C", "0.8"));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.failureCode())
        .isEqualTo(SupplierPreferredPriceSelector.PRIMARY_SUPPLIER_PRICE_MISSING);
    assertThat(result.remark()).contains("主供应商无价格", "SUP-C");
  }

  @Test
  @DisplayName("多供应商未维护供货比例时阻断")
  void factorRangeMissingRatioBlocks() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem first = factorRange(702L, "供应商A", "SUP-A", "0.420000", null, null);
    PriceRangeItem second = factorRange(701L, "供应商B", "SUP-B", "0.380000", null, null);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未维护"));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.failureCode())
        .isEqualTo(SupplierPreferredPriceSelector.SUPPLIER_RATIO_MISSING);
  }

  @Test
  @DisplayName("RPI1-10：候选均无供应商身份时保持原历史排序")
  void factorRangeWithoutSupplierIdentityKeepsLegacyFirstRow() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem first = factorRange(702L, null, null, "0.420000", null, null);
    PriceRangeItem second = factorRange(701L, null, null, "0.380000", null, null);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.420000");
    assertThat(result.resultRefId()).isEqualTo(702L);
    verifyNoInteractions(ratioService);
  }

  @Test
  @DisplayName("RPI1-10：行情区间候选不含税价全为空时返回缺价")
  void factorRangeAllExclTaxPricesMissingReturnsMiss() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    RangePriceResolver resolver = factorResolver(itemMapper, ruleMapper, oaFormMapper, ratioService);
    stubCopperFactor(ruleMapper, oaFormMapper);
    PriceRangeItem empty = factorRange(701L, "供应商A", "SUP-A", null, null, null);
    empty.setPriceInclTax(new BigDecimal("0.450000"));
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(empty));

    PriceResolveResult result = resolver.resolve(
        "OA-001", part("201850160", "1"), route(), quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("未命中当前区间");
    verifyNoInteractions(ratioService);
  }

  @Test
  @DisplayName("MFRP-05：报价单铜价为空时返回缺价且不查区间明细")
  void factorRangeMissingQuoteFactorValueReturnsMiss() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(factorRule("CU", 101L)));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(new OaForm());

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("201850160", "0.655"),
            route(),
            quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("缺少报价单行情值", "CU");
    verify(itemMapper, never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("MFRP-05：当前行情规则不命中时返回缺价，不回退旧 QTY")
  void factorRangeMissDoesNotFallbackToQtyRange() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(factorRule("CU", 102L)));
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCopperPrice(new BigDecimal("98000"));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("201850160", "0.655"),
            route(),
            quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("未命中当前区间", "value=98000");
    verify(itemMapper, times(1)).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("MFRP-05：无行情因素规则时旧数量区间价继续可用")
  void noFactorRuleFallsBackToQtyRange() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(range("0", "9", "5.000000")));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("201850160", "5"),
            route(),
            quoteContext("COMMERCIAL"));

    assertThat(result.unitPrice()).isEqualByComparingTo("5.000000");
    ArgumentCaptor<Wrapper<PriceRangeItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(itemMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("range_basis")
        .contains("IS NULL");
  }

  @Test
  @DisplayName("MFRP-05：行情因素规则按 business_unit_type 隔离")
  void factorRuleQueryUsesBusinessUnitType() {
    PriceRangeItemMapper itemMapper = mock(PriceRangeItemMapper.class);
    PriceRangeFactorRuleMapper ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    RangePriceResolver resolver =
        RangePriceResolverTestSupport.create(itemMapper, ruleMapper, oaFormMapper);
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    resolver.resolve(
        "OA-001",
        part("201850160", "5"),
        route(),
        quoteContext("COMMERCIAL"));

    ArgumentCaptor<Wrapper<PriceRangeFactorRule>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(ruleMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("business_unit_type", "current_flag", "ORDER BY");
    assertThat(paramValues(captor.getValue())).contains("COMMERCIAL");
  }

  private static CostRunPartItemDto part(String code, String qty) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(code);
    item.setPartQty(new BigDecimal(qty));
    return item;
  }

  private static PriceTypeRoute route() {
    return route(LocalDate.of(2026, 5, 1));
  }

  private static PriceTypeRoute route(LocalDate effectiveFrom) {
    return new PriceTypeRoute(
        "MAT-RANGE",
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.RANGE,
        1,
        effectiveFrom,
        null,
        "manual",
        "区间价");
  }

  private static CostRunContext monthlyContext(LocalDateTime priceAsOfTime) {
    return CostRunContext.monthlyReprice(
        "2026-05",
        88L,
        "MRP-001",
        "COMMERCIAL",
        priceAsOfTime,
        CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        "OA-001",
        1L,
        "P-001",
        null,
        null,
        "OBJ-001");
  }

  private static CostRunContext quoteContext(String businessUnitType) {
    return quoteContextAt(businessUnitType, LocalDateTime.of(2026, 7, 1, 9, 0));
  }

  private static CostRunContext quoteContextAt(
      String businessUnitType,
      LocalDateTime priceAsOfTime) {
    return CostRunContext.quote(
        "OA-001",
        1L,
        "P-001",
        null,
        null,
        businessUnitType,
        String.format("%04d-%02d", priceAsOfTime.getYear(), priceAsOfTime.getMonthValue()),
        priceAsOfTime,
        "OBJ-001");
  }

  private static PriceRangeItem range(String low, String high, String price) {
    return range(low, high, price, null);
  }

  private static PriceRangeItem range(String low, String high, String priceInclTax, String priceExclTax) {
    PriceRangeItem item = new PriceRangeItem();
    item.setMaterialCode("MAT-RANGE");
    item.setRangeLow(new BigDecimal(low));
    item.setRangeHigh(new BigDecimal(high));
    if (priceInclTax != null) {
      item.setPriceInclTax(new BigDecimal(priceInclTax));
    }
    if (priceExclTax != null) {
      item.setPriceExclTax(new BigDecimal(priceExclTax));
    }
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return item;
  }

  private static PriceRangeItem factorRange(String low, String high, String priceExclTax) {
    PriceRangeItem item = factorRange(
        601L,
        null,
        null,
        priceExclTax,
        LocalDate.of(2026, 7, 1),
        null);
    item.setRangeLow(new BigDecimal(low));
    item.setRangeHigh(new BigDecimal(high));
    return item;
  }

  private static PriceRangeItem factorRange(
      Long id,
      String supplierName,
      String supplierCode,
      String priceExclTax,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    PriceRangeItem item = new PriceRangeItem();
    item.setId(id);
    item.setBusinessUnitType("COMMERCIAL");
    item.setMaterialCode("201850160");
    item.setMaterialName("区间产品");
    item.setSpecModel("SPEC-RANGE");
    item.setSupplierName(supplierName);
    item.setSupplierCode(supplierCode);
    item.setRangeBasis("FACTOR");
    item.setFactorRuleId(101L);
    item.setCurrentFlag(1);
    item.setRangeLow(new BigDecimal("87501"));
    item.setRangeHigh(new BigDecimal("92500"));
    if (priceExclTax != null) {
      item.setPriceExclTax(new BigDecimal(priceExclTax));
    }
    item.setEffectiveFrom(effectiveFrom);
    item.setEffectiveTo(effectiveTo);
    return item;
  }

  private static RangePriceResolver factorResolver(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper ruleMapper,
      OaFormMapper oaFormMapper,
      SupplierSupplyRatioResolveService ratioService) {
    return new RangePriceResolver(
        itemMapper,
        ruleMapper,
        oaFormMapper,
        new SupplierPreferredPriceSelector(ratioService));
  }

  private static void stubCopperFactor(
      PriceRangeFactorRuleMapper ruleMapper,
      OaFormMapper oaFormMapper) {
    when(ruleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(factorRule("CU", 101L)));
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCopperPrice(new BigDecimal("90000"));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
  }

  private static SupplierSupplyRatioResolveResult mainSupplier(
      String supplierName,
      String supplierCode,
      String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setSupplierName(supplierName);
    result.setSupplierCode(supplierCode);
    result.setSupplyRatio(new BigDecimal(ratio));
    return result;
  }

  private static PriceRangeFactorRule factorRule(String factorCode, Long id) {
    PriceRangeFactorRule rule = new PriceRangeFactorRule();
    rule.setId(id);
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setMaterialCode("201850160");
    rule.setFactorCode(factorCode);
    rule.setVersionNo(1);
    rule.setCurrentFlag(1);
    return rule;
  }

  private static List<Object> paramValues(Wrapper<?> wrapper) {
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }
}
