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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuotePdfImportServiceImplTest {
  private QuoteIngestService quoteIngestService;

  @BeforeEach
  void setUp() {
    quoteIngestService = mock(QuoteIngestService.class);
  }

  @Test
  void previewReturnsStructuredPdfParseError() {
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            (inputStream, fileName) -> {
              throw new QuotePdfParseException("PDF_TEXT_EMPTY", "PDF 文本为空，当前阶段只支持文本型 PDF");
            });

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream(new byte[0]), "blank.pdf");

    assertThat(response.isValid()).isFalse();
    assertThat(response.getFileName()).isEqualTo("blank.pdf");
    assertThat(response.getErrors()).extracting("code").containsExactly("PDF_TEXT_EMPTY");
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void commitDoesNotIngestWhenPreviewInvalid() {
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            (inputStream, fileName) -> {
              throw new QuotePdfParseException("PDF_PARSE_FAILED", "PDF 解析失败");
            });

    QuoteExcelImportCommitResponse response =
        service.commit(new ByteArrayInputStream("broken".getBytes()), "broken.pdf");

    assertThat(response.isCommitted()).isFalse();
    assertThat(response.getPreview().isValid()).isFalse();
    assertThat(response.getPreview().getErrors()).extracting("code").containsExactly("PDF_PARSE_FAILED");
    verify(quoteIngestService, never()).ingest(any());
  }

  @Test
  void previewReturnsUnsupportedTemplateForUnknownNonEmptyPdf() {
    QuotePdfDocument document = new QuotePdfDocument();
    document.setFileName("sample.pdf");
    document.setFullText("unknown sample");
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            (inputStream, fileName) -> document);

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream("pdf".getBytes()), "sample.pdf");

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").containsExactly("PDF_TEMPLATE_UNSUPPORTED");
    assertThat(response.getFormCount()).isZero();
    assertThat(response.getItemCount()).isZero();
  }

  @Test
  void previewBuildsTemplateSkeletonForKnownPdfAfterT3Resolution() {
    QuotePdfDocument document =
        document(
            "FI-SC-006.标准品批量品成本核算流程.pdf",
            List.of(
                "流程标题 FI-SC-006.标准品/批量品成本核算流程-何之美-2026-03-27",
                "紧急程度 正常 流程编号 FI-SC-006-20260327-037",
                "申请人 何之美 申请时间 2026-03-27 14:38",
                "申请部门 欧美业务管理部 申请处室 欧洲业务管理部"));
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            (inputStream, fileName) -> document);

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream("pdf".getBytes()), document.getFileName());

    assertThat(response.isValid()).isFalse();
    assertThat(response.getFormCount()).isOne();
    assertThat(response.getForms()).hasSize(1);
    assertThat(response.getForms().get(0).getOaNo()).isEqualTo("FI-SC-006-20260327-037");
    assertThat(response.getForms().get(0).getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("STANDARD_BATCH");
    assertThat(response.getForms().get(0).getHeaderSummary().getApplyDate()).isEqualTo("2026-03-27");
    assertThat(response.getForms().get(0).getHeaderSummary().getApplicantName()).isEqualTo("何之美");
    assertThat(response.getForms().get(0).getHeaderSummary().getApplicantDept()).isEqualTo("欧美业务管理部");
    assertThat(response.getForms().get(0).getHeaderSummary().getApplicantOffice()).isEqualTo("欧洲业务管理部");
    assertThat(response.getErrors()).extracting("code").containsExactly("ITEMS_REQUIRED");
  }

  @Test
  void desktopPdfSamplesResolveAllSixTemplateProcessCodesWhenAvailable() throws Exception {
    List<PdfSample> samples =
        List.of(
            new PdfSample(
                Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-020.成本核算联系单（板换科技-直销）.pdf"),
                "FI-SC-020",
                "DIRECT_SALE"),
            new PdfSample(
                Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-006.标准品批量品成本核算流程.pdf"),
                "FI-SC-006",
                "STANDARD_BATCH"),
            new PdfSample(
                Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-005.新品成本核算流程.pdf"),
                "FI-SC-005",
                "NEW_PRODUCT"),
            new PdfSample(Path.of("/Users/xiexicheng/Desktop/demo3/空白：新品.pdf"), "FI-SR-005", "NEW_PRODUCT"),
            new PdfSample(Path.of("/Users/xiexicheng/Desktop/demo3/空白：批量品.pdf"), "FI-SR-005", "MASS_PRODUCT"),
            new PdfSample(Path.of("/Users/xiexicheng/Desktop/demo3/空白：衍生品.pdf"), "FI-SR-005", "DERIVED_PRODUCT"));
    Assumptions.assumeTrue(
        samples.stream().allMatch(sample -> DesktopFixtureAccess.isReadable(sample.path())),
        "six readable desktop PDF samples are required for T4 all-template smoke test");

    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            new PdfBoxQuotePdfTextExtractor());

    for (PdfSample sample : samples) {
      try (InputStream inputStream = Files.newInputStream(sample.path())) {
        QuoteExcelImportPreviewResponse response =
            service.preview(inputStream, sample.path().getFileName().toString());

        assertThat(response.getForms()).as(sample.path().getFileName().toString()).hasSize(1);
        assertThat(response.getForms().get(0).getProcessCode()).isEqualTo(sample.processCode());
        if (sample.assertNormalizedScenario()) {
          assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo(sample.quoteScenario());
        }
      }
    }
  }

  @Test
  void realFiSc006SanhuaPdfPreviewParsesHeaderNumbersAndDenseItemWhenAvailable() throws Exception {
    Path file = Path.of("/Users/xiexicheng/Desktop/demo4/打印 - SANHUA三花.pdf");
    Assumptions.assumeTrue(
        DesktopFixtureAccess.isReadable(file),
        "real FI-SC-006 SANHUA desktop PDF sample must exist and be readable");
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            new PdfBoxQuotePdfTextExtractor());

    QuoteExcelImportPreviewResponse response;
    try (InputStream inputStream = Files.newInputStream(file)) {
      response = service.preview(inputStream, file.getFileName().toString());
    }

    assertThat(response.getErrors())
        .extracting("code")
        .doesNotContain("NUMBER_INVALID", "ITEMS_REQUIRED", "PRODUCT_KEY_REQUIRED");
    assertThat(response.getForms()).hasSize(1);
    assertThat(response.getForms().get(0).getItems()).hasSize(2);
    assertThat(response.getForms().get(0).getHeaderSummary().getOaNo()).isEqualTo("FI-SC-006-20260529-038");
    assertThat(response.getForms().get(0).getHeaderSummary().getCopperPrice()).isEqualByComparingTo("100000.00");
    assertThat(response.getForms().get(0).getHeaderSummary().getZincPrice()).isEqualByComparingTo("23714.00");
    assertThat(response.getForms().get(0).getHeaderSummary().getSus316lPrice()).isEqualByComparingTo("32500.00");
    assertThat(response.getForms().get(0).getHeaderSummary().getSus304Price()).isEqualByComparingTo("20000.00");
    assertThat(response.getForms().get(0).getItems())
        .extracting("materialNo")
        .contains("1001900000237", "1001900000261");
  }

  @Test
  void realFiSr005SanhuaPdfPreviewParsesBlankOtherMaterialAndDenseItemsWhenAvailable() throws Exception {
    Path file = Path.of("/Users/xiexicheng/Desktop/demo7/FI-SR-005-20260318-0397.pdf");
    Assumptions.assumeTrue(
        DesktopFixtureAccess.isReadable(file),
        "real FI-SR-005 SANHUA desktop PDF sample must exist and be readable");
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestService,
            new PdfBoxQuotePdfTextExtractor());

    QuoteExcelImportPreviewResponse response;
    try (InputStream inputStream = Files.newInputStream(file)) {
      response = service.preview(inputStream, file.getFileName().toString());
    }

    assertThat(response.getErrors())
        .as(errorSummary(response))
        .extracting("code")
        .doesNotContain("NUMBER_INVALID", "ITEMS_REQUIRED");
    assertThat(response.getForms()).hasSize(1);
    assertThat(response.getForms().get(0).getOaNo()).isEqualTo("FI-SR-005-20260318-0397");
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("DERIVED_PRODUCT");
    assertThat(response.getForms().get(0).getHeaderSummary().getRemark()).isNull();
    assertThat(response.getForms().get(0).getItems()).hasSize(3);
    assertThat(response.getForms().get(0).getItems())
        .extracting("sunlModel")
        .containsExactly("S12BH-12L-18", "S12BH-16L-34", "S12BH-30L-19");
  }

  @Test
  void realFiSr005NovemberPrintPreviewAndCommitKeepAllThreeProductsWhenAvailable() throws Exception {
    Path file = Path.of("/Users/xiexicheng/Desktop/fee/打印 - SANHUA三花1103.pdf");
    Assumptions.assumeTrue(DesktopFixtureAccess.isReadable(file), "November FI-SR-005 PDF must be readable");
    QuotePdfImportServiceImpl service = new QuotePdfImportServiceImpl(
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
        quoteIngestService, new PdfBoxQuotePdfTextExtractor());

    try (InputStream inputStream = Files.newInputStream(file)) {
      QuoteExcelImportPreviewResponse response = service.preview(inputStream, file.getFileName().toString());
      assertThat(response.isValid()).as(errorSummary(response)).isTrue();
      assertThat(response.getItemCount()).isEqualTo(3);
      assertThat(response.getForms()).hasSize(1);
      var form = response.getForms().get(0);
      assertThat(form.getOaNo()).isEqualTo("FI-SR-005-20261102-0009");
      assertThat(form.getQuoteScenario()).isEqualTo("MASS_PRODUCT");
      assertThat(form.getItems()).extracting("materialNo")
          .containsExactly("1064100050088", "1064100050090", "1064100050089");
      assertThat(form.getItems()).extracting("sunlModel")
          .containsExactly("MPS5AH343", "MPS4AH014", "MPS4AH015");
      assertThat(form.getItems()).extracting("productName")
          .containsExactly("膜片式压力控制器", "膜片式压力控制器", "膜片式压力控制器");
      assertThat(form.getItems()).extracting("validMonth").containsExactly(3, 3, 3);
      assertThat(form.getItems().get(0).getSupportQty()).isEqualByComparingTo("0.1070");
      assertThat(form.getItems().get(1).getSupportQty()).isEqualByComparingTo("0.1070");
      assertThat(form.getItems().get(2).getSupportQty()).isEqualByComparingTo("0.1890");
      assertThat(form.getItems().get(0).getTotalWithShip()).isEqualByComparingTo("13.571");
      assertThat(form.getItems().get(1).getTotalWithShip()).isEqualByComparingTo("12.121");
      assertThat(form.getItems().get(2).getTotalWithShip()).isEqualByComparingTo("11.713");
      assertThat(form.getItems().get(0).getTotalNoShip()).isEqualByComparingTo("13.197");
      assertThat(form.getItems().get(1).getTotalNoShip()).isEqualByComparingTo("11.747");
      assertThat(form.getItems().get(2).getTotalNoShip()).isEqualByComparingTo("11.339");
    }
    verify(quoteIngestService, never()).ingest(any());
    when(quoteIngestService.ingest(any())).thenReturn(new QuoteIngestResponse());
    try (InputStream inputStream = Files.newInputStream(file)) {
      assertThat(service.commit(inputStream, file.getFileName().toString()).isCommitted()).isTrue();
    }
    var captured = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(captured.capture());
    assertThat(captured.getValue().getItems()).extracting("materialNo")
        .containsExactly("1064100050088", "1064100050090", "1064100050089");
    assertThat(captured.getValue().getItems()).extracting("shippingFee")
        .containsExactly("0.3738", "0.3738", "0.3738");
  }

  private QuotePdfDocument document(String fileName, List<String> lines) {
    QuotePdfDocument document = new QuotePdfDocument();
    document.setFileName(fileName);
    document.setFullText(String.join("\n", lines));
    QuotePdfPage page = new QuotePdfPage();
    page.setPageIndex(0);
    int index = 0;
    for (String text : lines) {
      QuotePdfLine line = new QuotePdfLine();
      line.setPageIndex(0);
      line.setText(text);
      line.setY(++index);
      page.getLines().add(line);
    }
    document.setPages(List.of(page));
    return document;
  }

  private String errorSummary(QuoteExcelImportPreviewResponse response) {
    return response.getErrors().stream()
        .map(error -> error.getFieldPath() + ":" + error.getCode() + ":" + error.getMessage())
        .collect(Collectors.joining("; "));
  }

  private record PdfSample(Path path, String processCode, String quoteScenario) {
    boolean assertNormalizedScenario() {
      return !"FI-SR-005".equals(processCode);
    }
  }
}
