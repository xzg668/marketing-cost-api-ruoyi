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
import org.junit.jupiter.api.Test;

class QuoteOaPdfDetailTableParserFiSc005Test {
  private final QuoteOaPdfDetailTableParser parser = new QuoteOaPdfDetailTableParser();

  @Test
  void appendsContinuationLineUntilNextSeq() {
    QuotePdfDocument document =
        document(
            "FI-SC-005.新品成本核算流程.pdf",
            row(cell(">>明细表", 30)),
            row(
                cell("序号", 30),
                cell("产品名称", 90),
                cell("料号", 240),
                cell("规格", 380),
                cell("有效日期", 520),
                cell("认证费", 660)),
            row(
                cell("1", 30),
                cell("新品泵", 90),
                cell("NP-001", 240),
                cell("A-1", 380),
                cell("2026-12-31", 520)),
            row(cell("低温版", 90), cell("补充规格", 380), cell("800", 660)),
            row(cell("2", 30), cell("新品阀", 90), cell("NP-002", 240), cell("B-1", 380)),
            row(cell(">>辅助信息", 30)));
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_005);

    parser.parse(context(QuoteExcelTemplateType.FI_SC_005, document), request);

    assertThat(request.getItems()).hasSize(2);
    QuoteIngestItemRequest first = request.getItems().get(0);
    assertThat(first.getProductName()).isEqualTo("新品泵 低温版");
    assertThat(first.getSpec()).isEqualTo("A-1 补充规格");
    assertThat(first.getValidDate()).isEqualTo("2026-12-31");
    assertThat(first.getBusinessType()).isEqualTo("新品");
    assertThat(first.getExtraFees()).extracting("feeCode").containsExactly("certificationFee");
    assertThat(first.getExtraFees()).extracting("amount").containsExactly("800");
    assertThat(request.getItems().get(1).getMaterialNo()).isEqualTo("NP-002");
  }

  @Test
  void keepsSeqOnlyRowSoExistingValidatorReturnsProductKeyRequired() {
    QuotePdfDocument document =
        document(
            "FI-SC-005.新品成本核算流程.pdf",
            row(cell("FI-SC-005.新品成本核算流程", 30)),
            row(cell("流程编号", 30), cell("FI-SC-005-20260530-001", 120)),
            row(cell("申请人", 30), cell("张三", 120), cell("申请日期", 260), cell("2026-05-30", 380)),
            row(cell(">>明细表", 30)),
            row(cell("序号", 30), cell("料号", 140), cell("三花型号", 280)),
            row(cell("1", 30)),
            row(cell(">>辅助信息", 30)));
    QuotePdfImportServiceImpl service =
        new QuotePdfImportServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            ignored -> null,
            (inputStream, fileName) -> document);

    QuoteExcelImportPreviewResponse response =
        service.preview(new ByteArrayInputStream("pdf".getBytes()), document.getFileName());

    assertThat(response.isValid()).isFalse();
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getErrors()).extracting("code").contains("PRODUCT_KEY_REQUIRED");
  }
}
