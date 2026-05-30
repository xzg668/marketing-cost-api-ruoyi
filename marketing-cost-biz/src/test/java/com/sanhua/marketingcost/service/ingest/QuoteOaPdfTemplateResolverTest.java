package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import org.junit.jupiter.api.Test;

class QuoteOaPdfTemplateResolverTest {
  private final QuoteOaPdfTemplateResolver resolver = new QuoteOaPdfTemplateResolver();

  @Test
  void resolvesFiScTemplatesByProcessCodeTitleOrFileName() {
    assertThat(resolver.resolve("FI-SC-020.成本核算联系单（板换科技-直销）.pdf", ""))
        .isEqualTo(QuoteExcelTemplateType.FI_SC_020);
    assertThat(resolver.resolve("quote.pdf", "流程标题 FI-SC-006.标准品/批量品成本核算流程"))
        .isEqualTo(QuoteExcelTemplateType.FI_SC_006);
    assertThat(resolver.resolve("quote.pdf", "流程编号 FI-SC-005-20260530-001 新品成本核算流程"))
        .isEqualTo(QuoteExcelTemplateType.FI_SC_005);
  }

  @Test
  void resolvesFiSr005ByBusinessType() {
    assertThat(resolver.resolve("空白：新品.pdf", "FI-SR-005 成本核算联系单"))
        .isEqualTo(QuoteExcelTemplateType.FI_SR_005_NEW);
    assertThat(resolver.resolve("FI-SR-005.pdf", ">>业务信息 业务类型 批量品"))
        .isEqualTo(QuoteExcelTemplateType.FI_SR_005_MASS);
    assertThat(resolver.resolve("FI-SR-005.pdf", ">>业务信息 业务类型 衍生品"))
        .isEqualTo(QuoteExcelTemplateType.FI_SR_005_DERIVED);
  }

  @Test
  void returnsNullForUnsupportedTemplate() {
    assertThat(resolver.resolve("unknown.pdf", "其他流程")).isNull();
  }

  @Test
  void definitionsCoverAllSixTemplatesAndCoreFields() {
    assertThat(QuoteOaPdfTemplateDefinitions.all()).hasSize(6);
    QuoteOaPdfTemplateDefinition fiSc006 =
        QuoteOaPdfTemplateDefinitions.get(QuoteExcelTemplateType.FI_SC_006);

    assertThat(fiSc006.getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(fiSc006.getQuoteScenario()).isEqualTo("STANDARD_BATCH");
    assertThat(fiSc006.getBusinessUnitType()).isEqualTo(QuoteClassifyService.BU_COMMERCIAL);
    assertThat(fiSc006.getHeaderFields()).extracting("fieldCode").contains("oaNo", "applicantName", "applyDate");
    assertThat(fiSc006.getItemFields()).extracting("fieldCode").contains("seq", "materialNo", "sunlModel");
    assertThat(fiSc006.getFeeFields()).extracting("fieldCode").contains("fixtureTotalAmount", "moldTotalAmount");
    assertThat(fiSc006.getItemTable().getStartAnchors()).contains("明细表");
  }
}
