package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemImportResponse;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PriceFixedItemServiceImplTest {

  @Test
  void importItems_insertsNewRow() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

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
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

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
  void importItems_mapsSettleReferenceNumericPrice() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setMaterialCode("203259840");
    row.setMaterialName("芯铁");
    row.setSourceType("SETTLE_FIXED");
    row.setSourceSystem("EXCEL");
    row.setSourceSheetName("家用结算价9");
    row.setSourceRowNo(97);
    row.setSettleReferenceHeader("铜价（90000元/吨、锌价21680元/吨）");
    row.setSettleReferencePrice(new BigDecimal("0.340314"));

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setImportFileName("3 产品成本计算表（3.29- 提供）5.15改.xls");
    request.setSourceBatchNo("BATCH-001");
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).insert(captor.capture());
    PriceFixedItem inserted = captor.getValue();
    assertEquals(new BigDecimal("0.340314"), inserted.getFixedPrice());
    assertEquals(new BigDecimal("0.340314"), inserted.getSettleReferencePrice());
    assertEquals("家用结算价9", inserted.getSourceSheetName());
    assertEquals("BATCH-001", inserted.getSourceBatchNo());
    assertEquals(1, result.getCreatedCount());
    assertEquals(0, result.getWarnings().size());
  }

  @Test
  void importItems_keepsSettleReferenceTextWithoutFixedPrice() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setMaterialCode("203240001");
    row.setMaterialName("测试料");
    row.setSourceType("SETTLE_FIXED");
    row.setSourceSystem("EXCEL");
    row.setSourceSheetName("家用结算价9");
    row.setSourceRowNo(40);
    row.setSettleReferenceText("不用提供");

    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).insert(captor.capture());
    PriceFixedItem inserted = captor.getValue();
    assertNull(inserted.getFixedPrice());
    assertEquals("不用提供", inserted.getSettleReferenceText());
    assertEquals(1, result.getCreatedCount());
    assertEquals(1, result.getWarnings().size());
  }

  @Test
  void importItems_purchaseFixedSameExternalRowIdUpdates() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

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
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow first = purchaseFixedRow("203240246", "OA-1001");
    PriceFixedItemImportRequest.PriceFixedItemImportRow second = purchaseFixedRow("203240246", "OA-1002");
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(first, second));

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    verify(mapper, times(2)).insert(any(PriceFixedItem.class));
    assertEquals(2, result.getCreatedCount());
    assertEquals(0, result.getUpdatedCount());
  }

  @Test
  void importItems_purchaseFixedMissingExternalRowIdSkipped() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItemImportRequest.PriceFixedItemImportRow row = purchaseFixedRow("203240246", null);
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    PriceFixedItemImportResponse result = service.importItems(request);

    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getSkippedCount());
    assertEquals(1, result.getErrors().size());
  }

  @Test
  void importItems_u9SettleFixedSameMaterialUpdates() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItem existing = new PriceFixedItem();
    existing.setId(11L);
    existing.setMaterialCode("301220046");
    existing.setSourceType("SETTLE_FIXED");
    existing.setSourceSystem("U9");

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        settleFixedRow("301220046", "U9", new BigDecimal("27.1858"), null);
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    PriceFixedItemImportResponse result = service.importItems(request);

    verify(mapper).updateById(any(PriceFixedItem.class));
    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getUpdatedCount());
  }

  @Test
  void importItems_excelSettleFixedSameMaterialUpdates() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    PriceFixedItem existing = new PriceFixedItem();
    existing.setId(12L);
    existing.setMaterialCode("203259840");
    existing.setSourceType("SETTLE_FIXED");
    existing.setSourceSystem("EXCEL");

    PriceFixedItemImportRequest.PriceFixedItemImportRow row =
        settleFixedRow("203259840", "EXCEL", new BigDecimal("0.340314"), null);
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    PriceFixedItemImportResponse result = service.importItems(request);

    verify(mapper).updateById(any(PriceFixedItem.class));
    assertEquals(0, result.getCreatedCount());
    assertEquals(1, result.getUpdatedCount());
  }

  @Test
  void importItems_householdSettleKeeps95RowsWith57PricedAnd38RemarkRows() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

    List<PriceFixedItemImportRequest.PriceFixedItemImportRow> rows = new ArrayList<>();
    for (int i = 0; i < 95; i++) {
      String materialCode = "20" + String.format("%07d", i);
      if (i < 57) {
        rows.add(settleFixedRow(materialCode, "EXCEL", new BigDecimal("1.000000"), null));
      } else {
        rows.add(settleFixedRow(materialCode, "EXCEL", null, "不用提供"));
      }
    }
    PriceFixedItemImportRequest request = new PriceFixedItemImportRequest();
    request.setRows(rows);

    when(mapper.selectOne(any())).thenReturn(null);

    PriceFixedItemImportResponse result = service.importItems(request);

    verify(mapper, times(95)).insert(any(PriceFixedItem.class));
    assertEquals(95, result.getCreatedCount());
    assertEquals(38, result.getWarnings().size());
    assertEquals(57, result.getItems().stream().filter(item -> item.getFixedPrice() != null).count());
    assertEquals(38, result.getItems().stream().filter(item -> item.getFixedPrice() == null).count());
  }

  @Test
  void page_returnsPagedRecords() {
    PriceFixedItemMapper mapper = Mockito.mock(PriceFixedItemMapper.class);
    PriceFixedItemServiceImpl service = new PriceFixedItemServiceImpl(mapper);

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
}
