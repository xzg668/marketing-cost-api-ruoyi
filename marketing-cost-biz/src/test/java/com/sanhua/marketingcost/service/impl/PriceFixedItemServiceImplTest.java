package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemImportResponse;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PriceFixedItemServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceFixedItem.class);
  }

  @Test
  void purchaseFixedQueryIgnoresMonthCarriedByAnOldClient() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    when(mapper.selectPage(any(), any())).thenAnswer(invocation -> {
      LambdaQueryWrapper<PriceFixedItem> query = invocation.getArgument(1);
      assertThat(query.getSqlSegment()).contains("material_code", "supplier_code", "source_type")
          .doesNotContain("pricing_month");
      assertThat(query.getParamNameValuePairs().values())
          .contains("PUBLIC", "PURCHASE_FIXED", "%MAT-1%", "%SUP-1%")
          .doesNotContain("2026-05");
      return invocation.getArgument(0);
    });

    service(mapper).page("MAT-1", "SUP-1", " PURCHASE_FIXED ", "2026-05", 1, 20);
    verify(mapper).selectPage(any(), any());
  }



  @Test
  void newPurchaseFixedPriceDoesNotFabricateAMonth() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    var request = new PriceFixedItemUpdateRequest();
    request.setSourceType("PURCHASE_FIXED");
    request.setMaterialCode("MAT-1");
    request.setFixedPrice(new BigDecimal("12.3"));
    request.setEffectiveFrom(LocalDate.of(2026, 9, 1));

    PriceFixedItem result = service(mapper).create(request);

    assertEquals("", result.getPricingMonth());
    assertEquals(LocalDate.of(2026, 9, 1), result.getEffectiveFrom());
    verify(mapper).insert(result);
  }

  @Test
  void editingSrmPriceKeepsItsEmptyMonthAndEffectiveDates() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    var existing = new PriceFixedItem();
    existing.setId(13L);
    existing.setSourceKind("PUBLIC");
    existing.setSourceType("PURCHASE_FIXED");
    existing.setSourceSystem("SRM");
    existing.setPricingMonth("");
    existing.setEffectiveFrom(LocalDate.of(2026, 9, 1));
    existing.setEffectiveTo(LocalDate.of(2027, 8, 31));
    when(mapper.selectById(13L)).thenReturn(existing);
    var request = new PriceFixedItemUpdateRequest();
    request.setFixedPrice(new BigDecimal("12.3"));

    PriceFixedItem result = service(mapper).update(13L, request);

    assertEquals("", result.getPricingMonth());
    assertEquals(LocalDate.of(2026, 9, 1), result.getEffectiveFrom());
    assertEquals(LocalDate.of(2027, 8, 31), result.getEffectiveTo());
    verify(mapper).updateById(result);
  }

  @Test
  void publicMaintenanceCannotModifyOrDeleteApprovedSupplementalPrice() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    var service = service(mapper);
    var approved = new PriceFixedItem();
    approved.setId(91L);
    approved.setSourceKind("TECH_SUPPLEMENTAL");
    approved.setFixedPrice(new BigDecimal("12"));
    when(mapper.selectById(91L)).thenReturn(approved);
    var request = new com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest();
    request.setFixedPrice(new BigDecimal("99"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(91L, request))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原补录任务");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(91L))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原补录任务");
    assertEquals(new BigDecimal("12"), approved.getFixedPrice());
    verify(mapper, times(2)).selectById(91L);
    Mockito.verifyNoMoreInteractions(mapper);
  }

  @Test
  void importItems_insertsNewRow() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setMaterialCode("1008000300944");
    row.setFixedPrice(new BigDecimal("12.3"));
    row.setSupplierCode("1004");
    row.setSpecModel("RFG-K04-002784");
    row.setEffectiveFrom(LocalDate.parse("2026-02-01"));

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).insert(captor.capture());
    PriceFixedItem inserted = captor.getValue();
    assertEquals("1008000300944", inserted.getMaterialCode());
    assertEquals(new BigDecimal("12.3"), inserted.getFixedPrice());
    assertEquals(1, result.getCreatedCount());
    assertEquals(0, result.getUpdatedCount());
  }

  @Test
  void importItems_updatesExistingRow() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItem existing = new PriceFixedItem();
    existing.setId(1L);
    existing.setMaterialCode("1008000300944");
    existing.setFixedPrice(new BigDecimal("10"));

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setMaterialCode("1008000300944");
    row.setFixedPrice(new BigDecimal("12.3"));

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).updateById(captor.capture());
    PriceFixedItem updated = captor.getValue();
    assertEquals(new BigDecimal("12.3"), updated.getFixedPrice());
    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getUpdatedCount());
  }





  @Test
  void importItems_purchaseFixedSameExternalRowIdUpdates() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItem existing = new PriceFixedItem();
    existing.setId(10L);
    existing.setMaterialCode("203240246");
    existing.setExternalRowId("OA-1001");
    existing.setFixedPrice(new BigDecimal("0.0100"));

    PriceFixedItemImportRequest.PriceFixedItemImportRow row = purchaseFixedRow("203240246", "OA-1001");
    row.setFixedPrice(new BigDecimal("0.0103"));

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).updateById(captor.capture());
    assertEquals(new BigDecimal("0.0103"), captor.getValue().getFixedPrice());
    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getUpdatedCount());
  }

  @Test
  void importItems_purchaseFixedDifferentExternalRowIdsInsertSeparately() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow first = purchaseFixedRow("203240246", "OA-1001");
    PriceFixedItemImportRequest.PriceFixedItemImportRow second = purchaseFixedRow("203240246", "OA-1002");
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(first, second));

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> saved = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper, times(2)).insert(saved.capture());
    assertThat(saved.getAllValues()).allSatisfy(item -> assertEquals("", item.getPricingMonth()));
    assertEquals(2, result.getCreatedCount());
    assertEquals(0, result.getUpdatedCount());
  }

  @Test
  void importItems_purchaseFixedMissingExternalRowIdSkipped() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow row = purchaseFixedRow("203240246", null);
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    PriceFixedItemImportResponse result = service.importItems(request);

    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getSkippedCount());
    assertEquals(1, result.getErrors().size());
  }

  @Test
  void importItems_u9PurchaseFixedMissingExternalRowIdUpdatesByMaterial() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItem existing = new PriceFixedItem();
    existing.setId(13L);
    existing.setMaterialCode("201290406");
    existing.setSourceType("PURCHASE_FIXED");
    existing.setSourceSystem("U9");
    existing.setFixedPrice(new BigDecimal("2.000000"));

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        purchaseFixedRow("201290406", null);
    row.setSourceSystem(null);
    row.setProcessNo("U9C-应付单列表");
    row.setFixedPrice(new BigDecimal("2.158499"));

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).updateById(captor.capture());
    assertEquals("U9", captor.getValue().getSourceSystem());
    assertEquals(new BigDecimal("2.158499"), captor.getValue().getFixedPrice());
    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getUpdatedCount());
    assertEquals(0, result.getSkippedCount());
  }







  @Test
  void page_returnsPagedRecords() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = service(mapper);

    PriceFixedItem record = new PriceFixedItem();
    record.setId(1L);
    record.setMaterialCode("1008000300944");

    Page<PriceFixedItem> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);

    // V46：page 签名扩成 6 参（加 sourceType / pricingMonth），老测试传 null 不影响行为
    Page<PriceFixedItem> result = service.page(null, null, null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1008000300944", result.getRecords().get(0).getMaterialCode());
  }

  private PriceFixedItemImportRequest.PriceFixedItemImportRow purchaseFixedRow(
      String materialCode, String externalRowId) {
    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setSourceType("PURCHASE_FIXED");
    row.setSourceSystem("SRM");
    row.setExternalRowId(externalRowId);
    row.setMaterialCode(materialCode);
    row.setMaterialName("测试固定采购价");
    row.setFixedPrice(new BigDecimal("1.000000"));
    row.setCurrentTaxExcludedPrice(new BigDecimal("1.000000"));
    return row;
  }



  @Test void legacySettlementCannotBeListedCreatedEditedDeletedOrMixedIntoAnImport() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    var service = service(mapper);
    for (String type : List.of("SETTLE", "SETTLE_FIXED", " settle_linked ")) {
      var create = new PriceFixedItemUpdateRequest();
      create.setSourceType(type); create.setMaterialCode("LEGACY"); create.setFixedPrice(BigDecimal.ONE);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(create)).hasMessageContaining("新结算价格");
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.page(null, null, type, "2026-10", 1, 20)).hasMessageContaining("新结算价格");
      var legacy = new PriceFixedItem(); legacy.setId(1L); legacy.setSourceType(type); legacy.setSourceKind("PUBLIC");
      when(mapper.selectById(1L)).thenReturn(legacy);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update(1L, new PriceFixedItemUpdateRequest())).hasMessageContaining("新结算价格");
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(1L)).hasMessageContaining("新结算价格");
      var bad = settleFixedRow("LEGACY", "EXCEL", BigDecimal.ONE, null); bad.setSourceType(type);
      var request = new PriceFixedItemImportRequest(); request.setRows(List.of(purchaseFixedRow("VALID", "SRM1"), bad));
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importItems(request)).hasMessageContaining("新结算价格");
    }
    verify(mapper, Mockito.never()).insert(any(PriceFixedItem.class));
    verify(mapper, Mockito.never()).updateById(any(PriceFixedItem.class));
    verify(mapper, Mockito.never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
  }

  @Test void ordinaryRowCannotBeConvertedIntoLegacySettlementAndUnfilteredListExcludesLegacy() {
    var mapper = Mockito.mock(PriceFixedItemMapper.class);
    var item = new PriceFixedItem(); item.setSourceType("PURCHASE_FIXED"); item.setSourceKind("PUBLIC");
    when(mapper.selectById(2L)).thenReturn(item);
    var change = new PriceFixedItemUpdateRequest(); change.setSourceType("SETTLE_FIXED");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(mapper).update(2L, change)).hasMessageContaining("新结算价格");
    assertEquals("PURCHASE_FIXED", item.getSourceType());
    when(mapper.selectPage(any(), any())).thenAnswer(call -> {
      LambdaQueryWrapper<PriceFixedItem> query = call.getArgument(1);
      assertThat(query.getSqlSegment()).contains("NOT LIKE 'SETTLE%'"); return call.getArgument(0);
    });
    service(mapper).page(null, null, null, null, 1, 20);
  }

  private PriceFixedItemImportRequest.PriceFixedItemImportRow settleFixedRow(
      String materialCode, String sourceSystem, BigDecimal price, String text) {
    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setSourceType("SETTLE_FIXED");
    row.setSourceSystem(sourceSystem);
    row.setMaterialCode(materialCode);
    row.setMaterialName("测试结算固定价");
    row.setSourceSheetName("家用结算价9");
    row.setSettleReferencePrice(price);
    row.setSettleReferenceText(text);
    return row;
  }

  private PriceFixedItemServiceImpl service(PriceFixedItemMapper mapper) {
    return new PriceFixedItemServiceImpl(
        mapper);
  }
}
