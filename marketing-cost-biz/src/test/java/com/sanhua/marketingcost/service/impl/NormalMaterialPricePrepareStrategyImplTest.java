package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
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
    when(routerService.listCandidatesBatch(any(), any(), any(), any(), any())).thenAnswer(call -> {
      var result = new java.util.LinkedHashMap<String, List<PriceTypeRoute>>();
      for (String code : (java.util.Collection<String>) call.getArgument(0))
        result.put(code, routerService.listCandidates(code, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
      return result;
    });
    when(routerService.prepareCandidatesBatch(any(), any(), any(), any(), any())).thenAnswer(call -> {
      var result = new java.util.LinkedHashMap<String, List<PriceTypeRoute>>();
      ((java.util.Map<String, String>) call.getArgument(0)).forEach((code, title) ->
        result.put(code, routerService.prepareCandidates(code, title, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4))));
      return result;
    });

    linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    fixedResolver = mock(PriceResolver.class);
    linkedResolver = mock(PriceResolver.class);
    rangeResolver = mock(PriceResolver.class);
    when(fixedResolver.priceType()).thenReturn(PriceTypeEnum.FIXED);
    when(linkedResolver.priceType()).thenReturn(PriceTypeEnum.LINKED);
    when(rangeResolver.priceType()).thenReturn(PriceTypeEnum.RANGE);
    strategy =
        new NormalMaterialPricePrepareStrategyImpl(
            routerService,
            linkedPriceEnsureService,
            List.of(fixedResolver, linkedResolver, rangeResolver));
  }

  @Test
  @DisplayName("普通料号：固定价成功写单价、金额和来源")
  void fixedPriceSuccess() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 10, 9, 30);
    PricePreparePlanItem planItem = planItem("MAT-FIX", new BigDecimal("2.5"));
    PriceTypeRoute route = route("MAT-FIX", PriceTypeEnum.FIXED);
    when(routerService.prepareCandidates(
            eq("MAT-FIX"), org.mockito.Mockito.any(), eq("2026-05"), eq(priceAsOfTime.toLocalDate()), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
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
    ArgumentCaptor<CostRunPartItemDto> itemCaptor =
        ArgumentCaptor.forClass(CostRunPartItemDto.class);
    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(fixedResolver)
        .resolve(eq("OA-001"), itemCaptor.capture(), eq(route), contextCaptor.capture());
    assertThat(itemCaptor.getValue().getPriceOrgCode()).isEqualTo("210");
    assertThat(itemCaptor.getValue().getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(contextCaptor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    assertThat(contextCaptor.getValue().getPriceOrgCode()).isEqualTo("210");
    assertThat(contextCaptor.getValue().getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    verifyNoInteractions(linkedPriceEnsureService);
  }

  @Test
  @DisplayName("普通料号：联动价先 ensure 再读取 Resolver 结果")
  void linkedPriceEnsuresBeforeResolve() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 11, 10, 15);
    PricePreparePlanItem planItem = planItem("MAT-LINK", new BigDecimal("3"));
    PriceTypeRoute route = route("MAT-LINK", PriceTypeEnum.LINKED);
    when(routerService.prepareCandidates(eq("MAT-LINK"), org.mockito.Mockito.any(), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
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
  void invalidLinkedRuleBlocksRangeInsteadOfMaskingTheFailureWithAnotherPrice() {
    when(routerService.prepareCandidates(eq("MAT-LINK"), org.mockito.Mockito.any(), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(
            List.of(
                route("MAT-LINK", PriceTypeEnum.LINKED), route("MAT-LINK", PriceTypeEnum.RANGE)));
    var failed = new LinkedPriceEnsureResult();
    failed.addFailedItem("MAT-LINK", "LINKED_RULE_ERROR", "因素 Zn 缺失");
    when(linkedPriceEnsureService.ensure(any())).thenReturn(failed);
    var result =
        strategy.prepare(
            "OA-001",
            "COMMERCIAL",
            "2026-05",
            LocalDateTime.of(2026, 5, 10, 9, 0),
            planItem("MAT-LINK", BigDecimal.ONE));
    assertThat(result.getUnitPrice()).isNull();
    assertThat(result.getReasonCode()).isEqualTo("LINKED_RULE_ERROR");
    assertThat(result.getPriceType()).isEqualTo("LINKED");
    assertThat(result.getMessage()).contains("因素 Zn 缺失");
    verify(linkedResolver, never()).resolve(any(), any(), any(), any());
    verify(rangeResolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("公共联动公式缺失时，仍使用已提交的技术补录公式")
  void submittedTechnicalFormulaSurvivesMissingPublicFormula() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 9, 30, 9, 0);
    PricePreparePlanItem planItem = planItem("MAT-TECH-LINK", new BigDecimal("3"));
    PriceTypeRoute publicRoute = route("MAT-TECH-LINK", PriceTypeEnum.LINKED);
    PriceTypeRoute technicalRoute =
        new PriceTypeRoute(
            "MAT-TECH-LINK",
            MaterialFormAttrEnum.PURCHASED,
            PriceTypeEnum.LINKED,
            2,
            null,
            null,
            "TECH_SUPPLEMENTAL",
            "联动价",
            "TECH_SUPPLEMENTAL",
            null);
    when(routerService.prepareCandidates(eq("MAT-TECH-LINK"), org.mockito.Mockito.any(), eq("2026-09"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(publicRoute, technicalRoute));
    LinkedPriceEnsureResult failed = new LinkedPriceEnsureResult();
    failed.addFailedItem("MAT-TECH-LINK", "联动价公式不存在或为空");
    when(linkedPriceEnsureService.ensure(any(LinkedPriceEnsureRequest.class))).thenReturn(failed);
    when(linkedResolver.resolve(
            eq("OA-001"),
            any(CostRunPartItemDto.class),
            eq(technicalRoute),
            any(CostRunContext.class)))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("15"), "补录联动价"));

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-09", priceAsOfTime, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("15");
    verify(linkedResolver, never()).resolve(eq("OA-001"), any(), eq(publicRoute), any());
  }

  @Test
  @DisplayName("第四步联动价只在内存计算，不调用 ensure 或数据库 Resolver")
  void linkedPriceCalculateDoesNotPersist() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 11, 10, 15);
    PricePreparePlanItem planItem = planItem("MAT-LINK", new BigDecimal("3"));
    PriceTypeRoute route = route("MAT-LINK", PriceTypeEnum.LINKED);
    when(routerService.listCandidates(eq("MAT-LINK"), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route));
    PriceLinkedCalcItem calculated = new PriceLinkedCalcItem();
    calculated.setItemCode("MAT-LINK");
    calculated.setPartUnitPrice(new BigDecimal("7.00"));
    calculated.setCalcStatus("OK");
    when(linkedPriceEnsureService.calculate(any(LinkedPriceEnsureRequest.class)))
        .thenReturn(List.of(calculated));

    NormalMaterialPricePrepareResult result =
        strategy.calculate("OA-001", "COMMERCIAL", "2026-05", priceAsOfTime, null, planItem);

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("7.00");
    assertThat(result.getAmount()).isEqualByComparingTo("21.00");
    verify(linkedPriceEnsureService, never()).ensure(any());
    verify(linkedResolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("普通料号：实际准备无适用来源时如实记录缺价")
  void missingPublicSourcesStillCallActualPreparationAndReportMissingPrice() {
    PricePreparePlanItem planItem = planItem("MAT-NO-ROUTE", BigDecimal.ONE);
    when(routerService.prepareCandidates(eq("MAT-NO-ROUTE"), org.mockito.Mockito.any(), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of());

    NormalMaterialPricePrepareResult result =
        strategy.prepare("OA-001", "COMMERCIAL", "2026-05", planItem);

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(result.getSourceTable()).isEqualTo("PriceResolver");
    assertThat(result.getMessage()).contains("该月份和组织没有可用价格来源");
  }

  @Test
  @DisplayName("普通料号：有价格类型但 Resolver 取不到价格写 MISSING_PRICE")
  void resolverMissWritesMissingPrice() {
    PricePreparePlanItem planItem = planItem("MAT-MISS", BigDecimal.ONE);
    PriceTypeRoute route = route("MAT-MISS", PriceTypeEnum.FIXED);
    when(routerService.prepareCandidates(eq("MAT-MISS"), org.mockito.Mockito.any(), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
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
    when(routerService.prepareCandidates(eq("201850160"), org.mockito.Mockito.any(), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route));
    when(rangeResolver.resolve(
            eq("OA-001"), any(CostRunPartItemDto.class), eq(route), any(CostRunContext.class)))
        .thenReturn(
            new PriceResolveResult(
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
    verify(rangeResolver)
        .resolve(eq("OA-001"), any(CostRunPartItemDto.class), eq(route), contextCaptor.capture());
    assertThat(contextCaptor.getValue().getScene()).isEqualTo(CostRunContext.SCENE_QUOTE);
    assertThat(contextCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(contextCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(contextCaptor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(contextCaptor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    assertThat(contextCaptor.getValue().getProductCode()).isEqualTo("TOP-001");
  }

  @Test
  void batchPreparesDistinctMaterialsOnceAndKeepsOrganizationQuantityAndSnapshots() {
    var a = planItem("A", new BigDecimal("2"));
    var aAgain = planItem("A", new BigDecimal("3"));
    var b = planItem("B", BigDecimal.ONE);
    var otherOrg = planItem("A", BigDecimal.ONE);
    otherOrg.getBomRow().setPriceOrgCode("220");
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenAnswer(call -> List.of(route(call.getArgument(0), PriceTypeEnum.LINKED)));
    when(linkedPriceEnsureService.ensure(any()))
        .thenAnswer(
            call -> {
              LinkedPriceEnsureRequest request = call.getArgument(0);
              var result = new LinkedPriceEnsureResult();
              for (String code : request.getItemCodes()) {
                var row = new PriceLinkedCalcItem();
                row.setItemCode(code);
                row.setCalcStatus("OK");
                row.setPartUnitPrice(
                    new BigDecimal("220".equals(request.getPriceOrgCode()) ? "30" : "10"));
                row.setInputSnapshotJson("{\"actualSnapshot\":true}");
                result.getItems().put(code, row);
              }
              return result;
            });
    var results =
        strategy.prepareBatch(
            "OA-001",
            "COMMERCIAL",
            "2026-05",
            LocalDateTime.of(2026, 5, 11, 10, 15),
            com.sanhua.marketingcost.service.PricePrepareScenarioContext.oaLocked(1L, "210"),
            List.of(a, aAgain, b, otherOrg),
            true);
    assertThat(results.get(a).getAmount()).isEqualByComparingTo("20");
    assertThat(results.get(aAgain).getAmount()).isEqualByComparingTo("30");
    assertThat(results.get(otherOrg).getUnitPrice()).isEqualByComparingTo("30");
    assertThat(results.get(a).getInputSnapshotJson()).isEqualTo("{\"actualSnapshot\":true}");
    ArgumentCaptor<LinkedPriceEnsureRequest> requests =
        ArgumentCaptor.forClass(LinkedPriceEnsureRequest.class);
    verify(linkedPriceEnsureService, org.mockito.Mockito.times(2)).ensure(requests.capture());
    assertThat(requests.getAllValues().getFirst().getItemCodes()).containsExactly("A", "B");
    assertThat(requests.getAllValues()).allSatisfy(request -> {
      assertThat(request.getSupplierPriceReviewContext().oaFormItemId()).isEqualTo(1L);
      assertThat(request.getSupplierPriceReviewContext().priceOrgCode()).isEqualTo(request.getPriceOrgCode());
    });
    verify(routerService, org.mockito.Mockito.times(3)).prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any());
    verify(linkedResolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  void batchKeepsOtherMaterialReadyWhenOneResolverFails() {
    var bad = planItem("BAD", BigDecimal.ONE);
    var good = planItem("GOOD", new BigDecimal("2"));
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenAnswer(call -> List.of(route(call.getArgument(0), PriceTypeEnum.FIXED)));
    when(fixedResolver.resolve(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              CostRunPartItemDto item = call.getArgument(1);
              if ("BAD".equals(item.getPartCode())) throw new IllegalArgumentException("固定价数据错误");
              return PriceResolveResult.hit(new BigDecimal("12"), "固定价");
            });
    var results =
        strategy.prepareBatch(
            "OA-001", "COMMERCIAL", "2026-05", null, null, List.of(bad, good), true);
    assertThat(results.get(bad).getStatus()).isEqualTo("FAILED");
    assertThat(results.get(bad).getMessage()).contains("固定价数据错误");
    assertThat(results.get(good).getAmount()).isEqualByComparingTo("24");
  }

  @Test
  void batchRethrowsConcurrentWriteForTaskRetry() {
    var item = planItem("MAT-LINK", BigDecimal.ONE);
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("MAT-LINK", PriceTypeEnum.LINKED)));
    when(linkedPriceEnsureService.ensure(any()))
        .thenThrow(new org.springframework.dao.ConcurrencyFailureException("输入已变化"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                strategy.prepareBatch(
                    "OA-001", "COMMERCIAL", "2026-05", null, null, List.of(item), true))
        .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);
  }

  @Test
  void financeFirstFixedPriceSkipsLowerLinkedCalculationForSingleBatchAndPreview() {
    var item = planItem("M1", BigDecimal.ONE);
    var candidates = List.of(route("M1", PriceTypeEnum.FIXED), route("M1", PriceTypeEnum.LINKED));
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any())).thenReturn(candidates);
    when(routerService.listCandidates(any(), any(), any(), any(), any())).thenReturn(candidates);
    when(fixedResolver.resolve(any(), any(), any(), any()))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("12"), "固定价"));
    when(linkedPriceEnsureService.ensure(any()))
        .thenThrow(new org.springframework.dao.ConcurrencyFailureException("低优先级联动价错误"));
    assertThat(strategy.prepare("OA-001", "COMMERCIAL", "2026-05", null, item).getUnitPrice())
        .isEqualByComparingTo("12");
    assertThat(strategy.prepareBatch("OA-001", "COMMERCIAL", "2026-05", null, null, List.of(item), true).get(item).getUnitPrice())
        .isEqualByComparingTo("12");
    assertThat(strategy.calculate("OA-001", "COMMERCIAL", "2026-05", null, null, item).getUnitPrice())
        .isEqualByComparingTo("12");
    verifyNoInteractions(linkedPriceEnsureService);
  }

  @Test
  void absentFirstFixedPriceFallsThroughToLinkedEvenInBatch() {
    var item = planItem("M1", BigDecimal.ONE);
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any()))
        .thenReturn(List.of(route("M1", PriceTypeEnum.FIXED), route("M1", PriceTypeEnum.LINKED)));
    when(fixedResolver.resolve(any(), any(), any(), any())).thenReturn(PriceResolveResult.miss("固定价无有效记录"));
    when(linkedPriceEnsureService.ensure(any())).thenReturn(new LinkedPriceEnsureResult());
    when(linkedResolver.resolve(any(), any(), any(), any())).thenReturn(PriceResolveResult.hit(new BigDecimal("7"), "联动价"));
    var result = strategy.prepareBatch("OA-001", "COMMERCIAL", "2026-05", null, null, List.of(item), true).get(item);
    assertThat(result.getUnitPrice()).isEqualByComparingTo("7");
    verify(linkedPriceEnsureService).ensure(any());
  }

  @Test
  void unmatchedRangeBandFallsThroughWhileInvalidRangeDataStops() {
    var item = planItem("M1", BigDecimal.ONE);
    when(routerService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any()))
        .thenReturn(List.of(route("M1", PriceTypeEnum.RANGE), route("M1", PriceTypeEnum.FIXED)));
    when(rangeResolver.resolve(any(), any(), any(), any()))
        .thenReturn(PriceResolveResult.miss("RANGE_BAND_MISSING", "无适用区间"))
        .thenReturn(PriceResolveResult.miss("FACTOR_INPUT_MISSING", "财务因素缺失"));
    when(fixedResolver.resolve(any(), any(), any(), any())).thenReturn(PriceResolveResult.hit(BigDecimal.TEN, "固定价"));
    assertThat(strategy.prepare("OA-001", "COMMERCIAL", "2026-05", null, item).getUnitPrice()).isEqualByComparingTo("10");
    assertThat(strategy.prepare("OA-001", "COMMERCIAL", "2026-05", null, item).getReasonCode()).isEqualTo("FACTOR_INPUT_MISSING");
    verify(fixedResolver, org.mockito.Mockito.times(1)).resolve(any(), any(), any(), any());
  }

  private PricePreparePlanItem planItem(String materialCode, BigDecimal quantity) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setTopProductCode("TOP-001");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setPriceOrgCode("210");
    row.setMaterialOrganizationCode("COMMERCIAL");
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
