package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MaterialPriceTypeServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        MaterialPriceType.class);
  }

  @Test
  void importItems_insertsNewRow() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setRowNo(1);
    row.setBillNo("JG202601010001");
    row.setMaterialCode("1008000300944");
    row.setPriceType("联动价");
    row.setPeriod("2026-02");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    MaterialPriceType inserted = captor.getValue();
    assertEquals("JG202601010001", inserted.getBillNo());
    assertEquals("1008000300944", inserted.getMaterialCode());
    assertEquals("联动价", inserted.getPriceType());
    assertEquals("2026-02", inserted.getPeriod());
  }

  @Test
  void importItems_updatesExistingRow() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setRowNo(2);
    row.setBillNo("JG202601010001");
    row.setMaterialCode("1008000300944");
    row.setMaterialName("阀体部件");
    row.setPriceType("固定价");
    row.setPeriod("2026-02");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    // importItems 现在采用 delete+insert 策略，不再 selectOne+updateById
    when(mapper.delete(any())).thenReturn(1);

    service.importItems(request);

    verify(mapper).delete(any());
    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    MaterialPriceType inserted = captor.getValue();
    assertEquals("阀体部件", inserted.getMaterialName());
    assertEquals("固定价", inserted.getPriceType());
  }

  @Test
  void page_returnsPagedRecords() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceType record = new MaterialPriceType();
    record.setId(1L);
    record.setMaterialCode("1008000300944");

    Page<MaterialPriceType> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);

    Page<MaterialPriceType> result =
        service.page(null, null, null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1008000300944", result.getRecords().get(0).getMaterialCode());
  }
}
