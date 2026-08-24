package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LinkedPriceEnsureServiceImplTest {
  private PriceLinkedCalcItemMapper calcItemMapper;
  private PriceLinkedItemMapper linkedItemMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private OaFormMapper oaFormMapper;
  private PriceLinkedCalcServiceImpl calcService;
  private SupplierSupplyRatioResolveService supplyRatioResolveService;
  private LinkedPriceEnsureServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  @BeforeEach
  void setUp() {
    calcItemMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    bomCostingRowMapper = Mockito.mock(BomCostingRowMapper.class);
    oaFormMapper = Mockito.mock(OaFormMapper.class);
    calcService = Mockito.mock(PriceLinkedCalcServiceImpl.class);
    supplyRatioResolveService = Mockito.mock(SupplierSupplyRatioResolveService.class);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未命中供货比例"));
    service = new LinkedPriceEnsureServiceImpl(
        calcItemMapper,
        linkedItemMapper,
        bomCostingRowMapper,
        oaFormMapper,
        calcService,
        new SupplierPreferredPriceSelector(supplyRatioResolveService));
  }

  @Test
  void ensureReturnsZeroWhenItemCodesEmpty() {
    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of()));

    assertThat(result.getRequestedCount()).isZero();
    assertThat(result.getCreatedCount()).isZero();
    assertThat(result.getFailedCount()).isZero();
    verifyNoInteractions(calcItemMapper, linkedItemMapper, bomCostingRowMapper, oaFormMapper,
        calcService);
  }

  @Test
  void ensureSkipsExistingOkResult() {
    PriceLinkedCalcItem existing = new PriceLinkedCalcItem();
    existing.setOaNo("OA-001");
    existing.setItemCode("MAT-1");
    existing.setCalcScene(LinkedPriceCalcScene.QUOTE.getCode());
    existing.setPricingMonth("2026-05");
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setPartUnitPrice(new BigDecimal("12.34"));
    existing.setCalcStatus("OK");
    existing.setSourcePriceRecordId(101L);
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    PriceLinkedItem source = linkedItem("MAT-1");
    source.setId(101L);
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(source));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getSkippedCount()).isEqualTo(1);
    assertThat(result.getCreatedCount()).isZero();
    assertThat(result.getUpdatedCount()).isZero();
    verify(calcService, never()).calculateQuoteItemForEnsure(any(), any(), any());
    verify(calcItemMapper, never()).insert(any(PriceLinkedCalcItem.class));
    verify(calcItemMapper, never()).updateById(any(PriceLinkedCalcItem.class));
  }

  @Test
  void ensureCreatesMissingResult() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem linkedItem = linkedItem("MAT-1");
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    OaForm oaForm = new OaForm();
    oaForm.setOaNo("OA-001");
    when(oaFormMapper.selectOne(any())).thenReturn(oaForm);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setPartAmount(new BigDecimal("25.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    PriceLinkedCalcItem saved = captor.getValue();
    assertThat(saved.getCalcScene()).isEqualTo("QUOTE");
    assertThat(saved.getFactorSource()).isEqualTo("OA_LOCKED");
    assertThat(saved.getPricingMonth()).isEqualTo("2026-05");
    assertThat(saved.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(saved.getBomQty()).isEqualByComparingTo("2.5");
  }

  @Test
  void ensureLocksConcurrentMaterialRowsInCanonicalCodeOrder() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any()))
        .thenReturn(List.of(linkedItem("MAT-B"), linkedItem("MAT-A")));
    when(bomCostingRowMapper.selectList(any()))
        .thenReturn(List.of(bomRow("MAT-B"), bomRow("MAT-A")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(BigDecimal.ONE);
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    service.ensure(
        LinkedPriceEnsureRequest.quote(
            "OA-001",
            "COMMERCIAL",
            "2026-05",
            new LinkedHashSet<>(List.of("MAT-B", "MAT-A"))));

    ArgumentCaptor<PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper, Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(PriceLinkedCalcItem::getItemCode)
        .containsExactly("MAT-A", "MAT-B");
  }

  @Test
  void ensureMarksFormulaFactorCarryForwardAsFinanceWarning() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          calcItem.setTraceJson(
              "{\"variableDetails\":[{\"source\":\"FINANCE_FACTOR_CARRIED_FORWARD\"}]}");
          return calcItem;
        });

    service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    assertThat(captor.getValue().getCarriedForward()).isEqualTo(1);
    assertThat(captor.getValue().getWarningMessage())
        .contains("沿用历史月份", "财务关注");
  }

  @Test
  void calculateReturnsTransientResultWithoutCalcItemWrites() {
    PriceLinkedItem linkedItem = linkedItem("MAT-1");
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setPartAmount(new BigDecimal("25.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    List<PriceLinkedCalcItem> result = service.calculate(
        LinkedPriceEnsureRequest.quote(
            "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result).singleElement().satisfies(item -> {
      assertThat(item.getPartUnitPrice()).isEqualByComparingTo("10.000000");
      assertThat(item.getCalcStatus()).isEqualTo("OK");
      assertThat(item.getId()).isNull();
    });
    verify(calcItemMapper, never()).selectList(any());
    verify(calcItemMapper, never()).insert(any(PriceLinkedCalcItem.class));
    verify(calcItemMapper, never()).updateById(any(PriceLinkedCalcItem.class));
  }

  @Test
  void ensureQuoteOrdersLinkedFormulaByPriceMonthAndImportTime() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("ORDER BY pricing_month DESC", "created_at DESC", "id DESC")
        .doesNotContain("effective_from", "effective_to", "updated_at");
  }

  @Test
  void ensureQuoteUsesLatestPriceMonthInsteadOfQuotaOrHistoricalImportTime() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem currentLowQuota = linkedItem("MAT-1");
    currentLowQuota.setPricingMonth("2026-06");
    currentLowQuota.setSupplierCode("S1");
    currentLowQuota.setQuota(new BigDecimal("0.20"));
    currentLowQuota.setCreatedAt(LocalDateTime.of(2026, 6, 1, 8, 0));
    PriceLinkedItem priorHighQuota = linkedItem("MAT-1");
    priorHighQuota.setPricingMonth("2026-05");
    priorHighQuota.setSupplierCode("S2");
    priorHighQuota.setQuota(new BigDecimal("0.90"));
    // 历史价格月即使更晚补导，也不能压过更大的 pricing_month。
    priorHighQuota.setCreatedAt(LocalDateTime.of(2026, 6, 20, 8, 0));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(currentLowQuota, priorHighQuota));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S1", "供应商1", "0.80"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=")
        .contains("ORDER BY pricing_month DESC,created_at DESC,id DESC")
        .doesNotContain("effective_from", "effective_to", "updated_at")
        .doesNotContain("quota DESC");

    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(
        calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S1");
  }

  @Test
  void ensureQuoteSameMonthPrefersSupplierSupplyRatioMainSupplier() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem highQuota = linkedItem("MAT-1");
    highQuota.setPricingMonth("2026-06");
    highQuota.setSupplierCode("S2");
    highQuota.setSupplierName("供应商2");
    highQuota.setQuota(new BigDecimal("0.70"));
    PriceLinkedItem lowQuota = linkedItem("MAT-1");
    lowQuota.setPricingMonth("2026-06");
    lowQuota.setSupplierCode("S1");
    lowQuota.setSupplierName("供应商1");
    lowQuota.setQuota(new BigDecimal("0.30"));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(highQuota, lowQuota));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S1", "供应商1", "0.80"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("ORDER BY pricing_month DESC,created_at DESC,id DESC")
        .doesNotContain("effective_from", "effective_to", "updated_at");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S1");
  }

  @Test
  void ensureQuoteSingleSupplierPriceDoesNotQuerySupplyRatio() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem onlySupplier = linkedItem("721250208");
    onlySupplier.setPricingMonth("2026-06");
    onlySupplier.setSupplierCode("S000219");
    onlySupplier.setSupplierName("丽水市丽凯制冷配件有限公司");
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(onlySupplier));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("721250208")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("0.636038"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "FI-SC-006-20260108-109", "COMMERCIAL", "2026-08", Set.of("721250208")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S000219");
    org.mockito.Mockito.verifyNoInteractions(supplyRatioResolveService);
  }

  @Test
  void ensureQuoteIgnoresHistoricalMonthSupplierWhenLatestMonthHasOneSupplier() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem latest = linkedItem("301990317");
    latest.setPricingMonth("2026-07");
    latest.setSupplierName("三花股份(江西)自控元器件有限公司");
    latest.setManualPrice(new BigDecimal("76.8584"));
    latest.setCreatedAt(LocalDateTime.of(2026, 7, 13, 17, 50));
    PriceLinkedItem history = linkedItem("301990317");
    history.setPricingMonth("2026-06");
    history.setSupplierName("联动表无自行添加");
    history.setManualPrice(new BigDecimal("78.1327"));
    history.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(latest, history));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("301990317")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          PriceLinkedItem selected = invocation.getArgument(1);
          calcItem.setPartUnitPrice(selected.getManualPrice());
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "FI-SC-006-20260108-109", "COMMERCIAL", "2026-08", Set.of("301990317")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> selected = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), selected.capture(), any());
    assertThat(selected.getValue()).isSameAs(latest);
    assertThat(selected.getValue().getManualPrice()).isEqualByComparingTo("76.8584");
    org.mockito.Mockito.verifyNoInteractions(supplyRatioResolveService);
  }

  @Test
  void ensureQuoteKeepsOtherSupplierAndUsesEachSuppliersLatestImport() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem supplierA = linkedItem("MAT-1");
    supplierA.setId(81L);
    supplierA.setPricingMonth("2026-08");
    supplierA.setSupplierCode("SUP-A");
    supplierA.setSupplierName("供应商A");
    supplierA.setFormulaExpr("A1");
    supplierA.setCreatedAt(LocalDateTime.of(2026, 8, 5, 9, 0));
    PriceLinkedItem supplierBOld = linkedItem("MAT-1");
    supplierBOld.setId(82L);
    supplierBOld.setPricingMonth("2026-08");
    supplierBOld.setSupplierCode("SUP-B");
    supplierBOld.setSupplierName("供应商B");
    supplierBOld.setFormulaExpr("B1");
    supplierBOld.setCreatedAt(LocalDateTime.of(2026, 8, 5, 9, 0));
    PriceLinkedItem supplierBNew = linkedItem("MAT-1");
    supplierBNew.setId(83L);
    supplierBNew.setPricingMonth("2026-08");
    supplierBNew.setSupplierCode("SUP-B");
    supplierBNew.setSupplierName("供应商B");
    supplierBNew.setFormulaExpr("B2");
    supplierBNew.setCreatedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
    when(linkedItemMapper.selectList(any()))
        .thenReturn(List.of(supplierBOld, supplierA, supplierBNew));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("SUP-B", "供应商B", "0.70"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(BigDecimal.TEN);
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-08", Set.of("MAT-1")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> selected = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), selected.capture(), any());
    assertThat(selected.getValue()).isSameAs(supplierBNew);
    assertThat(selected.getValue().getFormulaExpr()).isEqualTo("B2");
  }

  @Test
  void ensureQuoteBlocksWhenMainSupplierHasNoLinkedFormula() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem firstByDefault = linkedItem("MAT-1");
    firstByDefault.setPricingMonth("2026-06");
    firstByDefault.setSupplierCode("S2");
    firstByDefault.setSupplierName("供应商2");
    PriceLinkedItem secondByDefault = linkedItem("MAT-1");
    secondByDefault.setPricingMonth("2026-06");
    secondByDefault.setSupplierCode("S1");
    secondByDefault.setSupplierName("供应商1");
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(firstByDefault, secondByDefault));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S9", "供应商9", "0.90"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getFailedItems().get(0).getReasonCode())
        .isEqualTo(SupplierPreferredPriceSelector.PRIMARY_SUPPLIER_PRICE_MISSING);
    assertThat(result.getFailedItems().get(0).getReason()).contains("主供应商无价格");
    verify(calcService, never()).calculateQuoteItemForEnsure(any(), any(), any());
  }

  @Test
  void ensureQuoteUsesPriorFormulaWhenCurrentMonthMissingAndKeepsQuoteMonth() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem priorFormula = linkedItem("MAT-1");
    priorFormula.setPricingMonth("2026-05");
    priorFormula.setSupplierCode("S2");
    priorFormula.setQuota(new BigDecimal("0.90"));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(priorFormula));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(
        calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-05");
  }

  @Test
  void ensureQuoteIgnoresFormulaEffectiveDatesAndUsesLatestImportForSupplier() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem oldImport = linkedItem("MAT-1");
    oldImport.setPricingMonth("2026-06");
    oldImport.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    oldImport.setEffectiveTo(LocalDate.of(2026, 6, 15));
    oldImport.setSupplierCode("S1");
    oldImport.setFormulaExpr("OLD");
    oldImport.setId(61L);
    oldImport.setCreatedAt(LocalDateTime.of(2026, 6, 5, 8, 0));
    PriceLinkedItem newImport = linkedItem("MAT-1");
    newImport.setPricingMonth("2026-06");
    // 即使人工生效日期晚于取价日，正式表中更晚导入的版本仍然是当前版本。
    newImport.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    newImport.setEffectiveTo(null);
    newImport.setSupplierCode("S1");
    newImport.setFormulaExpr("NEW");
    newImport.setId(62L);
    newImport.setCreatedAt(LocalDateTime.of(2026, 6, 15, 8, 0));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(oldImport, newImport));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });
    LinkedPriceEnsureRequest request = LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1"));
    request.setPriceAsOfTime(LocalDateTime.of(2026, 6, 20, 12, 0));

    service.ensure(request);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .doesNotContain("effective_from", "effective_to", "updated_at");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcService).calculateQuoteItemForEnsure(
        calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPriceAsOfTime())
        .isEqualTo(LocalDateTime.of(2026, 6, 20, 12, 0));
    assertThat(linkedItemCaptor.getValue()).isSameAs(newImport);
    assertThat(linkedItemCaptor.getValue().getFormulaExpr()).isEqualTo("NEW");
    assertThat(calcItemCaptor.getValue().getSourceEffectiveFrom()).isNull();
    assertThat(calcItemCaptor.getValue().getSourceEffectiveTo()).isNull();
    assertThat(calcItemCaptor.getValue().getCarriedForward()).isZero();
  }

  @Test
  void ensureCreatesMonthlyAdjustResultWithoutOaContext() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(calcService.calculateMonthlyAdjustItemForEnsure(any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("11.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.monthlyAdjust(
        null, "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    PriceLinkedCalcItem saved = captor.getValue();
    assertThat(saved.getOaNo()).isNull();
    assertThat(saved.getCalcScene()).isEqualTo("MONTHLY_ADJUST");
    assertThat(saved.getFactorSource()).isEqualTo("MONTHLY_FACTOR");
    assertThat(saved.getAdjustBatchId()).isNull();
    assertThat(saved.getPricingMonth()).isEqualTo("2026-05");
    assertThat(saved.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    verify(bomCostingRowMapper, never()).selectList(any());
    verify(oaFormMapper, never()).selectOne(any());
  }

  @Test
  void ensureMonthlyAdjustDoesNotFilterLinkedFormulaByEffectiveDates() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(calcService.calculateMonthlyAdjustItemForEnsure(any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("11.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });

    var request = LinkedPriceEnsureRequest.monthlyAdjust(
        null,
        "COMMERCIAL",
        "2026-05",
        Set.of("MAT-1"),
        false,
        LocalDateTime.of(2026, 5, 31, 12, 0));
    service.ensure(request);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .doesNotContain("effective_from", "effective_to", "updated_at");
  }

  @Test
  void ensureReportsCalculationFailure() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-FAIL")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setCalcStatus("FAILED");
          calcItem.setCalcMessage("变量 Cu 缺失");
          return calcItem;
        });

    var result = service.ensure(LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-FAIL")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getFailedItems().get(0).getItemCode()).isEqualTo("MAT-FAIL");
    assertThat(result.getFailedItems().get(0).getReason()).isEqualTo("变量 Cu 缺失");
    verify(calcItemMapper).insert(any(PriceLinkedCalcItem.class));
  }

  @Test
  void financeQuoteUsesIndependentFactorSourceAndCuOverride() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(linkedItem("MAT-CU")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-CU")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          PriceLinkedCalcItem calcItem = invocation.getArgument(0);
          calcItem.setPartUnitPrice(new BigDecimal("90.000000"));
          calcItem.setCalcStatus("OK");
          return calcItem;
        });
    LinkedPriceEnsureRequest request = LinkedPriceEnsureRequest.quote(
        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-CU"));
    request.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("90.000000")));

    var result = service.ensure(request);

    assertThat(result.getCreatedCount()).isEqualTo(1);
    ArgumentCaptor<Map<String, BigDecimal>> overrides = ArgumentCaptor.forClass(Map.class);
    verify(calcService).calculateQuoteItemForEnsure(
        any(), any(), any(), overrides.capture(),
        org.mockito.ArgumentMatchers.eq("FINANCE_QUOTE_BASE"));
    assertThat(overrides.getValue()).containsOnlyKeys("Cu");
    ArgumentCaptor<PriceLinkedCalcItem> saved = ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(saved.capture());
    assertThat(saved.getValue().getFactorSource()).isEqualTo("FINANCE_QUOTE_BASE");
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(calcItemMapper).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("factor_source");
    assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
            query.getValue()).getParamNameValuePairs().values())
        .contains("FINANCE_QUOTE_BASE");
  }

  private PriceLinkedItem linkedItem(String materialCode) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(materialCode);
    item.setPricingMonth("2026-05");
    item.setBusinessUnitType("COMMERCIAL");
    item.setFormulaExpr("[Cu]+1");
    return item;
  }

  private SupplierSupplyRatioResolveResult mainSupplier(
      String supplierCode, String supplierName, String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setSupplierCode(supplierCode);
    result.setSupplierName(supplierName);
    result.setSupplyRatio(new BigDecimal(ratio));
    return result;
  }

  private BomCostingRow bomRow(String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialCode(materialCode);
    row.setShapeAttr("采购件");
    row.setQtyPerTop(new BigDecimal("2.5"));
    return row;
  }
}
