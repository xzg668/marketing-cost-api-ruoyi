package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * PriceLinkedItemController 单测 —— 聚焦 T18 新增的 {@code POST /items/import-excel}。
 *
 * <p>项目约定：直接 new Controller + mock Service，不起 MockMvc。
 * 两例：
 * <ol>
 *   <li>正常 file + pricingMonth → Service 透传，Controller 包 {@code CommonResult.success}</li>
 *   <li>包含非法公式：Service 把错误行放 errors；Controller 仍是 success（业务失败非 HTTP 失败）</li>
 * </ol>
 */
class PriceLinkedItemControllerTest {

  private PriceLinkedItemService priceLinkedItemService;
  private PriceLinkedItemController controller;

  @BeforeEach
  void setUp() {
    priceLinkedItemService = mock(PriceLinkedItemService.class);
    controller = new PriceLinkedItemController(priceLinkedItemService);
  }

  @Test
  @DisplayName("/items/import-excel：正常透传 Service 结果")
  void importExcel_ok_delegatesToService() {
    PriceItemImportResponse mocked = new PriceItemImportResponse();
    mocked.setBatchId("batch-uuid-lp-1");
    mocked.setLinkedCount(2);
    mocked.setFixedCount(1);
    when(priceLinkedItemService.importExcel(any(InputStream.class), eq("2026-02")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{1, 2, 3});

    CommonResult<PriceItemImportResponse> result = controller.importExcel(file, "2026-02");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getBatchId()).isEqualTo("batch-uuid-lp-1");
    assertThat(result.getData().getLinkedCount()).isEqualTo(2);
    assertThat(result.getData().getFixedCount()).isEqualTo(1);
    verify(priceLinkedItemService).importExcel(any(InputStream.class), eq("2026-02"));
  }

  @Test
  @DisplayName("/items/import-excel：含非法公式时 Service 把错误行列在 errors，Controller 仍 success")
  void importExcel_invalidFormula_reportedInErrors() {
    PriceItemImportResponse mocked = new PriceItemImportResponse();
    mocked.setBatchId("batch-uuid-lp-2");
    mocked.setLinkedCount(1);
    mocked.setSkipped(1);
    mocked.getErrors().add(new PriceItemImportResponse.ErrorRow(
        3, "203250445", "联动",
        "联动公式非法或无法解析: (Cu*0.59+Zn*0.41"));
    when(priceLinkedItemService.importExcel(any(InputStream.class), eq("2026-02")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{9});

    CommonResult<PriceItemImportResponse> result = controller.importExcel(file, "2026-02");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSkipped()).isEqualTo(1);
    assertThat(result.getData().getErrors()).hasSize(1);
    assertThat(result.getData().getErrors().get(0).getMessage()).contains("公式非法");
  }

  @Test
  @DisplayName("/items/import-excel：file 为空返 BAD_REQUEST，不触达 Service")
  void importExcel_emptyFile_returnsBadRequest() {
    MockMultipartFile empty = new MockMultipartFile(
        "file", "empty.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[0]);

    CommonResult<PriceItemImportResponse> result = controller.importExcel(empty, "2026-02");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    verify(priceLinkedItemService, org.mockito.Mockito.never())
        .importExcel(any(), any());
  }
}
