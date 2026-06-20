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
  private U9MaterialMasterIngestService ingestService;
  private U9MaterialMasterServiceImpl service;

  @BeforeEach
  void setUp() {
    rawMapper = mock(MaterialMasterRawMapper.class);
    ingestService = mock(U9MaterialMasterIngestService.class);
    service = new U9MaterialMasterServiceImpl(rawMapper, ingestService);
  }

  @Test
  @DisplayName("importExcel：页面服务委托统一 ingest 层的 Excel adapter")
  void importExcelDelegatesToIngestService() {
    U9MaterialImportResponse response = new U9MaterialImportResponse();
    response.setSourceType("EXCEL");
    when(ingestService.ingest(any(), any())).thenReturn(response);

    U9MaterialImportResponse result =
        service.importExcel(new ByteArrayInputStream(new byte[] {1}), "u9.xlsx", "admin", "PLATE");

    assertThat(result.getSourceType()).isEqualTo("EXCEL");
    ArgumentCaptor<com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest> captor =
        ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest.class);
    verify(ingestService).ingest(org.mockito.Mockito.eq(U9MaterialMasterSourceType.EXCEL), captor.capture());
    assertThat(captor.getValue().sourceFileName()).isEqualTo("u9.xlsx");
    assertThat(captor.getValue().importedBy()).isEqualTo("admin");
    assertThat(captor.getValue().organizationCode()).isEqualTo("PLATE");
  }

  @Test
  @DisplayName("pageRaw：限定组织当前有效料品并应用页面过滤条件")
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
        "电磁阀", "主要材料", "商用", "制造部", "COMMERCIAL", 0, 500);

    assertThat(result.getCurrent()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(200);
    assertThat(result.getTotal()).isEqualTo(1);

    ArgumentCaptor<Wrapper<MaterialMasterRaw>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(rawMapper).selectPage(any(Page.class), captor.capture());
    String sql = captor.getValue().getCustomSqlSegment();
    assertThat(sql).contains(
        "organization_code", "active_flag", "source_type", "material_code", "material_name");
    assertThat(sql).contains("material_spec", "material_model", "drawing_no", "shape_attr");
    assertThat(sql).contains("main_category_name", "cost_element", "production_division", "department_name");
    assertThat(sql).doesNotContain("SELECT MAX(import_batch_id)");
  }

  @Test
  @DisplayName("options：按组织当前有效 raw 搜索并带出 BOM 编辑字段")
  void optionsSearchesLatestRawAndMapsFields() {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setId(9L);
    row.setMaterialCode("MAT-001");
    row.setMaterialName("紫铜直管");
    row.setMaterialSpec("SPEC-1");
    row.setMaterialModel("MODEL-1");
    row.setUnit("KG");
    row.setGlobalSeg4Material("铜");
    row.setShapeAttr("采购件");
    when(rawMapper.selectOptionsByLatestBatchKeyword(
            "铜管", U9MaterialMasterServiceImpl.SOURCE_TYPE_EXCEL, "PLATE", 50))
        .thenReturn(List.of(row));

    var options = service.options(" 铜管 ", "PLATE", 200);

    assertThat(options).hasSize(1);
    assertThat(options.get(0).getMaterialCode()).isEqualTo("MAT-001");
    assertThat(options.get(0).getMaterialName()).isEqualTo("紫铜直管");
    assertThat(options.get(0).getMaterialSpec()).isEqualTo("SPEC-1");
    assertThat(options.get(0).getMaterialModel()).isEqualTo("MODEL-1");
    assertThat(options.get(0).getChildModel()).isEqualTo("MODEL-1");
    assertThat(options.get(0).getUnit()).isEqualTo("KG");
    assertThat(options.get(0).getMaterialAttribute()).isEqualTo("铜");
    assertThat(options.get(0).getShapeAttribute()).isEqualTo("采购件");
  }

  @Test
  @DisplayName("options：型号为空时 childModel 用规格兜底")
  void optionsUsesSpecAsChildModelFallback() {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode("MAT-002");
    row.setMaterialSpec("SPEC-2");
    when(rawMapper.selectOptionsByLatestBatchKeyword(
            null, U9MaterialMasterServiceImpl.SOURCE_TYPE_EXCEL, "COMMERCIAL", 20))
        .thenReturn(List.of(row));

    var options = service.options(" ", null, 0);

    assertThat(options).hasSize(1);
    assertThat(options.get(0).getChildModel()).isEqualTo("SPEC-2");
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
