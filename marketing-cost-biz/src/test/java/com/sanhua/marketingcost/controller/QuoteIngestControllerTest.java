package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusCheckRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogListItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteExcelImportService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteIngestService;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class QuoteIngestControllerTest {
  private QuoteIngestService quoteIngestService;
  private QuoteExcelImportService quoteExcelImportService;
  private QuoteBomStatusService quoteBomStatusService;
  private QuoteRequestQueryService quoteRequestQueryService;
  private QuoteIngestController controller;

  @BeforeEach
  void setUp() {
    quoteIngestService = mock(QuoteIngestService.class);
    quoteExcelImportService = mock(QuoteExcelImportService.class);
    quoteBomStatusService = mock(QuoteBomStatusService.class);
    quoteRequestQueryService = mock(QuoteRequestQueryService.class);
    controller =
        new QuoteIngestController(
            quoteIngestService,
            quoteExcelImportService,
            quoteBomStatusService,
            quoteRequestQueryService);
  }

  @Test
  void ingestReturnsServiceResponse() {
    QuoteIngestRequest request = new QuoteIngestRequest();
    QuoteIngestResponse response = new QuoteIngestResponse();
    response.setAccepted(true);
    response.setOaFormId(100L);
    when(quoteIngestService.ingest(request)).thenReturn(response);

    CommonResult<QuoteIngestResponse> result = controller.ingest(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().isAccepted()).isTrue();
    assertThat(result.getData().getOaFormId()).isEqualTo(100L);
    verify(quoteIngestService).ingest(request);
  }

  @Test
  void ingestExceptionReturnsBadRequest() {
    QuoteIngestRequest request = new QuoteIngestRequest();
    when(quoteIngestService.ingest(request)).thenThrow(new QuoteIngestException("接入失败"));

    CommonResult<QuoteIngestResponse> result = controller.ingest(request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("接入失败");
  }

  @Test
  void previewExcelReturnsServiceResponse() {
    MockMultipartFile file =
        new MockMultipartFile("file", "quote.xlsx", "application/vnd.ms-excel", new byte[] {1});
    QuoteExcelImportPreviewResponse response = new QuoteExcelImportPreviewResponse();
    response.setValid(true);
    when(quoteExcelImportService.preview(any(InputStream.class), eq("quote.xlsx"))).thenReturn(response);

    CommonResult<QuoteExcelImportPreviewResponse> result = controller.previewExcel(file);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().isValid()).isTrue();
  }

  @Test
  void commitExcelReturnsServiceResponse() {
    MockMultipartFile file =
        new MockMultipartFile("file", "quote.xlsx", "application/vnd.ms-excel", new byte[] {1});
    QuoteExcelImportCommitResponse response = new QuoteExcelImportCommitResponse();
    response.setCommitted(true);
    when(quoteExcelImportService.commit(any(InputStream.class), eq("quote.xlsx"))).thenReturn(response);

    CommonResult<QuoteExcelImportCommitResponse> result = controller.commitExcel(file);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().isCommitted()).isTrue();
  }

  @Test
  void bomStatusReturnsServiceResponse() {
    QuoteBomStatusResponse response = new QuoteBomStatusResponse();
    response.setOaNo("OA-T7-001");
    when(quoteBomStatusService.listByOaNo("OA-T7-001")).thenReturn(response);

    CommonResult<QuoteBomStatusResponse> result = controller.bomStatus("OA-T7-001");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getOaNo()).isEqualTo("OA-T7-001");
    verify(quoteBomStatusService).listByOaNo("OA-T7-001");
  }

  @Test
  void checkBomStatusReturnsServiceResponse() {
    QuoteBomStatusCheckRequest request = new QuoteBomStatusCheckRequest();
    request.setOaNo("OA-T7-001");
    QuoteBomStatusResponse response = new QuoteBomStatusResponse();
    response.setSyncedCount(1);
    when(quoteBomStatusService.checkByOaNo("OA-T7-001")).thenReturn(response);

    CommonResult<QuoteBomStatusResponse> result = controller.checkBomStatus(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getSyncedCount()).isEqualTo(1);
    verify(quoteBomStatusService).checkByOaNo("OA-T7-001");
  }

  @Test
  void logsReturnsServiceResponse() {
    QuoteIngestLogListItemResponse row = new QuoteIngestLogListItemResponse();
    row.setOaNo("OA-T8-001");
    PageResult<QuoteIngestLogListItemResponse> page = new PageResult<>(List.of(row), 1L);
    when(quoteRequestQueryService.pageLogs(1, 20, "OA-T8", "OA", "IMPORTED")).thenReturn(page);

    CommonResult<PageResult<QuoteIngestLogListItemResponse>> result =
        controller.logs(1, 20, "OA-T8", "OA", "IMPORTED");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
    assertThat(result.getData().getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
  }

  @Test
  void logDetailReturnsRawPayload() {
    QuoteIngestLogDetailResponse response = new QuoteIngestLogDetailResponse();
    response.setPayloadJson("{\"raw\":true}");
    response.setNormalizedJson("{\"normalized\":true}");
    when(quoteRequestQueryService.getLogDetail(8L)).thenReturn(response);

    CommonResult<QuoteIngestLogDetailResponse> result = controller.logDetail(8L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPayloadJson()).contains("raw");
    assertThat(result.getData().getNormalizedJson()).contains("normalized");
  }
}
