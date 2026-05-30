package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class QuoteOaPdfHeaderParserTest {
  private static final Path FI_SC_006_PDF =
      Path.of("/Users/xiexicheng/Desktop/demo3/FI-SC-006.标准品批量品成本核算流程.pdf");

  private final QuoteOaPdfHeaderParser parser = new QuoteOaPdfHeaderParser();

  @Test
  void parsesSameLineHeaderFieldsAndStopsAtNextLabel() {
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);
    parser.parse(
        context(
            QuoteExcelTemplateType.FI_SC_006,
            List.of(
                ">>基础信息",
                "流程标题 FI-SC-006.标准品/批量品成本核算流程-何之美-2026-03-27",
                "紧急程度 正常 流程编号 FI-SC-006-20260327-037",
                "申请人 何之美 申请时间 2026-03-27 14:38",
                "工号 12310607 申请单位 商用制冷业务单元",
                "申请部门 欧美业务管理部 申请处室 欧洲业务管理部",
                ">>业务信息",
                "客户名称 DTN group, s.r.o. 客户类型 国际客户",
                "业务类型 批量品 产品属性 商用产品",
                "贸易条款 DDP LEDZINY 汇率 7.2000",
                "销售价格是否联动 固定 是否通过海外仓库发终端客户 是",
                "核算时铜基价（含税，元/吨） 90000.00 核算时SUS304基价（含税，元/吨） 20000.00")),
        request);

    assertThat(request.getOaNo()).isEqualTo("FI-SC-006-20260327-037");
    assertThat(request.getExternalFormNo()).isEqualTo("FI-SC-006-20260327-037");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-03-27");
    assertThat(request.getHeader().getApplicantName()).isEqualTo("何之美");
    assertThat(request.getHeader().getApplicantDept()).isEqualTo("欧美业务管理部");
    assertThat(request.getHeader().getApplicantOffice()).isEqualTo("欧洲业务管理部");
    assertThat(request.getHeader().getCustomer()).isEqualTo("DTN group, s.r.o.");
    assertThat(request.getHeader().getProductAttr()).isEqualTo("商用产品");
    assertThat(request.getHeader().getTradeTerms()).isEqualTo("DDP LEDZINY");
    assertThat(request.getHeader().getExchangeRate()).isEqualTo("7.2000");
    assertThat(request.getHeader().getCopperPrice()).isEqualTo("90000.00");
    assertThat(request.getHeader().getSus304Price()).isEqualTo("20000.00");
    assertThat(request.getExtraFields()).extracting("fieldCode").contains("processTitle", "employeeNo", "businessType");
  }

  @Test
  void doesNotUseNextLabelLineAsMissingValue() {
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);
    parser.parse(
        context(
            QuoteExcelTemplateType.FI_SC_006,
            List.of(
                ">>基础信息",
                "紧急程度 正常 流程编号",
                "申请人 俞洋 申请时间 2026-05-25 16:49",
                ">>业务信息",
                "客户名称",
                "客户类型",
                "业务类型 批量品 产品属性")),
        request);

    assertThat(request.getOaNo()).isNull();
    assertThat(request.getHeader().getCustomer()).isNull();
    assertThat(request.getHeader().getApplicantName()).isEqualTo("俞洋");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-05-25");
    assertThat(request.getExtraFields())
        .filteredOn(field -> "businessType".equals(field.getFieldCode()))
        .extracting("fieldValue")
        .containsExactly("批量品");
  }

  @Test
  void parsesFiSr005BusinessTypeIntoHeaderExtraField() {
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SR_005_DERIVED);
    parser.parse(
        context(
            QuoteExcelTemplateType.FI_SR_005_DERIVED,
            List.of(
                ">>基础信息",
                "流程编号 FI-SR-005-20260530-001",
                "申请人 张三 申请日期 2026-05-30",
                ">>业务信息",
                "业务类型 衍生品 产品属性 家代商产品")),
        request);

    assertThat(request.getOaNo()).isEqualTo("FI-SR-005-20260530-001");
    assertThat(request.getHeader().getProcessCode()).isEqualTo("FI-SR-005");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-05-30");
    assertThat(request.getHeader().getProductAttr()).isEqualTo("家代商产品");
    assertThat(request.getExtraFields())
        .filteredOn(field -> "businessType".equals(field.getFieldCode()))
        .extracting("fieldValue")
        .containsExactly("衍生品");
  }

  @Test
  void parsesAvailableHeaderFieldsFromDesktopFiSc006PdfWhenAvailable() throws Exception {
    Assumptions.assumeTrue(Files.exists(FI_SC_006_PDF), "FI-SC-006 desktop PDF sample is required for T4");
    QuotePdfDocument document;
    try (InputStream inputStream = Files.newInputStream(FI_SC_006_PDF)) {
      document = new PdfBoxQuotePdfTextExtractor().extract(inputStream, FI_SC_006_PDF.getFileName().toString());
    }
    QuoteIngestRequest request = request(QuoteExcelTemplateType.FI_SC_006);

    parser.parse(context(QuoteExcelTemplateType.FI_SC_006, document), request);

    assertThat(request.getHeader().getApplicantName()).isEqualTo("俞洋");
    assertThat(request.getHeader().getApplyDate()).isEqualTo("2026-05-25");
    assertThat(request.getHeader().getApplicantDept()).isEqualTo("欧美业务管理部");
    assertThat(request.getHeader().getApplicantOffice()).isEqualTo("欧洲业务管理部");
    assertThat(request.getHeader().getUrgency()).isEqualTo("正常");
    assertThat(request.getOaNo()).as("当前桌面 PDF 流程编号为空，不能误读下一行").isNull();
  }

  private QuoteIngestRequest request(QuoteExcelTemplateType templateType) {
    QuoteOaPdfTemplateDefinition definition = QuoteOaPdfTemplateDefinitions.get(templateType);
    QuoteIngestRequest request = new QuoteIngestRequest();
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(definition.getProcessCode());
    header.setProcessName(definition.getProcessName());
    header.setQuoteScenario(definition.getQuoteScenario());
    header.setBusinessUnitType(definition.getBusinessUnitType());
    header.setExpenseProductCategory(definition.getExpenseProductCategory());
    request.setHeader(header);
    return request;
  }

  private QuotePdfParseContext context(QuoteExcelTemplateType templateType, List<String> lines) {
    QuotePdfDocument document = new QuotePdfDocument();
    document.setFileName(templateType.getCode() + ".pdf");
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
    return context(templateType, document);
  }

  private QuotePdfParseContext context(QuoteExcelTemplateType templateType, QuotePdfDocument document) {
    QuotePdfParseContext context = new QuotePdfParseContext();
    context.setFileName(document.getFileName());
    context.setDocument(document);
    context.setTemplateDefinition(QuoteOaPdfTemplateDefinitions.get(templateType));
    return context;
  }
}
