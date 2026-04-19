package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManualItemImportRequest;
import com.sanhua.marketingcost.entity.BomManualItem;
import com.sanhua.marketingcost.mapper.BomManualItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BomManualItemServiceImplTest {

  @Test
  void importItems_insertsNewRow() {
    BomManualItemMapper mapper = Mockito.mock(BomManualItemMapper.class);
    BomManualItemServiceImpl service = new BomManualItemServiceImpl(mapper);

    BomManualItemImportRequest.BomManualItemRow row =
        new BomManualItemImportRequest.BomManualItemRow();
    row.setBomCode("BOM0001");
    row.setItemCode("1008900031271");
    row.setBomLevel(1);
    row.setParentCode("");
    row.setItemName("热力膨胀阀");
    row.setBomQty(new BigDecimal("1.5"));
    row.setMaterial("SUS301");

    BomManualItemImportRequest request = new BomManualItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    service.importItems(request);

    ArgumentCaptor<BomManualItem> captor = ArgumentCaptor.forClass(BomManualItem.class);
    verify(mapper).insert(captor.capture());
    BomManualItem inserted = captor.getValue();
    assertEquals("BOM0001", inserted.getBomCode());
    assertEquals("1008900031271", inserted.getItemCode());
    assertEquals(1, inserted.getBomLevel());
    assertNull(inserted.getParentCode());
    assertEquals("制造件", inserted.getShapeAttr());
    assertEquals(new BigDecimal("1.5"), inserted.getBomQty());
    assertEquals("SUS301", inserted.getMaterial());
    assertEquals("import", inserted.getSource());
  }

  @Test
  void importItems_updatesExistingRow() {
    BomManualItemMapper mapper = Mockito.mock(BomManualItemMapper.class);
    BomManualItemServiceImpl service = new BomManualItemServiceImpl(mapper);

    BomManualItem existing = new BomManualItem();
    existing.setId(1L);
    existing.setBomCode("BOM0001");
    existing.setItemCode("102053856");
    existing.setBomLevel(2);
    existing.setParentCode("1008900031271");
    existing.setShapeAttr("制造件");
    existing.setSource("import");

    BomManualItemImportRequest.BomManualItemRow row =
        new BomManualItemImportRequest.BomManualItemRow();
    row.setBomCode("BOM0001");
    row.setItemCode("102053856");
    row.setBomLevel(2);
    row.setParentCode("1008900031271");
    row.setItemName("热力膨胀阀");
    row.setShapeAttr("制造件");
    row.setBomQty(new BigDecimal("2"));
    row.setMaterial("SUS302");

    BomManualItemImportRequest request = new BomManualItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    service.importItems(request);

    ArgumentCaptor<BomManualItem> captor = ArgumentCaptor.forClass(BomManualItem.class);
    verify(mapper).updateById(captor.capture());
    BomManualItem updated = captor.getValue();
    assertEquals("BOM0001", updated.getBomCode());
    assertEquals("102053856", updated.getItemCode());
    assertEquals(2, updated.getBomLevel());
    assertEquals("1008900031271", updated.getParentCode());
    assertEquals("热力膨胀阀", updated.getItemName());
    assertEquals(new BigDecimal("2"), updated.getBomQty());
    assertEquals("SUS302", updated.getMaterial());
  }

  @Test
  void page_returnsPagedRecords() {
    BomManualItemMapper mapper = Mockito.mock(BomManualItemMapper.class);
    BomManualItemServiceImpl service = new BomManualItemServiceImpl(mapper);

    BomManualItem record = new BomManualItem();
    record.setId(1L);
    record.setItemCode("1008900031271");
    record.setBomLevel(1);

    Page<BomManualItem> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);

    Page<BomManualItem> result = service.page(null, null, null, null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1008900031271", result.getRecords().get(0).getItemCode());
  }
}
