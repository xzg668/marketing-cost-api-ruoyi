package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefPageResponse;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefImportService;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefQueryService;
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

class CmsMaterialScrapRefControllerTest {
  private CmsMaterialScrapRefImportService importService;
  private CmsMaterialScrapRefQueryService queryService;
  private CmsMaterialScrapRefController controller;

  @BeforeEach
  void setUp() {
    importService = mock(CmsMaterialScrapRefImportService.class);
    queryService = mock(CmsMaterialScrapRefQueryService.class);
    controller = new CmsMaterialScrapRefController(importService, queryService);
    var auth = new UsernamePasswordAuthenticationToken("cms-user", "N/A");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("T4 /material-scrap-refs/import：上传 Excel 后透传导入服务")
  void importExcelPassesFileToService() {
    CmsMaterialScrapRefImportResponse response = new CmsMaterialScrapRefImportResponse();
    response.setStatus("IMPORTED");
    response.setUpdatedMappingCount(6);
    when(importService.importExcel(any(InputStream.class), eq("COMMERCIAL")))
        .thenReturn(response);

    CommonResult<CmsMaterialScrapRefImportResponse> result =
        controller.importExcel(
            new MockMultipartFile("file", "scrap.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "x".getBytes()),
            null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getUpdatedMappingCount()).isEqualTo(6);
  }

  @Test
  @DisplayName("T4 /material-scrap-refs/import：空文件返回 BAD_REQUEST")
  void importExcelRejectsEmptyFile() {
    CommonResult<CmsMaterialScrapRefImportResponse> result =
        controller.importExcel(
            new MockMultipartFile("file", "empty.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]),
            null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    verify(importService, never()).importExcel(any(), any());
  }

  @Test
  @DisplayName("T4 /material-scrap-refs/current：查询 current 映射并透传业务单元")
  void pageCurrentPassesFiltersToQueryService() {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setMaterialCode("301050066");
    when(queryService.pageCurrent("301050066", "301990317", "铜", 1, 20, "HOUSEHOLD"))
        .thenReturn(new CmsMaterialScrapRefPageResponse<>(1, List.of(row)));

    CommonResult<CmsMaterialScrapRefPageResponse<MaterialScrapRef>> result =
        controller.pageCurrent("301050066", "301990317", "铜", " HOUSEHOLD ", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    verify(queryService).pageCurrent("301050066", "301990317", "铜", 1, 20, "HOUSEHOLD");
  }

  @Test
  @DisplayName("T4 Controller 权限：导入和查询使用 CMS 成本数据权限")
  void controllerPermissions() throws Exception {
    Method importMethod =
        CmsMaterialScrapRefController.class.getMethod(
            "importExcel",
            org.springframework.web.multipart.MultipartFile.class,
            String.class);
    Method currentMethod =
        CmsMaterialScrapRefController.class.getMethod(
            "pageCurrent", String.class, String.class, String.class, String.class, int.class, int.class);

    assertThat(importMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('cms:cost:import')");
    assertThat(currentMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('cms:cost:list')");
  }
}
