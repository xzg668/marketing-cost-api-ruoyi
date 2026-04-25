package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.service.BomImportService;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * BomImportController 单测 —— 沿用项目 Controller 单测约定：new Controller + mock Service，
 * 不起 MockMvc / 不覆盖鉴权层（鉴权在 SecurityConfigTest 统一验）。
 */
@DisplayName("BomImportController · 路由 / 参数绑定 / 错误响应")
class BomImportControllerTest {

  private BomImportService service;
  private BomImportController controller;

  @BeforeEach
  void setUp() {
    service = mock(BomImportService.class);
    controller = new BomImportController(service);
  }

  @Test
  @DisplayName("/import：file + auth 齐全 → 透传 Service 结果")
  void importExcel_ok_delegatesToService() {
    BomImportResult mocked = new BomImportResult();
    mocked.setImportBatchId("b_20260423_abcdef");
    mocked.setSourceType("EXCEL");
    mocked.setSourceFileName("x.xlsx");
    mocked.setTotalRows(100);
    mocked.setSuccessRows(100);
    mocked.setImportedAt(LocalDateTime.now());
    when(service.importExcel(any(InputStream.class), eq("x.xlsx"), eq("alice")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "x.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
    Authentication auth = new UsernamePasswordAuthenticationToken("alice", null);

    CommonResult<BomImportResult> result = controller.importExcel(file, auth);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getImportBatchId()).isEqualTo("b_20260423_abcdef");
    assertThat(result.getData().getSuccessRows()).isEqualTo(100);
    verify(service).importExcel(any(InputStream.class), eq("x.xlsx"), eq("alice"));
  }

  @Test
  @DisplayName("/import：file 为空 → 返 BAD_REQUEST，不触达 Service")
  void importExcel_emptyFile_returnsBadRequest() {
    MockMultipartFile empty = new MockMultipartFile(
        "file", "empty.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[0]);
    Authentication auth = new UsernamePasswordAuthenticationToken("alice", null);

    CommonResult<BomImportResult> result = controller.importExcel(empty, auth);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("file");
    verify(service, never()).importExcel(any(), anyString(), anyString());
  }

  @Test
  @DisplayName("/import：auth=null（异常场景） → importedBy 传 null，Service 仍被调用")
  void importExcel_nullAuth_passesNullImportedBy() {
    BomImportResult mocked = new BomImportResult();
    mocked.setImportBatchId("b_x");
    when(service.importExcel(any(InputStream.class), eq("x.xlsx"), eq((String) null)))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "x.xlsx", "application/octet-stream", new byte[] {1});

    CommonResult<BomImportResult> result = controller.importExcel(file, null);

    assertThat(result.isSuccess()).isTrue();
    verify(service).importExcel(any(InputStream.class), eq("x.xlsx"), eq((String) null));
  }

  @Test
  @DisplayName("/batches：参数透传，返回 Service 的 list")
  void listBatches_delegates() {
    BomBatchSummary s1 = new BomBatchSummary();
    s1.setBatchId("b1");
    s1.setRowCount(10);
    when(service.listBatches("U9_SOURCE", 1, 20)).thenReturn(List.of(s1));

    CommonResult<List<BomBatchSummary>> result = controller.listBatches("U9_SOURCE", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().get(0).getBatchId()).isEqualTo("b1");
    verify(service).listBatches("U9_SOURCE", 1, 20);
  }

  @Test
  @DisplayName("/batches：layer 非法 → Service 抛 IllegalArgument，Controller 原样向外传播（由 GlobalExceptionHandler 转 500）")
  void listBatches_unsupportedLayer_throwsIllegalArgument() {
    when(service.listBatches(eq("RAW_HIERARCHY"), anyInt(), anyInt()))
        .thenThrow(new IllegalArgumentException("layer 暂只支持 U9_SOURCE"));

    try {
      controller.listBatches("RAW_HIERARCHY", 1, 20);
      // 如果没有抛出则失败
      assertThat(false).as("expected IllegalArgumentException").isTrue();
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("U9_SOURCE");
    }
  }
}
