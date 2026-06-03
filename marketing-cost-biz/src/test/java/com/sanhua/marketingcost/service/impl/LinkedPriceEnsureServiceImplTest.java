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
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
    service = new LinkedPriceEnsureServiceImpl(
        calcItemMapper, linkedItemMapper, bomCostingRowMapper, oaFormMapper, calcService);
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
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of());
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
  void ensureQuoteDefaultsToCurrentFormulaVersion() {
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
    assertThat(queryCaptor.getValue().getSqlSegment()).contains("effective_to IS NULL");
  }

  @Test
  void ensureQuotePrefersCurrentMonthBeforeHigherQuotaPriorMonth() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem currentLowQuota = linkedItem("MAT-1");
    currentLowQuota.setPricingMonth("2026-06");
    currentLowQuota.setSupplierCode("S1");
    currentLowQuota.setQuota(new BigDecimal("0.20"));
    PriceLinkedItem priorHighQuota = linkedItem("MAT-1");
    priorHighQuota.setPricingMonth("2026-05");
    priorHighQuota.setSupplierCode("S2");
    priorHighQuota.setQuota(new BigDecimal("0.90"));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(currentLowQuota, priorHighQuota));
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

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=")
        .contains("ORDER BY pricing_month DESC,quota DESC");

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
  void ensureQuoteSameMonthOrdersByHighestSupplierQuota() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem highQuota = linkedItem("MAT-1");
    highQuota.setPricingMonth("2026-06");
    highQuota.setSupplierCode("S2");
    highQuota.setQuota(new BigDecimal("0.70"));
    PriceLinkedItem lowQuota = linkedItem("MAT-1");
    lowQuota.setPricingMonth("2026-06");
    lowQuota.setSupplierCode("S1");
    lowQuota.setQuota(new BigDecimal("0.30"));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(highQuota, lowQuota));
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

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("ORDER BY pricing_month DESC,quota DESC");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S2");
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
  void ensureQuoteAsOfDateUsesEffectiveVersionInSameMonth() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem laterVersion = linkedItem("MAT-1");
    laterVersion.setPricingMonth("2026-06");
    laterVersion.setEffectiveFrom(LocalDate.of(2026, 6, 16));
    laterVersion.setEffectiveTo(null);
    laterVersion.setSupplierCode("S2");
    laterVersion.setQuota(new BigDecimal("0.50"));
    PriceLinkedItem earlierVersion = linkedItem("MAT-1");
    earlierVersion.setPricingMonth("2026-06");
    earlierVersion.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    earlierVersion.setEffectiveTo(LocalDate.of(2026, 6, 15));
    earlierVersion.setSupplierCode("S1");
    earlierVersion.setQuota(new BigDecimal("0.50"));
    when(linkedItemMapper.selectList(any())).thenReturn(List.of(earlierVersion, laterVersion));
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
    request.setPriceAsOfTime(LocalDateTime.of(2026, 6, 10, 12, 0));

    service.ensure(request);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectList(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("effective_from <=")
        .contains("effective_to >=");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S1");
    assertThat(linkedItemCaptor.getValue().getEffectiveTo())
        .isEqualTo(LocalDate.of(2026, 6, 15));
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
  void ensureMonthlyAdjustAsOfDateUsesInclusiveEffectiveWindow() {
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
    assertThat(queryCaptor.getValue().getSqlSegment()).contains("effective_to >=");
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

  private PriceLinkedItem linkedItem(String materialCode) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(materialCode);
    item.setPricingMonth("2026-05");
    item.setBusinessUnitType("COMMERCIAL");
    item.setFormulaExpr("[Cu]+1");
    return item;
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
