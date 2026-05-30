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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class QuoteOaPdfDetailTableParserFiSc020Test {
  private final QuoteOaPdfDetailTableParser parser = new QuoteOaPdfDetailTableParser();

  @Test
  void parsesDirectSaleDetailColumns() {
    QuotePdfDocument document =
        document(
            "FI-SC-020.成本核算联系单（板换科技-直销）.pdf",
            row(cell(">>明细表", 30)),
            row(
                cell("序号", 30),
                cell("客户图号", 90),
                cell("客户编码", 220),
                cell("三花型号", 350),
                cell("包装方式", 500),
                cell("运费", 640),
                cell("不含运费总价", 760),
                cell("设备费", 900)),
            row(
                cell("1", 30),
                cell("CUS-DWG-1", 90),
                cell("CUS-001", 220),
                cell("BPHE-001", 350),
                cell("纸箱", 500),
                cell("1.25", 640),
                cell("88.66", 760),
                cell("10000", 900)),
            row(cell(">>辅助信息", 30)));
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_020);

    parser.parse(context(QuoteExcelTemplateType.FI_SC_020, document), request);

    assertThat(request.getItems()).hasSize(1);
    QuoteIngestItemRequest item = request.getItems().get(0);
    assertThat(item.getCustomerDrawing()).isEqualTo("CUS-DWG-1");
    assertThat(item.getCustomerCode()).isEqualTo("CUS-001");
    assertThat(item.getSunlModel()).isEqualTo("BPHE-001");
    assertThat(item.getPackageMethod()).isEqualTo("纸箱");
    assertThat(item.getShippingFee()).isEqualTo("1.25");
    assertThat(item.getTotalNoShip()).isEqualTo("88.66");
    assertThat(item.getExtraFees()).extracting("feeCode").containsExactly("equipmentFee");
  }

  @Test
  void desktopPdfSamplesContainLocateableDetailTableWhenAvailable() throws Exception {
    List<Path> samples =
        List.of(
            Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-020.成本核算联系单（板换科技-直销）.pdf"),
            Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-006.标准品批量品成本核算流程.pdf"),
            Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-005.新品成本核算流程.pdf"),
            Path.of("/Users/xiexicheng/Desktop/demo3/空白：新品.pdf"),
            Path.of("/Users/xiexicheng/Desktop/demo3/空白：批量品.pdf"),
            Path.of("/Users/xiexicheng/Desktop/demo3/空白：衍生品.pdf"));
    Assumptions.assumeTrue(
        samples.stream().allMatch(Files::exists),
        "six desktop PDF samples are required for T5 detail table smoke test");

    PdfBoxQuotePdfTextExtractor extractor = new PdfBoxQuotePdfTextExtractor();
    for (Path sample : samples) {
      try (InputStream inputStream = Files.newInputStream(sample)) {
        QuotePdfDocument document = extractor.extract(inputStream, sample.getFileName().toString());
        assertThat(document.getFullText()).as(sample.getFileName().toString()).contains("料号");

        QuoteExcelTemplateType templateType =
            new QuoteOaPdfTemplateResolver().resolve(sample.getFileName().toString(), document.getFullText());
        QuoteIngestRequest request = request(templateType);
        parser.parse(context(templateType, document), request);

        if (document.getFullText().contains("暂无数据")) {
          assertThat(request.getItems()).as(sample.getFileName().toString()).isEmpty();
        } else {
          assertThat(request.getItems()).as(sample.getFileName().toString()).allSatisfy(item ->
              assertThat(item.getMaterialNo() != null || item.getSunlModel() != null).isTrue());
        }
      }
    }
  }
}
