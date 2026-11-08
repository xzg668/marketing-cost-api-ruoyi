package com.sanhua.marketingcost.service.ingest;

import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import com.sanhua.marketingcost.service.pricing.monthly.AnnualUsageInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuoteOaPdfAnnualVolumeUnitTest {
  @Test void suppliedFilesPassTheImportPreviewAndKeepUnitsInTheIngestRequest() throws Exception {
    for (String filename : new String[] {"FI-SC-006-20261104-058.pdf", "FI-SC-020-20261020-1.pdf"}) {
      var path = java.nio.file.Path.of("/Users/xiexicheng/Desktop", filename);
      org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isReadable(path));
      var ingest = org.mockito.Mockito.mock(QuoteIngestService.class);
      var service = new QuotePdfImportServiceImpl(
          new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
          ingest, new PdfBoxQuotePdfTextExtractor());
      try (var input = java.nio.file.Files.newInputStream(path)) {
        var response = service.commit(input, filename);
        assertThat(response.getPreview().getErrors()).as(filename + ": " + response.getPreview().getErrors().stream().map(e -> e.getFieldPath() + ":" + e.getMessage()).toList()).isEmpty();
        assertThat(response.isCommitted()).isTrue();
      }
      var request = org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest.class);
      org.mockito.Mockito.verify(ingest).ingest(request.capture());
      boolean wan = filename.startsWith("FI-SC-006");
      assertThat(request.getValue().getItems()).hasSize(wan ? 3 : 1);
      for (var item : request.getValue().getItems())
        assertUnit(item, wan ? "TEN_THOUSAND_PIECES" : "PIECES", wan ? "1000" : "2000");
    }
  }

  @Test void suppliedFiSc006PdfPreservesAllThreeWanZhiDemands() throws Exception {
    var items = parseFile("FI-SC-006-20261104-058.pdf", QuoteExcelTemplateType.FI_SC_006);
    assertThat(items).extracting(QuoteIngestItemRequest::getMaterialNo)
        .containsExactly("1053900030711", "1053900030530", "1053900030710");
    for (var item : items) assertUnit(item, "TEN_THOUSAND_PIECES", "1000");
  }

  @Test void suppliedFiSc020PdfPreservesTheExplicitPieceUnit() throws Exception {
    var items = parseFile("FI-SC-020-20261020-1.pdf", QuoteExcelTemplateType.FI_SC_020);
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getMaterialNo()).isEqualTo("1053900030362");
    assertUnit(items.get(0), "PIECES", "2000");
  }

  private java.util.List<QuoteIngestItemRequest> parseFile(String filename, QuoteExcelTemplateType type) throws Exception {
    var path = java.nio.file.Path.of("/Users/xiexicheng/Desktop", filename);
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isReadable(path));
    try (var input = java.nio.file.Files.newInputStream(path)) {
      var doc = new PdfBoxQuotePdfTextExtractor().extract(input, filename);
      var request = request(type);
      new QuoteOaPdfDetailTableParser().parse(context(type, doc), request);
      return request.getItems();
    }
  }

  @Test void keepsWanZhiWhenHeaderContainsSpaces() {
    var item = parse("预计年用量 [ 万 只 ]");
    assertUnit(item, "TEN_THOUSAND_PIECES", "2000");
  }

  @Test void keepsWanZhiWhenEveryHeaderFragmentWrapsOntoAnotherLine() {
    var item = parse("预", "计", "年", "用", "量", "[ 万", "只 ]");
    assertUnit(item, "TEN_THOUSAND_PIECES", "2000");
  }

  @Test void explicitPiecesRemainPiecesEvenForFractionalInput() {
    assertUnit(parse("预计年用量", "[只]"), "PIECES", "0.2");
  }

  @Test void doesNotUseTheShippingColumnUnitWhenAnnualHeaderHasNoUnit() {
    var item = parse("预计年用量");
    assertThat(item.getExtraFields()).noneMatch(f -> "ANNUAL_VOLUME_UNIT".equals(f.getFieldCode()));
  }

  private void assertUnit(QuoteIngestItemRequest item, String unit, String pieces) {
    var fields = item.getExtraFields().stream()
        .filter(f -> "ANNUAL_VOLUME_UNIT".equals(f.getFieldCode())).toList();
    assertThat(fields).hasSize(1);
    assertThat(fields.get(0).getFieldValue()).isEqualTo(unit);
    assertThat(fields.get(0).getSourceFieldPath()).contains("PDF:page:1:");
    assertThat(new AnnualUsageInput(new BigDecimal(item.getAnnualVolume()), unit).piecesIfKnown())
        .isEqualByComparingTo(pieces);
  }

  private QuoteIngestItemRequest parse(String... annualHeader) {
    var rows = new java.util.ArrayList<QuoteOaPdfDetailTableParserTestSupport.Row>();
    rows.add(row(cell(">>明细表", 30)));
    rows.add(row(cell(annualHeader.length > 2 ? "序" : "序号", 30), cell("产品名称", 80), cell("料号", 180),
        cell("三花型号", 285), cell(annualHeader[0], 500), cell("运输费[元/万只]", 620)));
    for (int i = 1; i < annualHeader.length; i++) rows.add(row(cell(i == 1 && annualHeader.length > 2 ? "号" : "", 30), cell(annualHeader[i], 500)));
    rows.add(row(cell("1", 30), cell("视液镜", 80), cell("1109900000190", 180),
        cell("SYJ10H51", 285), cell("0.200", 500), cell("0", 620)));
    rows.add(row(cell(">>辅助信息", 30)));
    var doc = document("FI-SC-006-20261106-063.pdf", rows.toArray(QuoteOaPdfDetailTableParserTestSupport.Row[]::new));
    var request = request(QuoteExcelTemplateType.FI_SC_006);
    new QuoteOaPdfDetailTableParser().parse(context(QuoteExcelTemplateType.FI_SC_006, doc), request);
    assertThat(request.getItems()).hasSize(1);
    assertThat(request.getItems().get(0).getAnnualVolume()).isEqualTo("0.200");
    return request.getItems().get(0);
  }
}
