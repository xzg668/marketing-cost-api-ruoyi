package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestService;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("U9MaterialMasterServiceImpl")
class U9MaterialMasterServiceImplTest {
  private MaterialMasterRawMapper rawMapper;
  private MaterialMasterSyncService syncService;
  private U9MaterialMasterIngestService ingestService;
  private U9MaterialMasterServiceImpl service;

  @BeforeEach
  void setUp() {
    rawMapper = mock(MaterialMasterRawMapper.class);
    syncService = mock(MaterialMasterSyncService.class);
    ingestService = mock(U9MaterialMasterIngestService.class);
    service = new U9MaterialMasterServiceImpl(rawMapper, syncService, ingestService);
  }

  @Test
  @DisplayName("importExcel：页面服务委托统一 ingest 层的 Excel adapter")
  void importExcelDelegatesToIngestService() {
    U9MaterialImportResponse response = new U9MaterialImportResponse();
    response.setSourceType("EXCEL");
    when(ingestService.ingest(any(), any())).thenReturn(response);

    U9MaterialImportResponse result =
        service.importExcel(new ByteArrayInputStream(new byte[] {1}), "u9.xlsx", "admin");

    assertThat(result.getSourceType()).isEqualTo("EXCEL");
    ArgumentCaptor<com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest> captor =
        ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest.class);
    verify(ingestService).ingest(org.mockito.Mockito.eq(U9MaterialMasterSourceType.EXCEL), captor.capture());
    assertThat(captor.getValue().sourceFileName()).isEqualTo("u9.xlsx");
    assertThat(captor.getValue().importedBy()).isEqualTo("admin");
  }

  @Test
  @DisplayName("pageRaw：无 batch 时默认限定 EXCEL 最新有效批次并应用页面过滤条件")
  void pageRawUsesLatestActiveBatchAndFilters() {
    when(rawMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MaterialMasterRaw> page = invocation.getArgument(0);
      MaterialMasterRaw row = new MaterialMasterRaw();
      row.setMaterialCode("301050066");
      page.setTotal(1);
      page.setRecords(List.of(row));
      return page;
    });

    Page<MaterialMasterRaw> result = service.pageRaw(
        "301", "阀", "spec", "model", "D1", "采购件",
        "电磁阀", "主要材料", "商用", "制造部", null, 0, 500);

    assertThat(result.getCurrent()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(200);
    assertThat(result.getTotal()).isEqualTo(1);

    ArgumentCaptor<Wrapper<MaterialMasterRaw>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(rawMapper).selectPage(any(Page.class), captor.capture());
    String sql = captor.getValue().getCustomSqlSegment();
    assertThat(sql).contains("active_flag", "source_type", "import_batch_id", "material_code", "material_name");
    assertThat(sql).contains("material_spec", "material_model", "drawing_no", "shape_attr");
    assertThat(sql).contains("main_category_name", "cost_element", "production_division", "department_name");
  }

  @Test
  @DisplayName("pageRaw：指定 batch 时按批次查询，不再追加最新批次子查询")
  void pageRawUsesProvidedBatch() {
    when(rawMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.pageRaw(null, null, null, null, null, null, null, null, null, null, "batch-1", 1, 20);

    ArgumentCaptor<Wrapper<MaterialMasterRaw>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(rawMapper).selectPage(any(Page.class), captor.capture());
    String sql = captor.getValue().getCustomSqlSegment();
    assertThat(sql).contains("import_batch_id");
    assertThat(sql).doesNotContain("SELECT MAX(import_batch_id)");
  }

  @Test
  @DisplayName("templateMapping：返回 U9 20260519 63 列字段契约")
  void templateMappingContainsContract() {
    var mapping = service.templateMapping();

    assertThat(mapping).hasSize(63);
    assertThat(mapping.get(5).field()).isEqualTo("material_code");
    assertThat(mapping.get(5).header()).isEqualTo("物料代码*");
    assertThat(mapping.get(62).field()).isEqualTo("global_seg_3_theoretical_net_weight");
  }
}
