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
            org.mockito.Mockito.mock(QuoteIngestService.class),
            (inputStream, fileName) -> document);

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream("pdf".getBytes()), document.getFileName());

    assertThat(response.isValid()).isTrue();
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getForms().get(0).getQuoteScenario()).isEqualTo("MASS_PRODUCT");
    assertThat(response.getForms().get(0).getItems().get(0).getBusinessType()).isEqualTo("批量品");
  }

  @Test
  void parsesDenseSplitHeaderRowsForFiSr005() {
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SR_005_DERIVED);

    parser.parse(
        context(
            QuoteExcelTemplateType.FI_SR_005_DERIVED,
            document(
                "FI-SR-005-20260318-0397.pdf",
                row(cell(">>财务部", 30)),
                row(
                    cell("序", 56),
                    cell("产品名称", 70),
                    cell("客户图号", 104),
                    cell("料号", 137),
                    cell("型号规格", 174),
                    cell("新品运输", 207),
                    cell("预计年用", 240),
                    cell("含运输费", 305),
                    cell("新品不含", 336),
                    cell("新品价格", 495)),
                row(
                    cell("号", 56),
                    cell("费[元/只]", 207),
                    cell("量[万只]", 240),
                    cell("总成本", 305),
                    cell("运输费总", 336),
                    cell("有效期", 495)),
                row(
                    cell("1", 56),
                    cell("板式热交换器", 70),
                    cell("/", 137),
                    cell("S12BH-12L-18", 174),
                    cell("5.2910", 207),
                    cell("0.400", 240),
                    cell("196.278", 305),
                    cell("190.987", 336),
                    cell("1个月", 495)),
                row(cell("", 30)),
                row(
                    cell("2", 56),
                    cell("板式热交换器", 70),
                    cell("/", 137),
                    cell("S12BH-16L-34", 174),
                    cell("5.2910", 207),
                    cell("0.400", 240),
                    cell("204.202", 305),
                    cell("198.911", 336),
                    cell("1个月", 495)),
                row(cell(">>辅助信息", 30)))),
        request);

    assertThat(request.getItems()).hasSize(2);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getSunlModel)
        .containsExactly("S12BH-12L-18", "S12BH-16L-34");
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getMaterialNo)
        .containsExactly(null, null);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getBusinessType)
        .containsExactly("衍生品", "衍生品");
  }

  private QuotePdfDocument detailDocument(String fileName) {
    return document(
        fileName,
        row(cell(">>明细表", 30)),
        row(cell("序号", 30), cell("料号", 120), cell("三花型号", 260), cell("年用量", 390)),
        row(cell("1", 30), cell("SR-MAT-001", 120), cell("SR-001", 260), cell("500", 390)),
        row(cell(">>辅助信息", 30)));
  }

  @Test
  void parsesUnlabelledTableWithHeadersStartingAboveTheFirstCompleteLabels() {
    // Like the browser printout: no “明细表” anchor, and 序号/三花型号 wrap above 料号/规格/备注.
    QuotePdfDocument document = document(
        "wrapped.pdf",
        row(cell("序", 30), cell("产品名", 90), cell("客户图", 220), cell("三花型", 460),
            cell("运输费", 740), cell("三花配", 860), cell("含运输", 980),
            cell("不含运", 1100), cell("成本有", 1220)),
        row(cell("料号", 340), cell("规格", 600), cell("备注", 1340),
            cell("[元/只]", 740), cell("套量[万", 860), cell("费总成", 980), cell("输费总", 1100)),
        row(cell("号", 30), cell("称", 90), cell("号", 220), cell("号", 460),
            cell("只]", 860), cell("本（不含税）", 980), cell("成本（不含税）", 1100), cell("效期", 1220)),
        row(cell("1", 30), cell("压力控制器", 90), cell("25100008", 220),
            cell("1064100050088", 340), cell("MPS5AH343", 460), cell("MPS5AH343", 600),
            cell("0.3738", 740), cell("0.1070", 860), cell("13.571", 980), cell("13.197", 1100), cell("3个月", 1220)),
        row(cell("", 30)),
        row(cell("2", 30), cell("压力控制器", 90), cell("25100009", 220),
            cell("1064100050090", 340), cell("MPS4AH014", 460), cell("MPS4AH014", 600)),
        row(cell(">>技术员", 30)));
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SR_005_MASS);

    parser.parse(context(QuoteExcelTemplateType.FI_SR_005_MASS, document), request);

    assertThat(request.getItems()).hasSize(2);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getMaterialNo)
        .containsExactly("1064100050088", "1064100050090");
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getSunlModel)
        .containsExactly("MPS5AH343", "MPS4AH014");
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getProductName)
        .containsExactly("压力控制器", "压力控制器");
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getBusinessType)
        .containsExactly("批量品", "批量品");
    var first = request.getItems().get(0);
    assertThat(first.getShippingFee()).isEqualTo("0.3738");
    assertThat(first.getSupportQty()).isEqualTo("0.1070");
    assertThat(first.getTotalWithShip()).isEqualTo("13.571");
    assertThat(first.getTotalNoShip()).isEqualTo("13.197");
    assertThat(first.getValidMonth()).isEqualTo("3");
  }

  private record TemplateCase(QuoteExcelTemplateType templateType, String businessType) {}
}
