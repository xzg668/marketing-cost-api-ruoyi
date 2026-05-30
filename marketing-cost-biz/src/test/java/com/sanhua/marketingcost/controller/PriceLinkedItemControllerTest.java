package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchDto;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBatchDetailDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.service.FactorMonthlyPriceAdjustmentService;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.io.InputStream;
import java.util.List;
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
  private FactorMonthlyPriceAdjustmentService factorMonthlyPriceAdjustmentService;
  private PriceLinkedItemController controller;

  @BeforeEach
  void setUp() {
    priceLinkedItemService = mock(PriceLinkedItemService.class);
    factorMonthlyPriceAdjustmentService = mock(FactorMonthlyPriceAdjustmentService.class);
    controller = new PriceLinkedItemController(
        priceLinkedItemService, factorMonthlyPriceAdjustmentService);
  }

  @Test
  @DisplayName("/items：includeHistory=true 透传给 Service 以展示历史版本")
  void list_includeHistory_delegatesToService() {
    PriceLinkedItemDto row = new PriceLinkedItemDto();
    row.setId(11L);
    when(priceLinkedItemService.list(eq("2026-05"), eq("M001"), eq(true)))
        .thenReturn(List.of(row));

    CommonResult<List<PriceLinkedItemDto>> result =
        controller.list("2026-05", "M001", true);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    verify(priceLinkedItemService).list("2026-05", "M001", true);
  }

  @Test
  @DisplayName("/items/import-excel：正常透传 Service 结果")
  void importExcel_ok_delegatesToService() {
    PriceItemImportResponse mocked = new PriceItemImportResponse();
    mocked.setBatchId("batch-uuid-lp-1");
    mocked.setLinkedCount(2);
    mocked.setFixedCount(1);
    when(priceLinkedItemService.importExcel(
        any(InputStream.class), eq("2026-02"), eq(false), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq(null), eq("2026-02-01"), eq("KEEP_EXISTING")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{1, 2, 3});

    CommonResult<PriceItemImportResponse> result =
        controller.importExcel(
            file, "2026-02", "COMMERCIAL", false, null, "2026-02-01", "KEEP_EXISTING");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getBatchId()).isEqualTo("batch-uuid-lp-1");
    assertThat(result.getData().getLinkedCount()).isEqualTo(2);
    assertThat(result.getData().getFixedCount()).isEqualTo(1);
    verify(priceLinkedItemService).importExcel(
        any(InputStream.class), eq("2026-02"), eq(false), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq(null), eq("2026-02-01"), eq("KEEP_EXISTING"));
  }

  @Test
  @DisplayName("/items/import-excel：新参数为空时仍透传给 Service，由 Service 做默认值")
  void importExcel_missingNewParams_delegatesForServiceDefaults() {
    PriceItemImportResponse mocked = new PriceItemImportResponse();
    mocked.setFormulaEffectiveDate("2026-02-01");
    mocked.setFactorPriceConflictStrategy("KEEP_EXISTING");
    when(priceLinkedItemService.importExcel(
        any(InputStream.class), eq("2026-02"), eq(false), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq("APPEND_ONLY"), eq(null), eq(null)))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{1, 2, 3});

    CommonResult<PriceItemImportResponse> result =
        controller.importExcel(file, "2026-02", "COMMERCIAL", false, "APPEND_ONLY", null, null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getFormulaEffectiveDate()).isEqualTo("2026-02-01");
    assertThat(result.getData().getFactorPriceConflictStrategy()).isEqualTo("KEEP_EXISTING");
    verify(priceLinkedItemService).importExcel(
        any(InputStream.class), eq("2026-02"), eq(false), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq("APPEND_ONLY"), eq(null), eq(null));
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
    when(priceLinkedItemService.importExcel(
        any(InputStream.class), eq("2026-02"), eq(true), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq("OVERRIDE_EFFECTIVE"), eq("2026-02-01"), eq("OVERWRITE")))
        .thenReturn(mocked);

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{9});

    CommonResult<PriceItemImportResponse> result =
        controller.importExcel(
            file, "2026-02", "COMMERCIAL", true, "OVERRIDE_EFFECTIVE", "2026-02-01", "OVERWRITE");

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

    CommonResult<PriceItemImportResponse> result =
        controller.importExcel(empty, "2026-02", "COMMERCIAL", false, null, null, null);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    verify(priceLinkedItemService, org.mockito.Mockito.never())
        .importExcel(any(), any(), eq(false), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("/items/import-excel：Service 报非法新参数时返回 BAD_REQUEST")
  void importExcel_invalidNewParam_returnsBadRequest() {
    when(priceLinkedItemService.importExcel(
        any(InputStream.class), eq("2026-02"), eq(false), eq("COMMERCIAL"), eq("linked.xlsx"),
        eq(null), eq("bad-date"), eq("KEEP_EXISTING")))
        .thenThrow(new IllegalArgumentException("formulaEffectiveDate 格式错误，应为 yyyy-MM-dd: bad-date"));

    MockMultipartFile file = new MockMultipartFile(
        "file", "linked.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[]{1, 2, 3});

    CommonResult<PriceItemImportResponse> result =
        controller.importExcel(
            file, "2026-02", "COMMERCIAL", false, null, "bad-date", "KEEP_EXISTING");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("formulaEffectiveDate 格式错误");
  }

  @Test
  @DisplayName("/factors/import-batches：影响因素菜单批次列表复用后端持久化批次")
  void listFactorImportBatches_delegatesToService() {
    FactorUploadBatchDto batch = new FactorUploadBatchDto();
    batch.setId(77L);
    batch.setPriceMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setUploadedBy("alice");
    when(priceLinkedItemService.listImportHistory(
        eq("2026-05"), eq("COMMERCIAL"), eq("alice"), eq(false), eq(10)))
        .thenReturn(List.of(batch));

    CommonResult<List<FactorUploadBatchDto>> result =
        controller.listFactorImportBatches("2026-05", "COMMERCIAL", "alice", false, 10);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().getFirst().getId()).isEqualTo(77L);
    verify(priceLinkedItemService)
        .listImportHistory("2026-05", "COMMERCIAL", "alice", false, 10);
  }

  @Test
  @DisplayName("/factors/import-batches/{id}：影响因素菜单批次明细复用持久化行级来源")
  void getFactorImportBatchDetail_delegatesToService() {
    PriceLinkedImportBatchDetailDto detail = new PriceLinkedImportBatchDetailDto();
    detail.setFactorUploadBatchId(77L);
    detail.setBatchId("77");
    when(priceLinkedItemService.getImportBatchDetail(77L)).thenReturn(detail);

    CommonResult<PriceLinkedImportBatchDetailDto> result =
        controller.getFactorImportBatchDetail(77L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getFactorUploadBatchId()).isEqualTo(77L);
    verify(priceLinkedItemService).getImportBatchDetail(77L);
  }
}
