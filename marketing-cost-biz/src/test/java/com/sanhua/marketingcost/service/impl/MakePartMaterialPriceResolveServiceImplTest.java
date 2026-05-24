package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.service.pricing.FixedPriceResolver;
import com.sanhua.marketingcost.service.pricing.LinkedPriceResolver;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import com.sanhua.marketingcost.service.pricing.RangePriceResolver;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MakePartMaterialPriceResolveServiceImplTest {

  private MaterialPriceRouterService routerService;
  private PriceFixedItemMapper fixedMapper;
  private PriceLinkedCalcItemMapper linkedMapper;
  private PriceRangeItemMapper rangeMapper;
  private SupplierSupplyRatioResolveService ratioService;
  private MakePartMaterialPriceResolveServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
  }

  @BeforeEach
  void setUp() {
    routerService = mock(MaterialPriceRouterService.class);
    fixedMapper = mock(PriceFixedItemMapper.class);
    linkedMapper = mock(PriceLinkedCalcItemMapper.class);
    rangeMapper = mock(PriceRangeItemMapper.class);
    ratioService = mock(SupplierSupplyRatioResolveService.class);
    List<PriceResolver> resolvers = List.of(
        new FixedPriceResolver(fixedMapper, new SupplierPreferredPriceSelector(ratioService)),
        new LinkedPriceResolver(linkedMapper),
        new RangePriceResolver(rangeMapper));
    service = new MakePartMaterialPriceResolveServiceImpl(routerService, resolvers);
  }

  @Test
  @DisplayName("固定价路由：child_material_no 通过 lp_material_price_type 路由后取价成功")
  void resolvesFixedPriceForChildMaterial() {
    when(routerService.listCandidates("RAW-001", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of(route("RAW-001", PriceTypeEnum.FIXED, "固定价")));
    when(fixedMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(fixedRow("RAW-001", "82.950000")));

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            " RAW-001 ", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getMaterialCode()).isEqualTo("RAW-001");
    assertThat(result.getPriceType()).isEqualTo("固定价");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("82.950000");
    assertThat(result.getTrace()).contains("固定价", "priority=1");
    verifyNoInteractions(ratioService);
  }

  @Test
  @DisplayName("联动价路由：scrap_code 和原材料共用同一入口取价成功")
  void resolvesLinkedPriceForScrapCode() {
    when(routerService.listCandidates("SCRAP-001", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of(route("SCRAP-001", PriceTypeEnum.LINKED, "联动价")));
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
    calc.setId(8L);
    calc.setOaNo("OA-001");
    calc.setItemCode("SCRAP-001");
    calc.setPartUnitPrice(new BigDecimal("75.660000"));
    when(linkedMapper.selectList(any(Wrapper.class))).thenReturn(List.of(calc));

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            "SCRAP-001", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getPriceType()).isEqualTo("联动价");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("75.660000");
  }

  @Test
  @DisplayName("区间价路由：默认按数量 1 命中 lp_price_range_item")
  void resolvesRangePrice() {
    when(routerService.listCandidates("RANGE-001", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of(route("RANGE-001", PriceTypeEnum.RANGE, "区间价")));
    PriceRangeItem row = new PriceRangeItem();
    row.setId(10L);
    row.setMaterialCode("RANGE-001");
    row.setRangeLow(BigDecimal.ZERO);
    row.setRangeHigh(new BigDecimal("100"));
    row.setPriceInclTax(new BigDecimal("12.340000"));
    when(rangeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            "RANGE-001", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getPriceType()).isEqualTo("区间价");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("12.340000");
    assertThat(result.getRemark()).contains("区间命中", "qty=1");
  }

  @Test
  @DisplayName("缺价格类型路由：返回 MISSING_ROUTE")
  void missingRoute() {
    when(routerService.listCandidates("NO-ROUTE", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of());

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            "NO-ROUTE", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("MISSING_ROUTE");
    assertThat(result.getUnitPrice()).isNull();
    assertThat(result.getRemark()).contains("缺价格类型路由", "NO-ROUTE");
  }

  @Test
  @DisplayName("有路由但价格表无有效价：返回 MISSING_PRICE")
  void routeExistsButPriceMissing() {
    when(routerService.listCandidates("MISS-PRICE", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of(route("MISS-PRICE", PriceTypeEnum.FIXED, "固定价")));
    when(fixedMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            "MISS-PRICE", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getUnitPrice()).isNull();
    assertThat(result.getRemark()).contains("lp_price_fixed_item 无记录", "MISS-PRICE");
  }

  @Test
  @DisplayName("命中自制件价格类型：第一版不递归制造件取价")
  void makeRouteIsBlockedToAvoidRecursion() {
    when(routerService.listCandidates("MAKE-RAW", "2026-05", LocalDate.parse("2026-05-20")))
        .thenReturn(List.of(route("MAKE-RAW", PriceTypeEnum.MAKE, "自制件")));

    MakePartMaterialPriceResolveResult result =
        service.resolveMaterialUnitPrice(
            "MAKE-RAW", "2026-05", LocalDate.parse("2026-05-20"), "OA-001", "COMMERCIAL");

    assertThat(result.getStatus()).isEqualTo("UNSUPPORTED_MAKE");
    assertThat(result.getUnitPrice()).isNull();
    assertThat(result.getRemark()).contains("第一版不递归取价", "MAKE-RAW");
  }

  private PriceTypeRoute route(String materialCode, PriceTypeEnum priceType, String rawPriceType) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        priceType,
        1,
        LocalDate.parse("2026-05-01"),
        null,
        "manual",
        rawPriceType);
  }

  private PriceFixedItem fixedRow(String code, String price) {
    PriceFixedItem row = new PriceFixedItem();
    row.setId(1L);
    row.setSourceType("PURCHASE_FIXED");
    row.setMaterialCode(code);
    row.setMaterialName("原材料");
    row.setSpecModel("SPEC");
    row.setFixedPrice(new BigDecimal(price));
    row.setEffectiveFrom(LocalDate.parse("2026-05-01"));
    return row;
  }
}
