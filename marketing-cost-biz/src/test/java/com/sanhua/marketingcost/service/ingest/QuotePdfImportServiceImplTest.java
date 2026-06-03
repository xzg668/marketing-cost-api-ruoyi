package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        samples.stream().allMatch(sample -> Files.exists(sample.path())),
        "six desktop PDF samples are required for T4 all-template smoke test");

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
    Assumptions.assumeTrue(Files.exists(file), "real FI-SC-006 SANHUA desktop PDF sample is required");
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

  private record PdfSample(Path path, String processCode, String quoteScenario) {
    boolean assertNormalizedScenario() {
      return !"FI-SR-005".equals(processCode);
    }
  }
}
