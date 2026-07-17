package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NormalMaterialPricePrepareStrategyImplTest {

  private MaterialPriceRouterService routerService;
  private LinkedPriceEnsureService linkedPriceEnsureService;
  private PriceResolver fixedResolver;
  private PriceResolver linkedResolver;
  private PriceResolver rangeResolver;
  private NormalMaterialPricePrepareStrategyImpl strategy;

  @BeforeEach
  void setUp() {
    routerService = mock(MaterialPriceRouterService.class);
    linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    fixedResolver = mock(PriceResolver.class);
    linkedResolver = mock(PriceResolver.class);
    rangeResolver = mock(PriceResolver.class);
    when(fixedResolver.priceType()).thenReturn(PriceTypeEnum.FIXED);
    when(linkedResolver.priceType()).thenReturn(PriceTypeEnum.LINKED);
    when(rangeResolver.priceType()).thenReturn(PriceTypeEnum.RANGE);
    strategy = new NormalMaterialPricePrepareStrategyImpl(
        routerService, linkedPriceEnsureService, List.of(fixedResolver, linkedResolver, rangeResolver));
  }

  @Test
  @DisplayName("普通料号：固定价成功写单价、金额和来源")
  void fixedPriceSuccess() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 10, 9, 30);
    PricePreparePlanItem planItem = planItem("MAT-FIX", new BigDecimal("2.5"));
    PriceTypeRoute route = route("MAT-FIX", PriceTypeEnum.FIXED);
    when(routerService.listCandidates(eq("MAT-FIX"), eq("2026-05"), eq(priceAsOfTime.toLocalDate())))
        .thenReturn(List.of(route));
    when(fixedResolver.resolve(
            eq("OA-001"), any(CostRunPartItemDto.class), eq(route), any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("12.30"), "固定采购价"));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", priceAsOfTime, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("12.30");
    assertThat(result.getAmount()).isEqualByComparingTo("30.750");
    assertThat(result.getPriceSource()).isEqualTo("固定采购价");
    assertThat(result.getResultRefType()).isEqualTo("FIXED_PRICE");
    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(fixedResolver).resolve(
        eq("OA-001"), any(CostRunPartItemDto.class), eq(route), contextCaptor.capture());
    assertThat(contextCaptor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    verifyNoInteractions(linkedPriceEnsureService);
  }

  @Test
  @DisplayName("普通料号：联动价先 ensure 再读取 Resolver 结果")
  void linkedPriceEnsuresBeforeResolve() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 11, 10, 15);
    PricePreparePlanItem planItem = planItem("MAT-LINK", new BigDecimal("3"));
    PriceTypeRoute route = route("MAT-LINK", PriceTypeEnum.LINKED);
    when(routerService.listCandidates(eq("MAT-LINK"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of(route));
    when(linkedPriceEnsureService.ensure(any(LinkedPriceEnsureRequest.class)))
        .thenReturn(new LinkedPriceEnsureResult());
    when(linkedResolver.resolve(
            eq("OA-001"), any(CostRunPartItemDto.class), eq(route), any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("7.00"), "联动价"));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", priceAsOfTime, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getResultRefType()).isEqualTo("LINKED_PRICE");
    ArgumentCaptor<LinkedPriceEnsureRequest> captor =
        ArgumentCaptor.forClass(LinkedPriceEnsureRequest.class);
    verify(linkedPriceEnsureService).ensure(captor.capture());
    assertThat(captor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    assertThat(captor.getValue().normalizedItemCodes()).containsExactly("MAT-LINK");
  }

  @Test
  @DisplayName("第四步联动价只在内存计算，不调用 ensure 或数据库 Resolver")
  void linkedPriceCalculateDoesNotPersist() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 11, 10, 15);
    PricePreparePlanItem planItem = planItem("MAT-LINK", new BigDecimal("3"));
    PriceTypeRoute route = route("MAT-LINK", PriceTypeEnum.LINKED);
    when(routerService.listCandidates(eq("MAT-LINK"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of(route));
    PriceLinkedCalcItem calculated = new PriceLinkedCalcItem();
    calculated.setItemCode("MAT-LINK");
    calculated.setPartUnitPrice(new BigDecimal("7.00"));
    calculated.setCalcStatus("OK");
    when(linkedPriceEnsureService.calculate(any(LinkedPriceEnsureRequest.class)))
        .thenReturn(List.of(calculated));

    NormalMaterialPricePrepareResult result = strategy.calculate(
        "OA-001", "COMMERCIAL", "2026-05", priceAsOfTime, null, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("7.00");
    assertThat(result.getAmount()).isEqualByComparingTo("21.00");
    verify(linkedPriceEnsureService, never()).ensure(any());
    verify(linkedResolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("普通料号：缺价格类型写 MISSING_PRICE_TYPE")
  void missingPriceType() {
    PricePreparePlanItem planItem = planItem("MAT-NO-ROUTE", BigDecimal.ONE);
    when(routerService.listCandidates(eq("MAT-NO-ROUTE"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of());

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem);

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE_TYPE");
    assertThat(result.getGapType()).isEqualTo("MISSING_PRICE_TYPE");
    assertThat(result.getSourceTable()).isEqualTo("lp_material_price_type");
    assertThat(result.getMessage()).contains("未配价格类型路由");
  }

  @Test
  @DisplayName("普通料号：有价格类型但 Resolver 取不到价格写 MISSING_PRICE")
  void resolverMissWritesMissingPrice() {
    PricePreparePlanItem planItem = planItem("MAT-MISS", BigDecimal.ONE);
    PriceTypeRoute route = route("MAT-MISS", PriceTypeEnum.FIXED);
    when(routerService.listCandidates(eq("MAT-MISS"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of(route));
    when(fixedResolver.resolve(
            eq("OA-001"), any(CostRunPartItemDto.class), eq(route), any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.miss("固定价表无该料号"));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem);

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(result.getSourceTable()).isEqualTo("PriceResolver");
    assertThat(result.getMessage()).contains("固定价表无该料号");
  }

  @Test
  @DisplayName("MFRP-06：区间价价格准备传递报价上下文并写入命中区间明细 ID")
  void rangePricePreparePassesQuoteContextAndKeepsRangeItemRef() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 12, 14, 20);
    PricePreparePlanItem planItem = planItem("201850160", new BigDecimal("0.655"));
    PriceTypeRoute route = route("201850160", PriceTypeEnum.RANGE);
    when(routerService.listCandidates(eq("201850160"), eq("2026-05"), any(LocalDate.class)))
        .thenReturn(List.of(route));
    when(rangeResolver.resolve(
            eq("OA-001"), any(CostRunPartItemDto.class), eq(route), any(CostRunContext.class)))
        .thenReturn(new PriceResolveResult(
            new BigDecimal("0.3920353982300885"),
            "区间价",
            "行情区间命中(CU=90000,range=87501-92500,field=price_excl_tax)",
            701L));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", priceAsOfTime, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("0.3920353982300885");
    assertThat(result.getAmount()).isEqualByComparingTo("0.2567831858407079675");
    assertThat(result.getResultRefType()).isEqualTo("RANGE_PRICE");
    assertThat(result.getResultRefId()).isEqualTo(701L);
    assertThat(result.getMessage()).contains("CU=90000", "87501-92500");
    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(rangeResolver).resolve(
        eq("OA-001"), any(CostRunPartItemDto.class), eq(route), contextCaptor.capture());
    assertThat(contextCaptor.getValue().getScene()).isEqualTo(CostRunContext.SCENE_QUOTE);
    assertThat(contextCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(contextCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(contextCaptor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(contextCaptor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    assertThat(contextCaptor.getValue().getProductCode()).isEqualTo("TOP-001");
  }

  private PricePreparePlanItem planItem(String materialCode, BigDecimal quantity) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setTopProductCode("TOP-001");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setShapeAttr("采购件");
    row.setQtyPerTop(quantity);
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(row.getTopProductCode());
    item.setMaterialCode(materialCode);
    item.setMaterialName(row.getMaterialName());
    item.setItemType("NORMAL");
    item.setStatus("READY");
    return item;
  }

  private PriceTypeRoute route(String materialCode, PriceTypeEnum priceType) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        priceType,
        1,
        LocalDate.parse("2026-01-01"),
        null,
        "manual",
        priceType.getDbText());
  }
}
