package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioService;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;

class SupplierSupplyRatioControllerTest {

  private SupplierSupplyRatioService service;
  private SupplierSupplyRatioImportService importService;
  private SupplierSupplyRatioController controller;

  @BeforeEach
  void setUp() {
    service = mock(SupplierSupplyRatioService.class);
    importService = mock(SupplierSupplyRatioImportService.class);
    controller = new SupplierSupplyRatioController(service, importService);
    var auth = new UsernamePasswordAuthenticationToken("ratio-user", "N/A");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Test
  @DisplayName("POST /supplier-supply-ratios/import-excel：上传 Excel 后透传导入服务")
  void importExcelPassesFileToService() {
    com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse response =
        new com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse();
    response.setBatchNo("SSR-batch-1");
    response.setInsertedRows(2);
    when(importService.importExcel(any(InputStream.class), eq("ratio.xls"), eq("COMMERCIAL"), eq("alice")))
        .thenReturn(response);

    CommonResult<com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse> result =
        controller.importExcel(
            new MockMultipartFile("file", "ratio.xls", "application/vnd.ms-excel", new byte[]{1}),
            null,
            new UsernamePasswordAuthenticationToken("alice", "N/A"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getBatchNo()).isEqualTo("SSR-batch-1");
    assertThat(result.getData().getInsertedRows()).isEqualTo(2);
    verify(importService).importExcel(any(InputStream.class), eq("ratio.xls"), eq("COMMERCIAL"), eq("alice"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("GET /supplier-supply-ratios：分页查询透传过滤条件和业务单元")
  void pagePassesFiltersToService() {
    SupplierSupplyRatio row = row(10L);
    when(service.page("203", "小阀座", "SHF", "大新", "EXCEL", 2, 30, "HOUSEHOLD"))
        .thenReturn(new SupplierSupplyRatioPageResponse(1, List.of(row)));

    CommonResult<SupplierSupplyRatioPageResponse> result =
        controller.page("203", "小阀座", "SHF", "大新", "EXCEL", " HOUSEHOLD ", 2, 30);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    verify(service).page("203", "小阀座", "SHF", "大新", "EXCEL", 2, 30, "HOUSEHOLD");
  }

  @Test
  @DisplayName("GET /supplier-supply-ratios：未传业务单元时使用当前登录上下文")
  void pageUsesBusinessUnitContextWhenRequestParamMissing() {
    when(service.page(null, null, null, null, null, 1, 20, "COMMERCIAL"))
        .thenReturn(new SupplierSupplyRatioPageResponse(0, List.of()));

    CommonResult<SupplierSupplyRatioPageResponse> result =
        controller.page(null, null, null, null, null, null, 1, 20);

    assertThat(result.isSuccess()).isTrue();
    verify(service).page(null, null, null, null, null, 1, 20, "COMMERCIAL");
  }

  @Test
  @DisplayName("PATCH /supplier-supply-ratios/{id}：更新供货比例并透传操作者")
  void updatePassesRequestAndOperator() {
    SupplierSupplyRatioUpdateRequest request = new SupplierSupplyRatioUpdateRequest();
    request.setSupplyRatio(new BigDecimal("0.75"));
    SupplierSupplyRatio updated = row(20L);
    updated.setSupplyRatio(new BigDecimal("0.75"));
    when(service.update(eq(20L), any(SupplierSupplyRatioUpdateRequest.class), eq("alice")))
        .thenReturn(updated);

    CommonResult<SupplierSupplyRatio> result =
        controller.update(20L, request, new UsernamePasswordAuthenticationToken("alice", "N/A"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSupplyRatio()).isEqualByComparingTo("0.75");
    verify(service).update(20L, request, "alice");
  }

  @Test
  @DisplayName("PATCH /supplier-supply-ratios/{id}：业务校验失败返回 BAD_REQUEST")
  void updateReturnsBadRequestForValidationError() {
    SupplierSupplyRatioUpdateRequest request = new SupplierSupplyRatioUpdateRequest();
    when(service.update(eq(20L), any(SupplierSupplyRatioUpdateRequest.class), eq("alice")))
        .thenThrow(new IllegalArgumentException("供货比例不能小于 0"));

    CommonResult<SupplierSupplyRatio> result =
        controller.update(20L, request, new UsernamePasswordAuthenticationToken("alice", "N/A"));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("不能小于 0");
  }

  @Test
  @DisplayName("DELETE /supplier-supply-ratios/{id}：逻辑删除透传操作者")
  void deletePassesOperator() {
    CommonResult<Void> result =
        controller.delete(20L, new UsernamePasswordAuthenticationToken("bob", "N/A"));

    assertThat(result.isSuccess()).isTrue();
    verify(service).delete(20L, "bob");
  }

  @Test
  @DisplayName("Controller 权限：查询、编辑、删除分别使用供应商供货比例权限")
  void controllerPermissions() throws Exception {
    Method pageMethod =
        SupplierSupplyRatioController.class.getMethod(
            "page",
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            int.class,
            int.class);
    Method updateMethod =
        SupplierSupplyRatioController.class.getMethod(
            "update",
            Long.class,
            SupplierSupplyRatioUpdateRequest.class,
            org.springframework.security.core.Authentication.class);
    Method deleteMethod =
        SupplierSupplyRatioController.class.getMethod(
            "delete", Long.class, org.springframework.security.core.Authentication.class);
    Method importMethod =
        SupplierSupplyRatioController.class.getMethod(
            "importExcel",
            org.springframework.web.multipart.MultipartFile.class,
            String.class,
            org.springframework.security.core.Authentication.class);

    assertThat(pageMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:supplier-supply-ratio:list')");
    assertThat(updateMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:supplier-supply-ratio:edit')");
    assertThat(deleteMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:supplier-supply-ratio:remove')");
    assertThat(importMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:supplier-supply-ratio:import')");
  }

  private SupplierSupplyRatio row(Long id) {
    SupplierSupplyRatio row = new SupplierSupplyRatio();
    row.setId(id);
    row.setMaterialCode("203240251");
    row.setMaterialName("小阀座");
    row.setSupplierName("新昌县大新机械厂");
    row.setSpecModel("SHF-000-036003");
    row.setSupplyRatio(BigDecimal.ONE);
    return row;
  }
}
