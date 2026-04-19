package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxRateItemImportRequest;
import com.sanhua.marketingcost.entity.AuxRateItem;
import com.sanhua.marketingcost.mapper.AuxRateItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuxRateItemServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        AuxRateItem.class);
  }

  @Test
  void importItems_insertsNewRow() {
    AuxRateItemMapper mapper = Mockito.mock(AuxRateItemMapper.class);
    AuxRateItemServiceImpl service = new AuxRateItemServiceImpl(mapper);

    AuxRateItemImportRequest.AuxRateItemRow row = new AuxRateItemImportRequest.AuxRateItemRow();
    row.setMaterialCode("1001");
    row.setMaterialName("气体");
    row.setFloatRate(new BigDecimal("0.01"));
    row.setPeriod("2025-02");

    AuxRateItemImportRequest request = new AuxRateItemImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    service.importItems(request);

    ArgumentCaptor<AuxRateItem> captor = ArgumentCaptor.forClass(AuxRateItem.class);
    verify(mapper).insert(captor.capture());
    AuxRateItem inserted = captor.getValue();
    assertEquals("1001", inserted.getMaterialCode());
    assertEquals(new BigDecimal("0.01"), inserted.getFloatRate());
  }

  @Test
  void importItems_updatesExistingRow() {
    AuxRateItemMapper mapper = Mockito.mock(AuxRateItemMapper.class);
    AuxRateItemServiceImpl service = new AuxRateItemServiceImpl(mapper);

    AuxRateItemImportRequest.AuxRateItemRow row = new AuxRateItemImportRequest.AuxRateItemRow();
    row.setMaterialCode("1001");
    row.setFloatRate(new BigDecimal("0.02"));
    row.setPeriod("2025-02");

    AuxRateItemImportRequest request = new AuxRateItemImportRequest();
    request.setRows(List.of(row));

    // importItems 现在采用 delete+insert 策略，不再 selectOne+updateById
    when(mapper.delete(any())).thenReturn(1);

    service.importItems(request);

    verify(mapper).delete(any());
    ArgumentCaptor<AuxRateItem> captor = ArgumentCaptor.forClass(AuxRateItem.class);
    verify(mapper).insert(captor.capture());
    AuxRateItem inserted = captor.getValue();
    assertEquals(new BigDecimal("0.02"), inserted.getFloatRate());
    assertEquals("1001", inserted.getMaterialCode());
  }

  @Test
  void page_returnsPagedRecords() {
    AuxRateItemMapper mapper = Mockito.mock(AuxRateItemMapper.class);
    AuxRateItemServiceImpl service = new AuxRateItemServiceImpl(mapper);

    AuxRateItem record = new AuxRateItem();
    record.setId(1L);
    record.setMaterialCode("1001");

    Page<AuxRateItem> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);

    Page<AuxRateItem> result = service.page(null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1001", result.getRecords().get(0).getMaterialCode());
  }
}
