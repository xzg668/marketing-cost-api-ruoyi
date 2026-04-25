package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.service.FinanceBasePriceImportService;
import com.sanhua.marketingcost.service.FinanceBasePriceService;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * FinanceBasePriceController 单测 —— 聚焦 T17 新增的 {@code POST /import-excel}。
 *
 * <p>沿用项目 Controller 单测约定：new Controller + mock Service，不起 MockMvc。
 * 三例：
 * <ol>
 *   <li>正常路径：Service 返 batchId + imported=3，Controller 包 {@code CommonResult.success}</li>
 *   <li>file 为空：返 BAD_REQUEST，不触达 Service</li>
 *   <li>priceMonth 透传：Service 收到的 month 与请求一致</li>
 * </ol>
 */
class FinanceBasePriceControllerTest {

  private FinanceBasePriceService financeBasePriceService;
  private FinanceBasePriceImportService financeBasePriceImportService;
  private FinanceBasePriceController controller;

  @BeforeEach
  void setUp() {
    financeBasePriceService = mock(FinanceBasePriceService.class);
    financeBasePriceImportService = mock(FinanceBasePriceImportService.class);
    controller = new FinanceBasePriceController(
        financeBasePriceService, financeBasePriceImportService);
  }

  @Test
  @DisplayName("/import-excel：file + priceMonth 齐全，透传 Service 结果")
  void importExcel_ok_delegatesToService() {
    InfluenceFactorImportResponse mocked = new InfluenceFactorImportResponse();
    mocked.setBatchId("batch-uuid-1");
    mocked.setImported(3);
    when(financeBasePriceImportService.importExcel(any(InputStream.class), eq("2026-02")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "factors.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{1, 2, 3}); // 内容无关紧要（Service 已 mock）

    CommonResult<InfluenceFactorImportResponse> result =
        controller.importExcel(file, "2026-02");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getBatchId()).isEqualTo("batch-uuid-1");
    assertThat(result.getData().getImported()).isEqualTo(3);
    verify(financeBasePriceImportService).importExcel(any(InputStream.class), eq("2026-02"));
  }

  @Test
  @DisplayName("/import-excel：file 为空返 BAD_REQUEST，不触达 Service")
  void importExcel_emptyFile_returnsBadRequest() {
    MockMultipartFile empty = new MockMultipartFile(
        "file", "empty.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[0]);

    CommonResult<InfluenceFactorImportResponse> result =
        controller.importExcel(empty, "2026-02");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("file");
    verify(financeBasePriceImportService, org.mockito.Mockito.never())
        .importExcel(any(), any());
  }

  @Test
  @DisplayName("/import-excel：即使 Service 返部分 skipped，Controller 仍是 success（业务明细在 body）")
  void importExcel_partialFailure_stillSuccess() {
    InfluenceFactorImportResponse mocked = new InfluenceFactorImportResponse();
    mocked.setBatchId("batch-uuid-2");
    mocked.setImported(2);
    mocked.setSkipped(1);
    mocked.getErrors().add(new InfluenceFactorImportResponse.ErrorRow(
        4, "", "简称为空，无法定位因素"));
    when(financeBasePriceImportService.importExcel(any(InputStream.class), eq("2026-03")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "factors.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{9});

    CommonResult<InfluenceFactorImportResponse> result =
        controller.importExcel(file, "2026-03");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSkipped()).isEqualTo(1);
    assertThat(result.getData().getErrors()).hasSize(1);
    assertThat(result.getData().getErrors().get(0).getRowNumber()).isEqualTo(4);
  }
}
