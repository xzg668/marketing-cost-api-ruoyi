package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).insert(captor.capture());
    PriceFixedItem inserted = captor.getValue();
    assertEquals("1008000300944", inserted.getMaterialCode());
    assertEquals(new BigDecimal("12.3"), inserted.getFixedPrice());
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

    service.importItems(request);

    ArgumentCaptor<PriceFixedItem> captor = ArgumentCaptor.forClass(PriceFixedItem.class);
    verify(mapper).updateById(captor.capture());
    PriceFixedItem updated = captor.getValue();
    assertEquals(new BigDecimal("12.3"), updated.getFixedPrice());
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

    Page<PriceFixedItem> result = service.page(null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1008000300944", result.getRecords().get(0).getMaterialCode());
  }
}
