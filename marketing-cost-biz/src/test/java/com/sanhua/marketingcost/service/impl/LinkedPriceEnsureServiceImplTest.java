package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Set;
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
