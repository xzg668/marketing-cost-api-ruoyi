package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.AuxSubjectImportRequest;
import com.sanhua.marketingcost.entity.AuxSubject;
import com.sanhua.marketingcost.mapper.AuxSubjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuxSubjectServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        AuxSubject.class);
  }

  @Test
  void importItems_insertsNewRow() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubjectImportRequest.AuxSubjectRow row = new AuxSubjectImportRequest.AuxSubjectRow();
    row.setMaterialCode("1008900031271");
    row.setAuxSubjectCode("1001");
    row.setAuxSubjectName("气体");
    row.setUnitPrice(new BigDecimal("1.00"));
    row.setPeriod("2026-01");

    AuxSubjectImportRequest request = new AuxSubjectImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    service.importItems(request);

    ArgumentCaptor<AuxSubject> captor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(mapper).insert(captor.capture());
    AuxSubject inserted = captor.getValue();
    assertEquals("1008900031271", inserted.getMaterialCode());
    assertEquals("1001", inserted.getAuxSubjectCode());
    assertEquals("气体", inserted.getAuxSubjectName());
  }

  @Test
  void importItems_fillsUnitPriceFromRefMaterial() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubjectImportRequest.AuxSubjectRow row = new AuxSubjectImportRequest.AuxSubjectRow();
    row.setMaterialCode("1008900031271");
    row.setAuxSubjectCode("1001");
    row.setAuxSubjectName("气体");
    row.setRefMaterialCode("SH-001");
    row.setPeriod("2026-01");

    AuxSubjectImportRequest request = new AuxSubjectImportRequest();
    request.setRows(List.of(row));

    var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
    when(mapper.selectOne(any())).thenAnswer(invocation -> {
      if (callCount.getAndIncrement() == 0) {
        return null;
      }
      AuxSubject quoted = new AuxSubject();
      quoted.setUnitPrice(new BigDecimal("9.90"));
      return quoted;
    });

    service.importItems(request);

    ArgumentCaptor<AuxSubject> captor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(mapper).insert(captor.capture());
    AuxSubject inserted = captor.getValue();
    assertEquals(new BigDecimal("9.90"), inserted.getUnitPrice());
  }

  @Test
  void importItems_allowsNullUnitPrice() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubjectImportRequest.AuxSubjectRow row = new AuxSubjectImportRequest.AuxSubjectRow();
    row.setMaterialCode("1008900031271");
    row.setAuxSubjectCode("1002");
    row.setAuxSubjectName("表面处理");
    row.setPeriod("2026-01");

    AuxSubjectImportRequest request = new AuxSubjectImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(null);

    service.importItems(request);

    ArgumentCaptor<AuxSubject> captor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(mapper).insert(captor.capture());
    AuxSubject inserted = captor.getValue();
    assertEquals("1002", inserted.getAuxSubjectCode());
    assertEquals("2026-01", inserted.getPeriod());
  }

  @Test
  void importItems_updatesExistingRow() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubject existing = new AuxSubject();
    existing.setId(1L);
    existing.setMaterialCode("1008900031271");
    existing.setAuxSubjectCode("1001");
    existing.setPeriod("2026-01");

    AuxSubjectImportRequest.AuxSubjectRow row = new AuxSubjectImportRequest.AuxSubjectRow();
    row.setMaterialCode("1008900031271");
    row.setAuxSubjectCode("1001");
    row.setAuxSubjectName("气体");
    row.setUnitPrice(new BigDecimal("1.5"));
    row.setPeriod("2026-01");

    AuxSubjectImportRequest request = new AuxSubjectImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectOne(any())).thenReturn(existing);

    service.importItems(request);

    ArgumentCaptor<AuxSubject> captor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(mapper).updateById(captor.capture());
    AuxSubject updated = captor.getValue();
    assertEquals(new BigDecimal("1.5"), updated.getUnitPrice());
  }

  @Test
  void page_returnsPagedRecords() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubject record = new AuxSubject();
    record.setId(1L);
    record.setMaterialCode("1008900031271");

    Page<AuxSubject> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);

    Page<AuxSubject> result = service.page(null, null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals(1, result.getRecords().size());
    assertEquals("1008900031271", result.getRecords().get(0).getMaterialCode());
  }

  @Test
  void quoteUnitPrice_returnsValue() {
    AuxSubjectMapper mapper = Mockito.mock(AuxSubjectMapper.class);
    AuxSubjectServiceImpl service = new AuxSubjectServiceImpl(mapper);

    AuxSubject record = new AuxSubject();
    record.setUnitPrice(new BigDecimal("2.5"));

    when(mapper.selectOne(any())).thenReturn(record);

    BigDecimal result = service.quoteUnitPrice("1008900031271", "1001", "2026-01");

    assertEquals(new BigDecimal("2.5"), result);
  }
}
