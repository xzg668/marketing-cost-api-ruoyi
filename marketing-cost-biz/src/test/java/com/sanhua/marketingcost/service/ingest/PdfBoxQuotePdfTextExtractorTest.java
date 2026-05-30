package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PdfBoxQuotePdfTextExtractorTest {
  private static final Path FI_SC_006_PDF =
      Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-006.标准品批量品成本核算流程.pdf");

  private final PdfBoxQuotePdfTextExtractor extractor = new PdfBoxQuotePdfTextExtractor();

  @Test
  void extractsFullTextLinesAndTokens() {
    QuotePdfDocument document =
        extractor.extract(
            pdfWithLines(List.of("FI-SC-006 standard batch", "productAttr mass", "annualVolume 10")),
            "sample.pdf");

    assertThat(document.getFileName()).isEqualTo("sample.pdf");
    assertThat(document.getFullText()).contains("FI-SC-006 standard batch", "productAttr mass", "annualVolume 10");
    assertThat(document.getPages()).hasSize(1);
    QuotePdfPage page = document.getPages().get(0);
    assertThat(page.getPageIndex()).isZero();
    assertThat(page.getLines()).hasSizeGreaterThanOrEqualTo(3);
    assertThat(page.getLines()).extracting(QuotePdfLine::getText).anyMatch(text -> text.contains("productAttr"));
    assertThat(page.getTokens()).extracting(QuotePdfToken::getText).contains("FI-SC-006", "productAttr", "10");
    assertThat(page.getTokens()).allSatisfy(token -> assertThat(token.getWidth()).isGreaterThan(0f));
  }

  @Test
  void emptyTextPdfReturnsPdfTextEmptyError() {
    assertThatThrownBy(() -> extractor.extract(blankPdf(), "blank.pdf"))
        .isInstanceOf(QuotePdfParseException.class)
        .extracting("code")
        .isEqualTo("PDF_TEXT_EMPTY");
  }

  @Test
  void invalidPdfReturnsPdfParseFailedError() {
    assertThatThrownBy(
            () -> extractor.extract(new ByteArrayInputStream("not a pdf".getBytes()), "broken.pdf"))
        .isInstanceOf(QuotePdfParseException.class)
        .extracting("code")
        .isEqualTo("PDF_PARSE_FAILED");
  }

  @Test
  void extractsDesktopFiSc006StructureSampleWhenAvailable() throws Exception {
    Assumptions.assumeTrue(Files.exists(FI_SC_006_PDF), "FI-SC-006 desktop PDF sample is required for T2");

    try (InputStream inputStream = Files.newInputStream(FI_SC_006_PDF)) {
      QuotePdfDocument document = extractor.extract(inputStream, FI_SC_006_PDF.getFileName().toString());

      assertThat(document.getFullText()).contains("FI-SC-006");
      assertThat(document.getPages()).hasSizeGreaterThanOrEqualTo(1);
      assertThat(document.getPages().stream().flatMap(page -> page.getLines().stream()).map(QuotePdfLine::getText))
          .anyMatch(text -> text.contains("产品属性"));
    }
  }

  private InputStream pdfWithLines(List<String> lines) {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.beginText();
        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        contentStream.newLineAtOffset(72, 720);
        for (String line : lines) {
          contentStream.showText(line);
          contentStream.newLineAtOffset(0, -18);
        }
        contentStream.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to create test PDF", ex);
    }
  }

  private InputStream blankPdf() {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return new ByteArrayInputStream(out.toByteArray());
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to create blank test PDF", ex);
    }
  }
}
