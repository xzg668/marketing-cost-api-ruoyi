package com.sanhua.marketingcost.service.ingest;

import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.cell;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.context;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.document;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.request;
import static com.sanhua.marketingcost.service.ingest.QuoteOaPdfDetailTableParserTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import org.junit.jupiter.api.Test;

class QuoteOaPdfDetailTableParserFiSc006Test {
  private final QuoteOaPdfDetailTableParser parser = new QuoteOaPdfDetailTableParser();

  @Test
  void parsesCoordinateColumnsAndRoutesItemFees() {
    QuotePdfDocument document =
        document(
            "FI-SC-006.标准品批量品成本核算流程.pdf",
            row(cell(">>明细表", 30)),
            row(
                cell("序号", 30),
                cell("产品名称", 80),
                cell("料号", 180),
                cell("三花型号", 285),
                cell("业务类型", 390),
                cell("年用量", 500),
                cell("含运费总价", 600),
                cell("工装夹具费", 720),
                cell("模具费", 840)),
            row(
                cell("1", 30),
                cell("阀组件", 80),
                cell("1079900000536", 180),
                cell("SH-90", 285),
                cell("批量品", 390),
                cell("1200", 500),
                cell("19.85", 600),
                cell("3000", 720),
                cell("4500", 840)),
            row(cell(">>辅助信息", 30)));
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);

    parser.parse(context(QuoteExcelTemplateType.FI_SC_006, document), request);

    assertThat(request.getItems()).hasSize(1);
    QuoteIngestItemRequest item = request.getItems().get(0);
    assertThat(item.getSeq()).isEqualTo(1);
    assertThat(item.getProductName()).isEqualTo("阀组件");
    assertThat(item.getMaterialNo()).isEqualTo("1079900000536");
    assertThat(item.getSunlModel()).isEqualTo("SH-90");
    assertThat(item.getBusinessType()).isEqualTo("批量品");
    assertThat(item.getAnnualVolume()).isEqualTo("1200");
    assertThat(item.getTotalWithShip()).isEqualTo("19.85");
    assertThat(item.getExtraFees()).hasSize(2);
    assertThat(item.getExtraFees()).extracting("feeCode").containsExactly("fixtureTotalAmount", "moldTotalAmount");
    assertThat(item.getExtraFees()).extracting("amount").containsExactly("3000", "4500");
    assertThat(item.getExtraFees().get(0).getSourceFieldPath()).contains("PDF:page:1:line:3");
  }
}
