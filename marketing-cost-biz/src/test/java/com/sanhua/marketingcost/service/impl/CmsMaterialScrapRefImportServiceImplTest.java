package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportRequest;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefSourceRow;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsMaterialScrapRefImportServiceImplTest {
  private static final Path MATERIAL_SCRAP_SAMPLE =
      Path.of("/Users/xiexicheng/Desktop/cms/原材料对应回收废料信息-列表导出20260512150059-new.xlsx");

  private MaterialScrapRefMapper materialScrapRefMapper;
  private CmsMaterialScrapRefImportServiceImpl service;

  @BeforeEach
  void setUp() {
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    service =
        new CmsMaterialScrapRefImportServiceImpl(
            materialScrapRefMapper,
            new CmsCostExcelParseServiceImpl());
    when(materialScrapRefMapper.selectOne(any())).thenReturn(null);
  }

  @Test
  @DisplayName("T3 导入财务样例：生成 6 条 current 映射")
  void importProvidedSampleGeneratesSixCurrentMappingsWhenAvailable() throws IOException {
    assumeTrue(Files.exists(MATERIAL_SCRAP_SAMPLE));

    CmsMaterialScrapRefImportResponse response;
    try (InputStream input = Files.newInputStream(MATERIAL_SCRAP_SAMPLE)) {
      response = service.importExcel(input, "COMMERCIAL");
    }

    assertThat(response.getStatus()).isEqualTo("IMPORTED");
    assertThat(response.getSourceRowCount()).isEqualTo(6);
    assertThat(response.getEffectiveRowCount()).isEqualTo(6);
    assertThat(response.getSkippedRowCount()).isZero();
    assertThat(response.getConflictRowCount()).isZero();
    assertThat(response.getUpdatedMappingCount()).isEqualTo(6);

    ArgumentCaptor<MaterialScrapRef> currentCaptor = ArgumentCaptor.forClass(MaterialScrapRef.class);
    verify(materialScrapRefMapper, times(6)).insert(currentCaptor.capture());
    assertThat(currentCaptor.getAllValues())
        .extracting(row -> row.getMaterialCode() + "->" + row.getScrapCode())
        .containsExactly(
            "701010001000->301990218",
            "301050066->301990317",
            "301220046->301990752",
            "301240123->301990444",
            "301050054->301990317",
            "301280056->301990444");
    assertThat(currentCaptor.getAllValues())
        .filteredOn(row -> "301280056".equals(row.getMaterialCode()))
        .singleElement()
        .satisfies(row -> assertThat(row.getSourceType()).isEqualTo("CMS_EXCEL"));
  }

  @Test
  @DisplayName("T3 current：同一原材料多个回收料号不是冲突，全部保留")
  void importSourceRowsKeepsOneMaterialToManyScraps() {
    CmsMaterialScrapRefImportRequest request = request(row(4, "301050066", "301990317", "已完成"));
    request.getSourceRows().add(row(5, "301050066", "301990XXX", "已完成"));

    CmsMaterialScrapRefImportResponse response = service.importSourceRows(request);

    assertThat(response.getEffectiveRowCount()).isEqualTo(2);
    assertThat(response.getConflictRowCount()).isZero();
    assertThat(response.getUpdatedMappingCount()).isEqualTo(2);
    verify(materialScrapRefMapper, times(2)).insert(any(MaterialScrapRef.class));
  }

  @Test
  @DisplayName("T3 current：同一 material+scrap 多候选按状态、版本、时间、行号择优")
  void importSourceRowsChoosesBestCandidateForSameMaterialAndScrap() {
    CmsMaterialScrapRefImportRequest request = request(row(4, " 301 050066", "301990317", ""));
    CmsMaterialScrapRefSourceRow lowerVersion = row(5, "301050066", "301990317", "已完成");
    lowerVersion.setCmsVersion("1");
    lowerVersion.setApprovalTime(LocalDate.of(2025, 9, 12));
    CmsMaterialScrapRefSourceRow higherVersion = row(6, "301050066", "301990317", "已完成");
    higherVersion.setCmsVersion("2");
    higherVersion.setApprovalTime(LocalDate.of(2025, 9, 11));
    request.getSourceRows().add(lowerVersion);
    request.getSourceRows().add(higherVersion);

    CmsMaterialScrapRefImportResponse response = service.importSourceRows(request);

    assertThat(response.getSourceRowCount()).isEqualTo(3);
    assertThat(response.getEffectiveRowCount()).isEqualTo(3);
    assertThat(response.getConflictRowCount()).isEqualTo(2);
    assertThat(response.getUpdatedMappingCount()).isEqualTo(1);
    ArgumentCaptor<MaterialScrapRef> currentCaptor = ArgumentCaptor.forClass(MaterialScrapRef.class);
    verify(materialScrapRefMapper).insert(currentCaptor.capture());
    assertThat(currentCaptor.getValue().getSourceDocNo()).isEqualTo("SEQ-6");
    assertThat(currentCaptor.getValue().getMaterialCode()).isEqualTo("301050066");
  }

  @Test
  @DisplayName("T3 current：失效状态行不生成当前有效映射")
  void importSourceRowsSkipsInvalidRows() {
    CmsMaterialScrapRefImportRequest request = request(row(4, "301240123", "301990444", "已撤回"));

    CmsMaterialScrapRefImportResponse response = service.importSourceRows(request);

    assertThat(response.getSourceRowCount()).isEqualTo(1);
    assertThat(response.getEffectiveRowCount()).isZero();
    assertThat(response.getSkippedRowCount()).isEqualTo(1);
    assertThat(response.getUpdatedMappingCount()).isZero();
    verify(materialScrapRefMapper, times(0)).insert(any(MaterialScrapRef.class));
  }

  private CmsMaterialScrapRefImportRequest request(CmsMaterialScrapRefSourceRow firstRow) {
    CmsMaterialScrapRefImportRequest request = new CmsMaterialScrapRefImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setSourceRows(new java.util.ArrayList<>(List.of(firstRow)));
    return request;
  }

  private CmsMaterialScrapRefSourceRow row(
      int rowNo, String materialCode, String recycleMaterialCode, String sequenceStatus) {
    CmsMaterialScrapRefSourceRow row = new CmsMaterialScrapRefSourceRow();
    row.setRowNo(rowNo);
    row.setSourceType("EXCEL");
    row.setMaterialCode(materialCode);
    row.setNormalizedMaterialCode(com.sanhua.marketingcost.util.CmsFieldNormalizeUtils.normalize(materialCode));
    row.setMaterialName("拉制铜管");
    row.setMaterialSpec("T2 Y2");
    row.setMaterialUnit("千克");
    row.setRecycleMaterialCode(recycleMaterialCode);
    row.setNormalizedRecycleMaterialCode(com.sanhua.marketingcost.util.CmsFieldNormalizeUtils.normalize(recycleMaterialCode));
    row.setRecycleMaterialName("废紫铜沫（干净）");
    row.setRecycleMaterialUnit("千克");
    row.setCmsVersion("1");
    row.setSequenceNo("SEQ-" + rowNo);
    row.setCmsRecordId("CMS-ID-" + rowNo);
    row.setLinkDetailId("LINK-" + rowNo);
    row.setSequenceStatus(sequenceStatus);
    row.setSyncTime(LocalDate.of(2025, 9, 12));
    row.setApprovalTime(LocalDate.of(2025, 9, 12));
    row.setPostingPeriod("2025-09");
    return row;
  }
}
