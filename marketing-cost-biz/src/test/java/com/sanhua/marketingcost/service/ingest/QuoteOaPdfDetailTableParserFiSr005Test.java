package com.sanhua.marketingcost.service.ingest;

import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.cell;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.context;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.document;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.request;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteOaPdfDetailTableParserFiSr005Test {
  private final QuoteOaPdfDetailTableParser parser = new QuoteOaPdfDetailTableParser();

  @Test
  void fillsDefaultBusinessTypeForAllFiSr005Templates() {
    List<TemplateCase> cases =
        List.of(
            new TemplateCase(QuoteExcelTemplateType.FI_SR_005_NEW, "新品"),
            new TemplateCase(QuoteExcelTemplateType.FI_SR_005_MASS, "批量品"),
            new TemplateCase(QuoteExcelTemplateType.FI_SR_005_DERIVED, "衍生品"));

    for (TemplateCase templateCase : cases) {
      QuoteIngestRequest request = request(templateCase.templateType());

      parser.parse(context(templateCase.templateType(), detailDocument(templateCase.templateType().getCode() + ".pdf")), request);

      assertThat(request.getItems()).as(templateCase.templateType().getCode()).hasSize(1);
      assertThat(request.getItems().get(0).getBusinessType()).isEqualTo(templateCase.businessType());
    }
  }

  @Test
  void servicePreviewUsesParsedBusinessTypeToConfirmFiSr005Scenario() {
    QuotePdfDocument document =
        document(
            "空白：批量品.pdf",
            row(cell("FI-SR-005.成本核算联系单（批量品）", 30)),
            row(cell("流程编号", 30), cell("FI-SR-005-20260530-001", 120)),
            row(cell("申请人", 30), cell("张三", 120), cell("申请日期", 260), cell("2026-05-30", 380)),
            row(cell(">>业务信息", 30)),
            row(cell("业务类型", 30), cell("批量品", 120)),
            row(cell(">>明细表", 30)),
            row(cell("序号", 30), cell("料号", 120), cell("三花型号", 260), cell("年用量", 390)),
            row(cell("1", 30), cell("SR-MAT-001", 120), cell("SR-001", 260), cell("600", 390)),
            row(cell(">>辅助信息", 30)));
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            ignored -> null,
            (inputStream, fileName) -> document);

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream("pdf".getBytes()), document.getFileName());

    assertThat(response.isValid()).isTrue();
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("MASS_PRODUCT");
    assertThat(response.getForms().get(0).getItems().get(0).getBusinessType()).isEqualTo("批量品");
  }

  private QuotePdfDocument detailDocument(String fileName) {
    return document(
        fileName,
        row(cell(">>明细表", 30)),
        row(cell("序号", 30), cell("料号", 120), cell("三花型号", 260), cell("年用量", 390)),
        row(cell("1", 30), cell("SR-MAT-001", 120), cell("SR-001", 260), cell("500", 390)),
        row(cell(">>辅助信息", 30)));
  }

  private record TemplateCase(QuoteExcelTemplateType templateType, String businessType) {}
}
