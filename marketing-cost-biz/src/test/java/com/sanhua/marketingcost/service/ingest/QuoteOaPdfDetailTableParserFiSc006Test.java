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
import org.junit.jupiter.api.Assumptions;
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

  @Test
  void parsesDenseFiSc006TableWhenHeaderTextIsSplitAcrossManyLines() {
    QuotePdfDocument document =
        document(
            "打印 - SANHUA三花.pdf",
            row(cell(">>明细表", 48)),
            row(
                cell("序", 50),
                cell("产品", 58),
                cell("客户", 78),
                cell("U11", 98),
                cell("三花型", 138),
                cell("运", 186),
                cell("预", 200),
                cell("含", 214),
                cell("不含", 228),
                cell("成本", 298),
                cell("包装", 315)),
            row(
                cell("料号", 118),
                cell("规格", 162),
                cell("输", 186),
                cell("年", 200),
                cell("运输费", 214),
                cell("运输费", 228),
                cell("有效", 298),
                cell("方式", 315)),
            row(
                cell("费", 186),
                cell("用", 200),
                cell("总成本", 214),
                cell("总成本", 228),
                cell("期（月）", 298)),
            row(cell("量", 200)),
            row(
                cell("1", 51),
                cell("电磁", 58),
                cell("阀阀", 58),
                cell("体", 58),
                cell("1001", 118),
                cell("9000", 118),
                cell("0023", 118),
                cell("7", 118),
                cell("HDF3H", 138),
                cell("82K", 138),
                cell("HDF3", 162),
                cell("H82K", 162),
                cell("0.0", 186),
                cell("000", 186),
                cell("1.0", 200),
                cell("00", 200),
                cell("31.", 214),
                cell("573", 214),
                cell("31.", 228),
                cell("573", 228),
                cell("3", 298),
                cell("标准", 315),
                cell("小包", 315),
                cell("装", 315),
                cell("小", 332),
                cell("包", 332),
                cell("装", 332)),
            row(cell(">>辅助信息", 40)));
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);

    parser.parse(context(QuoteExcelTemplateType.FI_SC_006, document), request);

    assertThat(request.getItems()).hasSize(1);
    QuoteIngestItemRequest item = request.getItems().get(0);
    assertThat(item.getSeq()).isEqualTo(1);
    assertThat(item.getProductName()).isEqualTo("电磁阀阀体");
    assertThat(item.getMaterialNo()).isEqualTo("1001900000237");
    assertThat(item.getSunlModel()).isEqualTo("HDF3H82K");
    assertThat(item.getSpec()).isEqualTo("HDF3H82K");
    assertThat(item.getShippingFee()).isEqualTo("0.0000");
    assertThat(item.getAnnualVolume()).isEqualTo("1.000");
    assertThat(item.getTotalNoShip()).isEqualTo("31.573");
    assertThat(item.getTotalWithShip()).isEqualTo("31.573");
    assertThat(item.getValidMonth()).isEqualTo("3");
    assertThat(item.getBusinessType()).isEqualTo("批量品");
  }

  @Test
  void desktopFiSc006PdfFiltersSplitMaterialFragmentsAndKeepsTwentyProductRows() throws Exception {
    Path path = Path.of("/Users/xiexicheng/Desktop/打印 - SANHUA三花(1).pdf");
    Assumptions.assumeTrue(Files.exists(path), "desktop FI-SC-006 PDF sample is required");

    PdfBoxQuotePdfTextExtractor extractor = new PdfBoxQuotePdfTextExtractor();
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);
    try (InputStream inputStream = Files.newInputStream(path)) {
      QuotePdfDocument document = extractor.extract(inputStream, path.getFileName().toString());
      parser.parse(context(QuoteExcelTemplateType.FI_SC_006, document), request);
    }

    assertThat(request.getItems()).hasSize(20);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getSeq)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getMaterialNo)
        .doesNotContain("71001", "1001", "31001", "01001", "1003", "1108")
        .contains("1001900001202", "1108900000163");
    QuoteIngestItemRequest target =
        request.getItems().stream()
            .filter(item -> "1001900001090".equals(item.getMaterialNo()))
            .findFirst()
            .orElseThrow();
    assertThat(target.getShippingFee())
        .as(
            "shipping=%s totalWithShip=%s totalNoShip=%s annualVolume=%s validMonth=%s",
            target.getShippingFee(),
            target.getTotalWithShip(),
            target.getTotalNoShip(),
            target.getAnnualVolume(),
            target.getValidMonth())
        .isEqualTo("0.0000");
  }

  @Test
  void desktopPlateFiSc006PdfKeepsActualEightyOneProductRows() throws Exception {
    Path path = Path.of("/Users/xiexicheng/Desktop/板换/FI-SC-006-20260605-008.pdf");
    Assumptions.assumeTrue(Files.exists(path), "desktop plate FI-SC-006 PDF sample is required");

    PdfBoxQuotePdfTextExtractor extractor = new PdfBoxQuotePdfTextExtractor();
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);
    try (InputStream inputStream = Files.newInputStream(path)) {
      QuotePdfDocument document = extractor.extract(inputStream, path.getFileName().toString());
      parser.parse(context(QuoteExcelTemplateType.FI_SC_006, document), request);
    }

    assertThat(request.getItems()).hasSize(81);
    assertThat(request.getItems()).extracting(QuoteIngestItemRequest::getSeq)
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 81).boxed().toList());
  }
}
