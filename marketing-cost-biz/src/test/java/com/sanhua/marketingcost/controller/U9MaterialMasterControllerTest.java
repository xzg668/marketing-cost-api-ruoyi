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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialRawPageResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingMaterialOptionResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.service.U9MaterialMasterService;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@DisplayName("U9MaterialMasterController")
class U9MaterialMasterControllerTest {
  private U9MaterialMasterService service;
  private U9MaterialMasterController controller;

  @BeforeEach
  void setUp() {
    service = mock(U9MaterialMasterService.class);
    controller = new U9MaterialMasterController(service);
  }

  @Test
  @DisplayName("import：成功上传时透传文件流、文件名和操作人")
  void importExcelSuccess() {
    U9MaterialImportResponse response = new U9MaterialImportResponse();
    response.setSuccessCount(2);
    when(service.importExcel(any(InputStream.class), eq("u9.xlsx"), eq("alice"), eq("PLATE")))
        .thenReturn(response);

    MockMultipartFile file = new MockMultipartFile("file", "u9.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1});
    CommonResult<U9MaterialImportResponse> result =
        controller.importExcel(file, "PLATE", new UsernamePasswordAuthenticationToken("alice", null));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSuccessCount()).isEqualTo(2);
    verify(service).importExcel(any(InputStream.class), eq("u9.xlsx"), eq("alice"), eq("PLATE"));
  }

  @Test
  @DisplayName("import：空文件返回 BAD_REQUEST，不触达 Service")
  void importExcelEmptyFile() {
    MockMultipartFile file = new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0]);

    CommonResult<U9MaterialImportResponse> result = controller.importExcel(file, null, null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    verify(service, never()).importExcel(any(), any(), any(), any());
  }

  @Test
  @DisplayName("import：导入异常返回明确失败提示")
  void importExcelServiceException() {
    when(service.importExcel(any(InputStream.class), eq("bad.xlsx"), eq((String) null), eq("COMMERCIAL")))
        .thenThrow(new IllegalArgumentException("缺少必填表头"));
    MockMultipartFile file = new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", new byte[] {1});

    CommonResult<U9MaterialImportResponse> result = controller.importExcel(file, "COMMERCIAL", null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("U9 物料主档导入失败").contains("缺少必填表头");
  }

  @Test
  @DisplayName("raw：查询参数透传并返回分页响应")
  void rawDelegatesToService() {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode("301050066");
    Page<MaterialMasterRaw> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(row));
    when(service.pageRaw("301", "阀", "spec", "model", "D1", "采购件",
        "电磁阀", "主要材料", "商用", "制造部", "PLATE", 1, 20))
        .thenReturn(page);

    CommonResult<U9MaterialRawPageResponse> result = controller.raw(
        "301", "阀", "spec", "model", "D1", "采购件",
        "电磁阀", "主要材料", "商用", "制造部", "PLATE", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().total()).isEqualTo(1);
    assertThat(result.getData().records().get(0).getMaterialCode()).isEqualTo("301050066");
  }

  @Test
  @DisplayName("options：选择器关键字和条数透传并返回字段带出响应")
  void optionsDelegatesToService() {
    QuoteCostingMaterialOptionResponse option = new QuoteCostingMaterialOptionResponse();
    option.setMaterialCode("MAT-001");
    option.setMaterialName("紫铜直管");
    option.setChildModel("MODEL-1");
    option.setUnit("KG");
    option.setMaterialAttribute("铜");
    option.setShapeAttribute("采购件");
    when(service.options("铜管", "PLATE", 15)).thenReturn(List.of(option));

    CommonResult<List<QuoteCostingMaterialOptionResponse>> result = controller.options("铜管", "PLATE", 15);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().get(0).getMaterialCode()).isEqualTo("MAT-001");
    assertThat(result.getData().get(0).getMaterialAttribute()).isEqualTo("铜");
    verify(service).options("铜管", "PLATE", 15);
  }

  @Test
  @DisplayName("options：非法 limit 使用默认 20")
  void optionsDefaultsLimit() {
    when(service.options(null, null, 20)).thenReturn(List.of());

    CommonResult<List<QuoteCostingMaterialOptionResponse>> result = controller.options(null, null, 0);

    assertThat(result.isSuccess()).isTrue();
    verify(service).options(null, null, 20);
  }

  @Test
  @DisplayName("权限：U9 物料页接口使用独立权限码")
  void permissions() throws Exception {
    assertPerm("templateMapping", "@ss.hasPermi('base:u9-material:export')");
    Method rawMethod = U9MaterialMasterController.class.getMethod(
        "raw", String.class, String.class, String.class, String.class, String.class,
        String.class, String.class, String.class, String.class, String.class,
        String.class, Integer.class, Integer.class);
    assertThat(rawMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:u9-material:list')");
    Method optionsMethod = U9MaterialMasterController.class.getMethod(
        "options", String.class, String.class, Integer.class);
    assertThat(optionsMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:u9-material:list')");
    Method importMethod = U9MaterialMasterController.class.getMethod(
        "importExcel", org.springframework.web.multipart.MultipartFile.class,
        String.class,
        org.springframework.security.core.Authentication.class);
    assertThat(importMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:u9-material:import')");
  }

  private static void assertPerm(String methodName, String expected) throws Exception {
    Method method = U9MaterialMasterController.class.getMethod(methodName);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
  }
}
