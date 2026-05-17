package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CmsCostBatchPageResponse;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectiveLogPageResponse;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsCostImportService;
import com.sanhua.marketingcost.service.CmsCostQueryService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CmsCostControllerTest {

  private CmsCostImportService importService;
  private CmsCostQueryService queryService;
  private CmsSalaryCostSourceEffectiveService salaryEffectiveService;
  private CmsAuxSubjectSourceEffectiveService auxEffectiveService;
  private CmsCostController controller;

  @BeforeEach
  void setUp() {
    importService = mock(CmsCostImportService.class);
    queryService = mock(CmsCostQueryService.class);
    salaryEffectiveService = mock(CmsSalaryCostSourceEffectiveService.class);
    auxEffectiveService = mock(CmsAuxSubjectSourceEffectiveService.class);
    controller =
        new CmsCostController(importService, queryService, salaryEffectiveService, auxEffectiveService);
    var auth = new UsernamePasswordAuthenticationToken("cms-user", "N/A");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("/cms-cost/import：正常上传 4 个文件，透传 Service 并返回统计")
  void importCmsCost_ok() {
    CmsCostImportResponse response = new CmsCostImportResponse();
    response.setImportBatchId(10L);
    response.setStatus("DERIVED");
    response.setPlanRowCount(1);
    response.setWorkshopRowCount(2);
    response.setSubjectRowCount(3);
    response.setSalaryInsertCount(1);
    response.setAuxInsertCount(2);
    when(importService.importExcel(
            any(InputStream.class),
            eq("plan.xlsx"),
            any(InputStream.class),
            eq("workshop.xlsx"),
            any(InputStream.class),
            eq("subject.xlsx"),
            any(InputStream.class),
            eq("subject-setting.xlsx"),
            eq(false),
            eq("cms-user"),
            eq("COMMERCIAL")))
        .thenReturn(response);

    CommonResult<CmsCostImportResponse> result =
        controller.importCmsCost(
            file("planFile", "plan.xlsx"),
            file("workshopFile", "workshop.xlsx"),
            file("subjectFile", "subject.xlsx"),
            file("subjectSettingFile", "subject-setting.xlsx"),
            false,
            null,
            SecurityContextHolder.getContext().getAuthentication());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getStatus()).isEqualTo("DERIVED");
    assertThat(result.getData().getSalaryInsertCount()).isEqualTo(1);
    assertThat(result.getData().getAuxInsertCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("/cms-cost/import：businessUnitType 参数优先于登录上下文")
  void importCmsCost_businessUnitTypeParamOverridesContext() {
    CmsCostImportResponse response = new CmsCostImportResponse();
    response.setImportBatchId(11L);
    response.setStatus("DRY_RUN");
    when(importService.importExcel(
            any(InputStream.class),
            eq("plan.xlsx"),
            any(InputStream.class),
            eq("workshop.xlsx"),
            any(InputStream.class),
            eq("subject.xlsx"),
            any(InputStream.class),
            eq("subject-setting.xlsx"),
            eq(true),
            eq("cms-user"),
            eq("HOUSEHOLD")))
        .thenReturn(response);

    CommonResult<CmsCostImportResponse> result =
        controller.importCmsCost(
            file("planFile", "plan.xlsx"),
            file("workshopFile", "workshop.xlsx"),
            file("subjectFile", "subject.xlsx"),
            file("subjectSettingFile", "subject-setting.xlsx"),
            true,
            " HOUSEHOLD ",
            SecurityContextHolder.getContext().getAuthentication());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getStatus()).isEqualTo("DRY_RUN");
    verify(importService)
        .importExcel(
            any(InputStream.class),
            eq("plan.xlsx"),
            any(InputStream.class),
            eq("workshop.xlsx"),
            any(InputStream.class),
            eq("subject.xlsx"),
            any(InputStream.class),
            eq("subject-setting.xlsx"),
            eq(true),
            eq("cms-user"),
            eq("HOUSEHOLD"));
  }

  @Test
  @DisplayName("/cms-cost/import：只上传科目设置时允许导入")
  void importCmsCost_subjectSettingOnly() {
    CmsCostImportResponse response = new CmsCostImportResponse();
    response.setImportBatchId(12L);
    response.setStatus("IMPORTED");
    response.setSubjectSettingRowCount(20);
    when(importService.importExcel(
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any(InputStream.class),
            eq("subject-setting.xlsx"),
            eq(false),
            eq("cms-user"),
            eq("COMMERCIAL")))
        .thenReturn(response);

    CommonResult<CmsCostImportResponse> result =
        controller.importCmsCost(
            null,
            null,
            null,
            file("subjectSettingFile", "subject-setting.xlsx"),
            false,
            null,
            SecurityContextHolder.getContext().getAuthentication());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSubjectSettingRowCount()).isEqualTo(20);
    verify(importService)
        .importExcel(
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            any(InputStream.class),
            eq("subject-setting.xlsx"),
            eq(false),
            eq("cms-user"),
            eq("COMMERCIAL"));
  }

  @Test
  @DisplayName("/cms-cost/import：未选择任何文件返回明确 BAD_REQUEST")
  void importCmsCost_noFile() {
    CommonResult<CmsCostImportResponse> result =
        controller.importCmsCost(
            null,
            null,
            null,
            null,
            false,
            null,
            SecurityContextHolder.getContext().getAuthentication());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("至少需要上传一个 CMS Excel 文件");
    verify(importService, never())
        .importExcel(any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any());
  }

  @Test
  @DisplayName("/cms-cost/import：表头解析错误返回明确 BAD_REQUEST")
  void importCmsCost_parseError() {
    when(importService.importExcel(any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any()))
        .thenThrow(new IllegalArgumentException("CMS Excel 解析失败: 产品计划成本汇总 第1行[父件编码]缺少必要列"));

    CommonResult<CmsCostImportResponse> result =
        controller.importCmsCost(
            file("planFile", "plan.xlsx"),
            file("workshopFile", "workshop.xlsx"),
            file("subjectFile", "subject.xlsx"),
            file("subjectSettingFile", "subject-setting.xlsx"),
            false,
            null,
            SecurityContextHolder.getContext().getAuthentication());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("父件编码").contains("缺少必要列");
  }

  @Test
  @DisplayName("/cms-cost/import：权限标识使用 cms:cost:import")
  void importCmsCost_permissionAnnotation() throws Exception {
    Method method =
        CmsCostController.class.getMethod(
            "importCmsCost",
            org.springframework.web.multipart.MultipartFile.class,
            org.springframework.web.multipart.MultipartFile.class,
            org.springframework.web.multipart.MultipartFile.class,
            org.springframework.web.multipart.MultipartFile.class,
            boolean.class,
            String.class,
            org.springframework.security.core.Authentication.class);

    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("@ss.hasPermi('cms:cost:import')");
  }

  @Test
  @DisplayName("/cms-cost/batches：查询批次透传参数并受 cms:cost:list 保护")
  void pageBatches_delegatesToQueryService() throws Exception {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setBatchNo("CMS001");
    when(queryService.pageBatches("CMS", "DERIVED", 1, 20, "COMMERCIAL"))
        .thenReturn(new CmsCostBatchPageResponse(1, List.of(batch)));

    var result = controller.pageBatches("CMS", "DERIVED", null, 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getList()).extracting(CmsCostImportBatch::getBatchNo)
        .containsExactly("CMS001");
    assertPermission(
        "pageBatches",
        "@ss.hasPermi('cms:cost:list')",
        String.class,
        String.class,
        String.class,
        int.class,
        int.class);
  }

  @Test
  @DisplayName("/cms-cost/import-records：兼容文档路径并透传 businessUnitType")
  void pageImportRecords_delegatesToQueryService() throws Exception {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setBatchNo("CMS002");
    when(queryService.pageBatches("CMS", "IMPORTED", 2, 50, "HOUSEHOLD"))
        .thenReturn(new CmsCostBatchPageResponse(1, List.of(batch)));

    var result = controller.pageImportRecords("CMS", "IMPORTED", "HOUSEHOLD", 2, 50);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getList()).extracting(CmsCostImportBatch::getBatchNo)
        .containsExactly("CMS002");
    assertPermission(
        "pageImportRecords",
        "@ss.hasPermi('cms:cost:list')",
        String.class,
        String.class,
        String.class,
        int.class,
        int.class);
  }

  @Test
  @DisplayName("/cms-cost/effective-source-logs：按操作类型查询透传参数")
  void pageEffectiveSourceLogs_delegatesToQueryService() throws Exception {
    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setActionType("BLOCKED");
    when(queryService.pageEffectiveSourceLogs(
            2026, "P-100", "2026-01", "AUX_SUBJECT", "0201", "BLOCKED", 1, 20, "COMMERCIAL"))
        .thenReturn(new CmsCostSourceEffectiveLogPageResponse(1, List.of(log)));

    var result =
        controller.pageEffectiveSourceLogs(
            2026, "P-100", "2026-01", "AUX_SUBJECT", "0201", "BLOCKED", null, 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRecords()).extracting(CmsCostSourceEffectiveLog::getActionType)
        .containsExactly("BLOCKED");
    assertPermission(
        "pageEffectiveSourceLogs",
        "@ss.hasPermi('cms:cost:list')",
        Integer.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        String.class,
        int.class,
        int.class);
  }

  private void assertPermission(String methodName, String expected, Class<?>... parameterTypes)
      throws Exception {
    Method method = CmsCostController.class.getMethod(methodName, parameterTypes);
    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo(expected);
  }

  private MockMultipartFile file(String fieldName, String fileName) {
    return new MockMultipartFile(
        fieldName,
        fileName,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
  }
}
