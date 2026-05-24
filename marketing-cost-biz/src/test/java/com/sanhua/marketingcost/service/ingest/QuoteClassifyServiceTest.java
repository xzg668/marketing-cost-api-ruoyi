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
    assertClassification("FI-SR-005", "新品", "COMMERCIAL", "家代商代销产品", "NEW_PRODUCT", "CONFIRMED");
    assertClassification("FI-SR-005", "批量品", "COMMERCIAL", "家代商代销产品", "MASS_PRODUCT", "CONFIRMED");
    assertClassification("FI-SR-005", "衍生品", "COMMERCIAL", "家代商代销产品", "DERIVED_PRODUCT", "CONFIRMED");
  }

  @Test
  void fiSr005WithHouseholdProxyContextClassifiesAsCommercialSpecialCase() {
    QuoteIngestRequest request = request("FI-SR-005", "新品");
    request.getHeader().setApplicantUnit("家代商业务单元");
    request.getHeader().setExpenseProductCategory("家代商代销产品");

    QuoteClassificationResult result = service.classify(request);

    assertThat(result.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(result.getExpenseProductCategory()).isEqualTo("家代商代销产品");
    assertThat(result.getQuoteScenario()).isEqualTo("NEW_PRODUCT");
    assertThat(result.getClassificationStatus()).isEqualTo("CONFIRMED");
  }

  @Test
  void fiSr005WithCommercialDirectContextGoesPending() {
    QuoteIngestRequest request = request("FI-SR-005", "新品");
    request.getHeader().setApplicantUnit("商用制冷业务单元");
    request.getHeader().setExpenseProductCategory("商用直销产品");

    QuoteClassificationResult result = service.classify(request);

    assertThat(result.getBusinessUnitType()).isEqualTo("UNKNOWN");
    assertThat(result.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(result.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getWarnings()).extracting("code").contains("RULE_FI_SR_005_CONTEXT_CONFLICT");
  }

  @Test
  void commercialProcessWithHouseholdProxyContextGoesPending() {
    QuoteIngestRequest request = request("FI-SC-006", "批量品");
    request.getHeader().setApplicantUnit("家代商业务单元");
    request.getHeader().setExpenseProductCategory("家代商代销产品");

    QuoteClassificationResult result = service.classify(request);

    assertThat(result.getBusinessUnitType()).isEqualTo("UNKNOWN");
    assertThat(result.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(result.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getWarnings()).extracting("code").contains("RULE_FI_SC_CONTEXT_CONFLICT");
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
    assertClassification(processCode, "新品", businessUnitType, "商用直销产品", quoteScenario, status);
  }

  private void assertClassification(
      String processCode,
      String businessType,
      String businessUnitType,
      String expenseProductCategory,
      String quoteScenario,
      String status) {
    QuoteClassificationResult result = service.classify(request(processCode, businessType));

    assertThat(result.getBusinessUnitType()).isEqualTo(businessUnitType);
    assertThat(result.getExpenseProductCategory()).isEqualTo(expenseProductCategory);
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
