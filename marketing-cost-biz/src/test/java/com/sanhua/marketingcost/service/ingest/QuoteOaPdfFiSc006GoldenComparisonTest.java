package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteOaPdfFiSc006GoldenComparisonTest {
  private static final Path FI_SC_006_EXCEL =
      Path.of("/Users/xiexicheng/Desktop/demo3/报价单导入模板_02_FI-SC-006_标准品批量品.xlsx");
  private static final Path FI_SC_006_PDF =
      Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-006.标准品批量品成本核算流程.pdf");

  private final QuoteIngestGoldenSnapshotSupport snapshots = new QuoteIngestGoldenSnapshotSupport();
  private QuoteExcelImportServiceImpl excelService;
  private QuotePdfImportServiceImpl pdfService;

  @BeforeEach
  void setUp() {
    QuoteNormalizeService normalizeService =
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());
    QuoteIngestService quoteIngestService = mock(QuoteIngestService.class);
    excelService = new QuoteExcelImportServiceImpl(normalizeService, quoteIngestService);
    pdfService = new QuotePdfImportServiceImpl(normalizeService, quoteIngestService, new PdfBoxQuotePdfTextExtractor());
  }

  @Test
  void fiSc006PdfMatchesExcelGoldenWhenSameBusinessDataOtherwiseKeepsSmokeCoverage() throws Exception {
    Assumptions.assumeTrue(Files.exists(FI_SC_006_EXCEL), "FI-SC-006 desktop Excel golden sample is required");
    Assumptions.assumeTrue(Files.exists(FI_SC_006_PDF), "FI-SC-006 desktop PDF golden sample is required");

    QuoteExcelImportPreviewResponse excel = previewExcel();
    QuoteExcelImportPreviewResponse pdf = previewPdf();

    assertExcelGoldenSample(excel);
    assertPdfStructureSmoke(pdf);

    JsonNode excelSnapshot = snapshots.previewBusinessSnapshot(excel);
    JsonNode pdfSnapshot = snapshots.previewBusinessSnapshot(pdf);
    if (!snapshots.representsSameFiSc006BusinessData(excel, pdf)) {
      assertThat(pdfSnapshot)
          .as("当前 PDF 与 Excel 不是同一份业务数据时，只执行结构 smoke，不执行强对账")
          .isNotEqualTo(excelSnapshot);
      return;
    }

    assertThat(pdfSnapshot).isEqualTo(excelSnapshot);
  }

  private QuoteExcelImportPreviewResponse previewExcel() throws Exception {
    try (InputStream inputStream = Files.newInputStream(FI_SC_006_EXCEL)) {
      return excelService.preview(inputStream, FI_SC_006_EXCEL.getFileName().toString());
    }
  }

  private QuoteExcelImportPreviewResponse previewPdf() throws Exception {
    try (InputStream inputStream = Files.newInputStream(FI_SC_006_PDF)) {
      return pdfService.preview(inputStream, FI_SC_006_PDF.getFileName().toString());
    }
  }

  private void assertExcelGoldenSample(QuoteExcelImportPreviewResponse excel) {
    assertThat(excel.isValid()).isTrue();
    assertThat(excel.getForms()).hasSize(1);
    QuoteIngestPreviewResponse form = excel.getForms().get(0);
    assertThat(form.getOaNo()).isEqualTo("FI-SC-006-20260327-037");
    assertThat(form.getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(form.getQuoteScenario()).isEqualTo("STANDARD_BATCH");
    assertThat(form.getHeaderSummary().getApplicantName()).isEqualTo("何之美");
    assertThat(form.getItems()).hasSize(1);
    assertThat(form.getItems().get(0).getMaterialNo()).isEqualTo("1079900000536");
  }

  private void assertPdfStructureSmoke(QuoteExcelImportPreviewResponse pdf) {
    assertThat(pdf.getForms()).hasSize(1);
    QuoteIngestPreviewResponse form = pdf.getForms().get(0);
    assertThat(form.getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(form.getQuoteScenario()).isEqualTo("STANDARD_BATCH");
    assertThat(form.getHeaderSummary()).isNotNull();
    assertThat(form.getHeaderSummary().getProcessName()).isEqualTo("标准品/批量品成本核算流程");
  }
}
