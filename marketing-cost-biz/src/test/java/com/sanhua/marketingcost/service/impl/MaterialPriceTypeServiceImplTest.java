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
import java.time.LocalDate;
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
    row.setMaterialName("阀体部件");
    row.setMaterialModel("MODEL-A");
    row.setMaterialShape("采购件");
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
  void importItems_updatesExistingSamePriceTypeRow() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setRowNo(2);
    row.setBillNo("JG202601010001");
    row.setMaterialCode("1008000300944");
    row.setMaterialName("阀体部件");
    row.setMaterialModel("MODEL-A");
    row.setMaterialShape("采购件");
    row.setPriceType("固定价");
    row.setPeriod("2026-02");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    MaterialPriceType existing = new MaterialPriceType();
    existing.setId(10L);
    existing.setMaterialCode("1008000300944");
    existing.setMaterialName("旧名称");
    existing.setMaterialModel("MODEL-A");
    existing.setPriceType("固定价");
    existing.setPeriod("2026-02");
    existing.setPriority(1);
    existing.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(mapper.selectList(any())).thenReturn(List.of(existing));

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).updateById(captor.capture());
    Mockito.verify(mapper, Mockito.never()).insert(any(MaterialPriceType.class));
    MaterialPriceType updated = captor.getValue();
    assertEquals(10L, updated.getId());
    assertEquals("阀体部件", updated.getMaterialName());
    assertEquals("固定价", updated.getPriceType());
    assertEquals(LocalDate.of(2026, 1, 1), updated.getEffectiveFrom());
  }

  @Test
  void importItems_updatesSameMaterialAndPriceTypeEvenWhenModelDiffers() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("301240123");
    row.setMaterialName("新名称");
    row.setPriceType("联动价");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    MaterialPriceType existing = new MaterialPriceType();
    existing.setId(301240123L);
    existing.setMaterialCode("301240123");
    existing.setMaterialName("旧名称");
    existing.setMaterialModel("OLD-MODEL");
    existing.setPriceType("联动价");
    existing.setPriority(1);
    existing.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(mapper.selectList(any())).thenReturn(List.of(existing));

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).updateById(captor.capture());
    Mockito.verify(mapper, Mockito.never()).insert(any(MaterialPriceType.class));
    MaterialPriceType updated = captor.getValue();
    assertEquals(301240123L, updated.getId());
    assertEquals("新名称", updated.getMaterialName());
    assertEquals("OLD-MODEL", updated.getMaterialModel());
    assertEquals("联动价", updated.getPriceType());
  }

  @Test
  void importItems_expiresOldPriceTypeBeforeNewEffectiveDate() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("301990444");
    row.setMaterialName("废不锈钢沫和丝网");
    row.setPriceType("联动价");
    row.setPeriod("2026-06");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    MaterialPriceType old = new MaterialPriceType();
    old.setId(184L);
    old.setMaterialCode("301990444");
    old.setMaterialName("废不锈钢沫和丝网");
    old.setPriceType("固定价");
    old.setPeriod("2026-06");
    old.setPriority(1);
    old.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(mapper.selectList(any())).thenReturn(List.of(old));

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> updateCaptor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).updateById(updateCaptor.capture());
    assertEquals(LocalDate.of(2026, 5, 31), updateCaptor.getValue().getEffectiveTo());

    ArgumentCaptor<MaterialPriceType> insertCaptor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(insertCaptor.capture());
    assertEquals("联动价", insertCaptor.getValue().getPriceType());
    assertEquals(LocalDate.of(2026, 6, 1), insertCaptor.getValue().getEffectiveFrom());
  }

  @Test
  void importItems_invalidatesWorkbenchConfirmationsWhenPriceTypeChanges() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    QuotePriceTypeConfirmationInvalidationService invalidationService =
        Mockito.mock(QuotePriceTypeConfirmationInvalidationService.class);
    MaterialPriceTypeServiceImpl service =
        new MaterialPriceTypeServiceImpl(mapper, invalidationService);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("721250136");
    row.setPriceType("结算价");
    row.setPeriod("2026-06");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    MaterialPriceType old = new MaterialPriceType();
    old.setId(721250136L);
    old.setMaterialCode("721250136");
    old.setPriceType("固定价");
    old.setPeriod("2026-06");
    old.setPriority(1);
    old.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(mapper.selectList(any())).thenReturn(List.of(old));

    service.importItems(request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaterialPriceType>> captor = ArgumentCaptor.forClass(List.class);
    verify(invalidationService).invalidateByMaterialPriceTypeChanges(captor.capture());
    assertEquals(1, captor.getValue().size());
    assertEquals("721250136", captor.getValue().get(0).getMaterialCode());
    assertEquals("结算固定价", captor.getValue().get(0).getPriceType());
  }

  @Test
  void importItems_doesNotInvalidateWhenOnlyDescriptiveFieldsChange() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    QuotePriceTypeConfirmationInvalidationService invalidationService =
        Mockito.mock(QuotePriceTypeConfirmationInvalidationService.class);
    MaterialPriceTypeServiceImpl service =
        new MaterialPriceTypeServiceImpl(mapper, invalidationService);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("301240123");
    row.setMaterialName("新名称");
    row.setPriceType("联动价");
    row.setPeriod("2026-06");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    MaterialPriceType existing = new MaterialPriceType();
    existing.setId(301240123L);
    existing.setMaterialCode("301240123");
    existing.setMaterialName("旧名称");
    existing.setPriceType("联动价");
    existing.setPeriod("2026-06");
    existing.setPriority(1);
    existing.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(mapper.selectList(any())).thenReturn(List.of(existing));

    service.importItems(request);

    Mockito.verify(invalidationService, Mockito.never()).invalidateByMaterialPriceTypeChanges(any());
  }

  @Test
  void importItems_requiresOnlyMaterialCodeAndPriceType() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("301050057");
    row.setPriceType("联动价");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectList(any())).thenReturn(List.of());

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    MaterialPriceType inserted = captor.getValue();
    assertEquals("301050057", inserted.getMaterialCode());
    assertEquals("联动价", inserted.getPriceType());
  }

  @Test
  void importItems_normalizesPurchaseFixedPriceType() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("202850035");
    row.setPriceType("固定采购价");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectList(any())).thenReturn(List.of());

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertEquals("固定价", captor.getValue().getPriceType());
  }

  @Test
  void importItems_normalizesSettlePriceType() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("721250136");
    row.setPriceType("结算价");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectList(any())).thenReturn(List.of());

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertEquals("结算固定价", captor.getValue().getPriceType());
  }

  @Test
  void importItems_normalizesSettleFixedAlias() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceTypeServiceImpl service = new MaterialPriceTypeServiceImpl(mapper);

    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode("721250136");
    row.setPriceType("结算固定价");

    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));

    when(mapper.selectList(any())).thenReturn(List.of());

    service.importItems(request);

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertEquals("结算固定价", captor.getValue().getPriceType());
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
