package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteClassificationResult;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteClassifyServiceTest {
  private final QuoteClassifyService service = new QuoteClassifyService();

  @Test
  void commercialProcessCodesClassifyToExpectedScenarios() {
    assertClassification("FI-SC-020", "COMMERCIAL", "DIRECT_SALE", "CONFIRMED");
    assertClassification("FI-SC-006", "COMMERCIAL", "STANDARD_BATCH", "CONFIRMED");
    assertClassification("FI-SC-005", "COMMERCIAL", "NEW_PRODUCT", "CONFIRMED");
  }

  @Test
  void fiSr005ClassifiesByBusinessType() {
    assertClassification("FI-SR-005", "新品", "HOUSEHOLD", "NEW_PRODUCT", "CONFIRMED");
    assertClassification("FI-SR-005", "批量品", "HOUSEHOLD", "MASS_PRODUCT", "CONFIRMED");
    assertClassification("FI-SR-005", "衍生品", "HOUSEHOLD", "DERIVED_PRODUCT", "CONFIRMED");
  }

  @Test
  void fiSr005WithoutBusinessTypeGoesPending() {
    QuoteClassificationResult result = service.classify(request("FI-SR-005", null));

    assertThat(result.getBusinessUnitType()).isEqualTo("UNKNOWN");
    assertThat(result.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(result.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getWarnings()).extracting("code").contains("RULE_FI_SR_005_UNKNOWN");
  }

  @Test
  void fiSr005WithMixedBusinessTypeGoesPending() {
    QuoteIngestRequest request = request("FI-SR-005", "新品");
    QuoteIngestItemRequest second = new QuoteIngestItemRequest();
    second.setMaterialNo("1002");
    second.setBusinessType("衍生品");
    request.setItems(List.of(request.getItems().get(0), second));

    QuoteClassificationResult result = service.classify(request);

    assertThat(result.getBusinessUnitType()).isEqualTo("UNKNOWN");
    assertThat(result.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(result.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getWarnings()).extracting("code").contains("RULE_FI_SR_005_MIXED");
  }

  @Test
  void unmatchedProcessCodeGoesPending() {
    QuoteClassificationResult result = service.classify(request("FI-XX-999", "新品"));

    assertThat(result.getBusinessUnitType()).isEqualTo("UNKNOWN");
    assertThat(result.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(result.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getWarnings()).extracting("code").contains("RULE_UNMATCHED");
  }

  private void assertClassification(
      String processCode, String businessUnitType, String quoteScenario, String status) {
    assertClassification(processCode, "新品", businessUnitType, quoteScenario, status);
  }

  private void assertClassification(
      String processCode,
      String businessType,
      String businessUnitType,
      String quoteScenario,
      String status) {
    QuoteClassificationResult result = service.classify(request(processCode, businessType));

    assertThat(result.getBusinessUnitType()).isEqualTo(businessUnitType);
    assertThat(result.getQuoteScenario()).isEqualTo(quoteScenario);
    assertThat(result.getClassificationStatus()).isEqualTo(status);
  }

  private QuoteIngestRequest request(String processCode, String businessType) {
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(processCode);

    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    item.setMaterialNo("1001");
    item.setBusinessType(businessType);

    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType("EXCEL");
    request.setExternalFormNo("EXT-001");
    request.setHeader(header);
    request.setItems(List.of(item));
    return request;
  }
}
