package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorAdjustBatchPageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustImportResponse;
import com.sanhua.marketingcost.dto.FactorAdjustPricePageResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListPageResponse;
import com.sanhua.marketingcost.service.FactorAdjustImportService;
import com.sanhua.marketingcost.service.FactorAdjustQueryService;
import com.sanhua.marketingcost.service.FactorAdjustTemplateExportService;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class FactorAdjustControllerTest {

  private FactorAdjustTemplateExportService templateExportService;
  private FactorAdjustQueryService queryService;
  private FactorAdjustImportService importService;
  private FactorAdjustController controller;

  @BeforeEach
  void setUp() {
    templateExportService = mock(FactorAdjustTemplateExportService.class);
    queryService = mock(FactorAdjustQueryService.class);
    importService = mock(FactorAdjustImportService.class);
    controller = new FactorAdjustController(templateExportService, queryService, importService);
  }

  @Test
  @DisplayName("/factor-adjust/import：透传文件、用途和上传人")
  void importAdjustExcelDelegatesToImportService() {
    FactorAdjustImportResponse mocked = new FactorAdjustImportResponse();
    mocked.setAdjustBatchNo("FAB202605160001");
    when(importService.importAdjustExcel(
        org.mockito.ArgumentMatchers.any(InputStream.class),
        eq("adjust.xlsx"),
        org.mockito.ArgumentMatchers.any(),
        eq("alice")))
        .thenReturn(mocked);
    MockMultipartFile file = new MockMultipartFile(
        "file", "adjust.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
    org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice", "n/a");

    CommonResult<FactorAdjustImportResponse> result =
        controller.importAdjustExcel(file, "2026-05", "COMMERCIAL",
            "REPRICE_AND_DAILY", "5月调价", auth);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    org.mockito.ArgumentCaptor<com.sanhua.marketingcost.dto.FactorAdjustImportRequest> captor =
        org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.FactorAdjustImportRequest.class);
    verify(importService).importAdjustExcel(
        org.mockito.ArgumentMatchers.any(InputStream.class),
        eq("adjust.xlsx"),
        captor.capture(),
        eq("alice"));
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getUsageScope()).isEqualTo("REPRICE_AND_DAILY");
    assertThat(captor.getValue().getRemark()).isEqualTo("5月调价");
  }

  @Test
  @DisplayName("/factor-adjust/export-template：输出 xlsx 二进制和下载文件头")
  void exportTemplateWritesXlsxResponse() throws Exception {
    byte[] bytes = new byte[] {1, 2, 3};
    when(templateExportService.exportTemplate(
        eq("2026-05"), eq("COMMERCIAL"), eq("锰"), eq(9001L)))
        .thenReturn(bytes);
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.exportTemplate("2026-05", "COMMERCIAL", "锰", 9001L, response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType())
        .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition"))
        .contains("attachment; filename*=UTF-8''factor-adjust-template-2026-05.xlsx");
    assertThat(response.getContentAsByteArray()).containsExactly(bytes);
    verify(templateExportService).exportTemplate("2026-05", "COMMERCIAL", "锰", 9001L);
  }

  @Test
  @DisplayName("/factor-adjust/export-template：参数错误时返回 400")
  void exportTemplateBadRequest() throws Exception {
    when(templateExportService.exportTemplate(eq(""), eq("COMMERCIAL"), eq(null), eq(null)))
        .thenThrow(new IllegalArgumentException("pricingMonth 必填"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.exportTemplate("", "COMMERCIAL", null, null, response);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getErrorMessage()).contains("pricingMonth");
  }

  @Test
  @DisplayName("/factor-adjust/batches：透传批次查询条件")
  void listBatchesDelegatesToQueryService() {
    FactorAdjustBatchPageResponse mocked = new FactorAdjustBatchPageResponse(0, List.of());
    when(queryService.pageBatches(org.mockito.ArgumentMatchers.any())).thenReturn(mocked);

    CommonResult<FactorAdjustBatchPageResponse> result = controller.listBatches(
        "2026-05", "COMMERCIAL", "FAB", "REPRICE_ONLY", "SUCCESS",
        "alice", true, null, 2, 30);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    org.mockito.ArgumentCaptor<com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest> captor =
        org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest.class);
    verify(queryService).pageBatches(captor.capture());
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getAdjustBatchNo()).isEqualTo("FAB");
    assertThat(captor.getValue().getUsageScope()).isEqualTo("REPRICE_ONLY");
    assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    assertThat(captor.getValue().getUploadedBy()).isEqualTo("alice");
    assertThat(captor.getValue().getIncludeAllUploaders()).isTrue();
    assertThat(captor.getValue().getPage()).isEqualTo(2);
    assertThat(captor.getValue().getPageSize()).isEqualTo(30);
  }

  @Test
  @DisplayName("/factor-adjust/prices：透传明细查询条件")
  void listPricesDelegatesToQueryService() {
    FactorAdjustPricePageResponse mocked = new FactorAdjustPricePageResponse(0, List.of());
    when(queryService.pagePrices(org.mockito.ArgumentMatchers.any())).thenReturn(mocked);

    CommonResult<FactorAdjustPricePageResponse> result =
        controller.listPrices(9001L, 191L, "锰", "CHANGED", null, 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    org.mockito.ArgumentCaptor<com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest> captor =
        org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest.class);
    verify(queryService).pagePrices(captor.capture());
    assertThat(captor.getValue().getAdjustBatchId()).isEqualTo(9001L);
    assertThat(captor.getValue().getFactorIdentityId()).isEqualTo(191L);
    assertThat(captor.getValue().getKeyword()).isEqualTo("锰");
    assertThat(captor.getValue().getStatus()).isEqualTo("CHANGED");
  }

  @Test
  @DisplayName("/factor-adjust/monthly-prices：透传影响因素列表查询条件")
  void listMonthlyPricesDelegatesToQueryService() {
    FactorMonthlyPriceListPageResponse mocked = new FactorMonthlyPriceListPageResponse(0, List.of());
    when(queryService.pageMonthlyPrices(org.mockito.ArgumentMatchers.any())).thenReturn(mocked);

    CommonResult<FactorMonthlyPriceListPageResponse> result =
        controller.listMonthlyPrices("2026-05", "COMMERCIAL", "锰", "ADJUST_IMPORT",
            "REPRICE_AND_DAILY", "alice", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    org.mockito.ArgumentCaptor<com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest> captor =
        org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest.class);
    verify(queryService).pageMonthlyPrices(captor.capture());
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getKeyword()).isEqualTo("锰");
    assertThat(captor.getValue().getSourceTag()).isEqualTo("ADJUST_IMPORT");
    assertThat(captor.getValue().getLatestAdjustUsageScope()).isEqualTo("REPRICE_AND_DAILY");
    assertThat(captor.getValue().getLatestAdjustedBy()).isEqualTo("alice");
  }
}
