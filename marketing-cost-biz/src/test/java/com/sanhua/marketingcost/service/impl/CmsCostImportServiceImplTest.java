package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CmsCostExcelParseError;
import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsCostExcelParseService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsCostImportServiceImplTest {

  private CmsCostImportBatchMapper batchMapper;
  private CmsPlanCostRawMapper planMapper;
  private CmsWorkshopLaborRawMapper workshopMapper;
  private CmsProductSubjectCostRawMapper subjectMapper;
  private CmsSubjectSettingRawMapper subjectSettingMapper;
  private CmsCostExcelParseService parseService;
  private CmsCostImportServiceImpl service;

  @BeforeEach
  void setUp() {
    batchMapper = mock(CmsCostImportBatchMapper.class);
    planMapper = mock(CmsPlanCostRawMapper.class);
    workshopMapper = mock(CmsWorkshopLaborRawMapper.class);
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    subjectSettingMapper = mock(CmsSubjectSettingRawMapper.class);
    parseService = mock(CmsCostExcelParseService.class);
    service =
        new CmsCostImportServiceImpl(
            batchMapper,
            planMapper,
            workshopMapper,
            subjectMapper,
            subjectSettingMapper,
            parseService);
  }

  @Test
  @DisplayName("T10 importExcel：正常解析后只保存 CMS 原始数据，不执行公共生效来源派生")
  void importExcelPersistsRawRowsOnly() {
    stubParseSuccess();
    doAnswer(invocation -> {
          CmsCostImportBatch batch = invocation.getArgument(0);
          assertThat(batch.getStatus()).isEqualTo("PENDING");
          assertThat(batch.getPlanRowCount()).isZero();
          assertThat(batch.getWorkshopRowCount()).isZero();
          assertThat(batch.getSubjectRowCount()).isZero();
          batch.setId(99L);
          return 1;
        })
        .when(batchMapper)
        .insert(any(CmsCostImportBatch.class));
    CmsCostImportResponse response =
        service.importExcel(
            stream(),
            "plan.xlsx",
            stream(),
            "workshop.xlsx",
            stream(),
            "subject.xlsx",
            stream(),
            "subject-setting.xlsx",
            false,
            "cms-user",
            "COMMERCIAL");

    assertThat(response.getImportBatchId()).isEqualTo(99L);
    assertThat(response.getStatus()).isEqualTo("IMPORTED");
    assertThat(response.getPlanRowCount()).isEqualTo(1);
    assertThat(response.getWorkshopRowCount()).isEqualTo(1);
    assertThat(response.getSubjectRowCount()).isEqualTo(1);
    assertThat(response.getSubjectSettingRowCount()).isEqualTo(1);
    assertThat(response.getSalaryInsertCount()).isZero();
    assertThat(response.getSalarySkipCount()).isZero();
    assertThat(response.getSalaryBlockedCount()).isZero();
    assertThat(response.getAuxInsertCount()).isZero();
    assertThat(response.getAuxSkipCount()).isZero();
    verify(planMapper).upsert(any(CmsPlanCostRaw.class));
    verify(workshopMapper).upsert(any(CmsWorkshopLaborRaw.class));
    verify(subjectMapper).upsert(any(CmsProductSubjectCostRaw.class));
    verify(subjectSettingMapper).upsert(any(CmsSubjectSettingRaw.class));
    verify(batchMapper).updateById(any(CmsCostImportBatch.class));

    ArgumentCaptor<CmsCostImportBatch> updatedBatch =
        ArgumentCaptor.forClass(CmsCostImportBatch.class);
    verify(batchMapper).updateById(updatedBatch.capture());
    assertThat(updatedBatch.getValue().getStatus()).isEqualTo("IMPORTED");
    assertThat(updatedBatch.getValue().getPlanRowCount()).isEqualTo(1);
    assertThat(updatedBatch.getValue().getWorkshopRowCount()).isEqualTo(1);
    assertThat(updatedBatch.getValue().getSubjectRowCount()).isEqualTo(1);
    assertThat(updatedBatch.getValue().getSubjectSettingRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("T10 importExcel：dryRun 只解析并返回行数，不写数据库、不派生")
  void importExcelDryRunDoesNotWriteDatabase() {
    stubParseSuccess();

    CmsCostImportResponse response =
        service.importExcel(
            stream(),
            "plan.xlsx",
            stream(),
            "workshop.xlsx",
            stream(),
            "subject.xlsx",
            stream(),
            "subject-setting.xlsx",
            true,
            "cms-user",
            "COMMERCIAL");

    assertThat(response.getStatus()).isEqualTo("DRY_RUN");
    assertThat(response.getPlanRowCount()).isEqualTo(1);
    assertThat(response.getWorkshopRowCount()).isEqualTo(1);
    assertThat(response.getSubjectRowCount()).isEqualTo(1);
    assertThat(response.getSubjectSettingRowCount()).isEqualTo(1);
    assertThat(response.getImportBatchId()).isNull();
    verify(batchMapper, never()).insert(any(CmsCostImportBatch.class));
    verify(planMapper, never()).upsert(any(CmsPlanCostRaw.class));
    verify(workshopMapper, never()).upsert(any(CmsWorkshopLaborRaw.class));
    verify(subjectMapper, never()).upsert(any(CmsProductSubjectCostRaw.class));
    verify(subjectSettingMapper, never()).upsert(any(CmsSubjectSettingRaw.class));
  }

  @Test
  @DisplayName("T10 importExcel：支持只上传科目设置导出")
  void importExcelSubjectSettingOnly() {
    when(parseService.parseSubjectSetting(any())).thenReturn(subjectSettingResult());
    doAnswer(invocation -> {
          CmsCostImportBatch batch = invocation.getArgument(0);
          batch.setId(101L);
          return 1;
        })
        .when(batchMapper)
        .insert(any(CmsCostImportBatch.class));

    CmsCostImportResponse response =
        service.importExcel(
            null,
            null,
            null,
            null,
            null,
            null,
            stream(),
            "subject-setting.xlsx",
            false,
            "cms-user",
            "COMMERCIAL");

    assertThat(response.getImportBatchId()).isEqualTo(101L);
    assertThat(response.getPlanRowCount()).isZero();
    assertThat(response.getWorkshopRowCount()).isZero();
    assertThat(response.getSubjectRowCount()).isZero();
    assertThat(response.getSubjectSettingRowCount()).isEqualTo(1);
    verify(parseService, never()).parsePlanCost(any());
    verify(parseService, never()).parseWorkshopLabor(any());
    verify(parseService, never()).parseProductSubjectCost(any());
    verify(planMapper, never()).upsert(any(CmsPlanCostRaw.class));
    verify(workshopMapper, never()).upsert(any(CmsWorkshopLaborRaw.class));
    verify(subjectMapper, never()).upsert(any(CmsProductSubjectCostRaw.class));
    verify(subjectSettingMapper).upsert(any(CmsSubjectSettingRaw.class));
  }

  @Test
  @DisplayName("T10 importExcel：原始行按 raw 唯一键执行 mapper upsert")
  void importExcelUpsertsDuplicateRawRows() {
    stubParseSuccess();
    doAnswer(invocation -> {
          CmsCostImportBatch batch = invocation.getArgument(0);
          batch.setId(100L);
          return 1;
        })
        .when(batchMapper)
        .insert(any(CmsCostImportBatch.class));

    service.importExcel(
        stream(),
        "plan.xlsx",
        stream(),
        "workshop.xlsx",
        stream(),
        "subject.xlsx",
        stream(),
        "subject-setting.xlsx",
        false,
        "cms-user",
        "COMMERCIAL");

    verify(planMapper, never()).insert(any(CmsPlanCostRaw.class));
    verify(workshopMapper, never()).insert(any(CmsWorkshopLaborRaw.class));
    verify(subjectMapper, never()).insert(any(CmsProductSubjectCostRaw.class));
    verify(subjectSettingMapper, never()).insert(any(CmsSubjectSettingRaw.class));

    ArgumentCaptor<CmsPlanCostRaw> planUpsert = ArgumentCaptor.forClass(CmsPlanCostRaw.class);
    ArgumentCaptor<CmsWorkshopLaborRaw> workshopUpsert =
        ArgumentCaptor.forClass(CmsWorkshopLaborRaw.class);
    ArgumentCaptor<CmsProductSubjectCostRaw> subjectUpsert =
        ArgumentCaptor.forClass(CmsProductSubjectCostRaw.class);
    verify(planMapper).upsert(planUpsert.capture());
    verify(workshopMapper).upsert(workshopUpsert.capture());
    verify(subjectMapper).upsert(subjectUpsert.capture());
    assertThat(planUpsert.getValue().getParentCode()).isEqualTo("P1");
    assertThat(planUpsert.getValue().getEffectivePeriod()).isEqualTo("2026-01");
    assertThat(workshopUpsert.getValue().getSourceRowId()).isEqualTo("WORKSHOP-ID-1");
    assertThat(subjectUpsert.getValue().getSourceRowId()).isEqualTo("SUBJECT-ID-1");
  }

  @Test
  @DisplayName("T10 importExcel：表头错误抛出明确解析异常")
  void importExcelParseError() {
    CmsCostExcelParseResult<CmsPlanCostExcelRow> plan = new CmsCostExcelParseResult<>();
    plan.getErrors().add(new CmsCostExcelParseError(1, "父件编码", "缺少必要列"));
    when(parseService.parsePlanCost(any())).thenReturn(plan);
    when(parseService.parseWorkshopLabor(any())).thenReturn(workshopResult());
    when(parseService.parseProductSubjectCost(any())).thenReturn(subjectResult());

    assertThatThrownBy(
            () ->
                service.importExcel(
                    stream(),
                    "plan.xlsx",
                    stream(),
                    "workshop.xlsx",
                    stream(),
                    "subject.xlsx",
                    stream(),
                    "subject-setting.xlsx",
                    false,
                    "cms-user",
                    "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CMS Excel 解析失败")
        .hasMessageContaining("父件编码")
        .hasMessageContaining("缺少必要列");
    verify(batchMapper, never()).insert(any(CmsCostImportBatch.class));
  }

  private void stubParseSuccess() {
    when(parseService.parsePlanCost(any())).thenReturn(planResult());
    when(parseService.parseWorkshopLabor(any())).thenReturn(workshopResult());
    when(parseService.parseProductSubjectCost(any())).thenReturn(subjectResult());
    when(parseService.parseSubjectSetting(any())).thenReturn(subjectSettingResult());
  }

  private CmsCostExcelParseResult<CmsPlanCostExcelRow> planResult() {
    CmsCostExcelParseResult<CmsPlanCostExcelRow> result = new CmsCostExcelParseResult<>();
    CmsPlanCostExcelRow row = new CmsPlanCostExcelRow();
    row.setRowNo(2);
    row.setParentCode("P1");
    row.setEffectivePeriod("2026-01");
    row.setUnapprovedItems("辅料");
    result.getRows().add(row);
    return result;
  }

  private CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> workshopResult() {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result = new CmsCostExcelParseResult<>();
    CmsWorkshopLaborExcelRow row = new CmsWorkshopLaborExcelRow();
    row.setRowNo(4);
    row.setPeriod("2026-01");
    row.setParentCode("P1");
    row.setSourceRowId("WORKSHOP-ID-1");
    row.setWorkingCostCent(new BigDecimal("200.000000"));
    row.setWorkingCostYuan(new BigDecimal("2.000000"));
    result.getRows().add(row);
    return result;
  }

  private CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> subjectResult() {
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> result = new CmsCostExcelParseResult<>();
    CmsProductSubjectCostExcelRow row = new CmsProductSubjectCostExcelRow();
    row.setRowNo(4);
    row.setPeriod("2026-01");
    row.setParentCode("P1");
    row.setSourceRowId("SUBJECT-ID-1");
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode("0201");
    row.setSecondSubjectName("辅助焊料类");
    row.setMaterialPrice(new BigDecimal("22.000000"));
    result.getRows().add(row);
    return result;
  }

  private CmsCostExcelParseResult<CmsSubjectSettingExcelRow> subjectSettingResult() {
    CmsCostExcelParseResult<CmsSubjectSettingExcelRow> result = new CmsCostExcelParseResult<>();
    CmsSubjectSettingExcelRow row = new CmsSubjectSettingExcelRow();
    row.setRowNo(4);
    row.setFirstSubjectCode("02");
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode("0201");
    row.setSecondSubjectName("辅助焊料类");
    row.setThirdSubjectCode("020101");
    row.setThirdSubjectName("焊料类");
    result.getRows().add(row);
    return result;
  }

  private ByteArrayInputStream stream() {
    return new ByteArrayInputStream(new byte[] {1, 2, 3});
  }
}
