package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteExcelImportServiceImplTest {
  private QuoteIngestService quoteIngestService;
  private QuoteExcelImportServiceImpl service;

  @BeforeEach
  void setUp() {
    quoteIngestService = mock(QuoteIngestService.class);
    QuoteNormalizeService normalizeService =
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());
    service = new QuoteExcelImportServiceImpl(normalizeService, quoteIngestService);
  }

  @Test
  void previewSingleFormSingleItemSucceeds() {
    QuoteExcelImportPreviewResponse response =
        service.preview(excel(List.of(header("OA-T6-001", "FI-SC-006")), List.of(item("OA-T6-001", 1))), "t6.xlsx");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getFormCount()).isEqualTo(1);
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getForms()).hasSize(1);
    assertThat(response.getForms().get(0).getOaNo()).isEqualTo("OA-T6-001");
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("STANDARD_BATCH");
  }

  @Test
  void previewSingleFormMultipleItemsSucceeds() {
    QuoteExcelImportPreviewResponse response =
        service.preview(
            excel(
                List.of(header("OA-T6-002", "FI-SC-006")),
                List.of(item("OA-T6-002", 1), item("OA-T6-002", 2))),
            "t6.xlsx");

    assertThat(response.isValid()).isTrue();
    assertThat(response.getFormCount()).isEqualTo(1);
    assertThat(response.getItemCount()).isEqualTo(2);
    assertThat(response.getForms().get(0).getItemCount()).isEqualTo(2);
  }

  @Test
  void commitImportsExtraFeeThroughUnifiedIngestService() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    ingestResponse.setOaNo("OA-T6-FEE");
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    QuoteExcelImportCommitResponse response =
        service.commit(
            excel(
                List.of(header("OA-T6-FEE", "FI-SR-005")),
                List.of(newItem("OA-T6-FEE", 1, "新品", "MAT-FEE", "SHF-FEE")),
                List.of(fee("OA-T6-FEE", 1, "MOLD_TOTAL", "模具费用总金额", "MOLD", "50000"))),
            "t6.xlsx");

    assertThat(response.isCommitted()).isTrue();
    assertThat(response.getPreview().getFeeCount()).isEqualTo(1);
    assertThat(response.getResults()).hasSize(1);
    ArgumentCaptor<QuoteIngestRequest> captor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captor.capture());
    assertThat(captor.getValue().getItems().get(0).getExtraFees()).hasSize(1);
    assertThat(captor.getValue().getItems().get(0).getExtraFees().get(0).getFeeCode())
        .isEqualTo("MOLD_TOTAL");
  }

  @Test
  void blankFormNoReturnsErrorAndCommitDoesNotCallIngest() {
    InputStream excel = excel(List.of(header("", "FI-SC-006")), List.of(item("", 1)));

    QuoteExcelImportPreviewResponse preview = service.preview(excel, "t6.xlsx");

    assertThat(preview.isValid()).isFalse();
    assertThat(preview.getErrors()).extracting("code").contains("FORM_NO_REQUIRED");

    QuoteExcelImportCommitResponse commit =
        service.commit(excel(List.of(header("", "FI-SC-006")), List.of(item("", 1))), "t6.xlsx");
    assertThat(commit.isCommitted()).isFalse();
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void itemWithoutMaterialAndModelReturnsError() {
    QuoteExcelImportPreviewResponse response =
        service.preview(
            excel(
                List.of(header("OA-T6-BAD-ITEM", "FI-SC-006")),
                List.of(newItem("OA-T6-BAD-ITEM", 1, "批量品", "", ""))),
            "t6.xlsx");

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("PRODUCT_KEY_REQUIRED");
  }

  @Test
  void blankGoldPriceReturnsWarningAndCanCommit() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    QuoteExcelImportPreviewResponse preview =
        service.preview(excel(List.of(header("OA-T6-GOLD", "FI-SC-006")), List.of(item("OA-T6-GOLD", 1))), "t6.xlsx");

    assertThat(preview.isValid()).isTrue();
    assertThat(preview.getWarnings()).extracting("code").contains("GOLD_PRICE_EMPTY");

    QuoteExcelImportCommitResponse commit =
        service.commit(excel(List.of(header("OA-T6-GOLD", "FI-SC-006")), List.of(item("OA-T6-GOLD", 1))), "t6.xlsx");
    assertThat(commit.isCommitted()).isTrue();
    verify(quoteIngestService).ingest(any());
  }

  @Test
  void commitProvidesStableIdempotencyInputForUnifiedIngestService() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    service.commit(
        excel(List.of(header("OA-T6-IDEMPOTENT", "FI-SC-006")), List.of(item("OA-T6-IDEMPOTENT", 1))),
        "t6.xlsx");

    ArgumentCaptor<QuoteIngestRequest> captor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captor.capture());
    assertThat(captor.getValue().getSourceType()).isEqualTo("EXCEL");
    assertThat(captor.getValue().getExternalFormNo()).isEqualTo("OA-T6-IDEMPOTENT");
    assertThat(captor.getValue().getVersion()).isEqualTo("1");
    assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("EXCEL:OA-T6-IDEMPOTENT:1");
  }

  @Test
  void fiSr005WithoutBusinessTypeWarnsAndCanCommitAsPending() {
    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    ingestResponse.setClassificationStatus("PENDING");
    ingestResponse.setQuoteScenario("UNKNOWN");
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);

    InputStream file =
        excel(
            List.of(header("OA-T6-PENDING", "FI-SR-005")),
            List.of(newItem("OA-T6-PENDING", 1, "", "MAT-PENDING", "SHF-PENDING")));
    QuoteExcelImportPreviewResponse preview = service.preview(file, "t6.xlsx");

    assertThat(preview.isValid()).isTrue();
    assertThat(preview.getForms().get(0).isClassificationPending()).isTrue();
    assertThat(preview.getWarnings()).extracting("code").contains("FI_SR_005_BUSINESS_TYPE_EMPTY");

    QuoteExcelImportCommitResponse commit =
        service.commit(
            excel(
                List.of(header("OA-T6-PENDING", "FI-SR-005")),
                List.of(newItem("OA-T6-PENDING", 1, "", "MAT-PENDING", "SHF-PENDING"))),
            "t6.xlsx");
    assertThat(commit.isCommitted()).isTrue();
    assertThat(commit.getResults().get(0).getClassificationStatus()).isEqualTo("PENDING");
  }

  private InputStream excel(List<List<String>> headers, List<List<String>> items) {
    return excel(headers, items, List.of());
  }

  private InputStream excel(List<List<String>> headers, List<List<String>> items, List<List<String>> fees) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      Sheet headerSheet = workbook.createSheet("报价单表头");
      writeRows(
          headerSheet,
          List.of(
              List.of(
                  "报价单号",
                  "来源类型",
                  "流程编号",
                  "申请日期",
                  "客户名称",
                  "产品属性",
                  "铜基价",
                  "锌基价",
                  "铝基价",
                  "不锈钢基价",
                  "黄金基价")));
      appendRows(headerSheet, headers);

      Sheet itemSheet = workbook.createSheet("产品明细");
      writeRows(
          itemSheet,
          List.of(List.of("报价单号", "行号", "业务类型", "产品名称", "料号", "三花型号", "规格", "三花配套量")));
      appendRows(itemSheet, items);

      Sheet feeSheet = workbook.createSheet("额外费用");
      writeRows(feeSheet, List.of(List.of("报价单号", "行号", "费用编码", "费用名称", "费用分类", "金额", "单位")));
      appendRows(feeSheet, fees);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private List<String> header(String oaNo, String processCode) {
    return List.of(
        oaNo,
        "EXCEL",
        processCode,
        "2026-05-11",
        "示例客户",
        "批量品",
        "90000",
        "21684",
        "23386",
        "17200",
        "");
  }

  private List<String> item(String oaNo, int seq) {
    return newItem(oaNo, seq, "批量品", "MAT-" + seq, "SHF-" + seq);
  }

  private List<String> newItem(String oaNo, int seq, String businessType, String materialNo, String sunlModel) {
    return List.of(
        oaNo,
        String.valueOf(seq),
        businessType,
        "测试产品" + seq,
        materialNo,
        sunlModel,
        "规格" + seq,
        "1");
  }

  private List<String> fee(
      String oaNo, int seq, String feeCode, String feeName, String feeCategory, String amount) {
    return List.of(oaNo, String.valueOf(seq), feeCode, feeName, feeCategory, amount, "元");
  }

  private void appendRows(Sheet sheet, List<List<String>> rows) {
    int start = sheet.getLastRowNum() + 1;
    for (int index = 0; index < rows.size(); index++) {
      writeRow(sheet.createRow(start + index), rows.get(index));
    }
  }

  private void writeRows(Sheet sheet, List<List<String>> rows) {
    for (int index = 0; index < rows.size(); index++) {
      writeRow(sheet.createRow(index), rows.get(index));
    }
  }

  private void writeRow(Row row, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      row.createCell(index).setCellValue(values.get(index));
    }
  }
}
