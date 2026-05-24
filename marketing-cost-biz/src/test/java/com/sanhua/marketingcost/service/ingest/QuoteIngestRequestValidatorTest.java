package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteIngestRequestValidatorTest {
  private final QuoteIngestRequestValidator validator = new QuoteIngestRequestValidator();

  @Test
  void minimalValidRequestPasses() {
    QuoteIngestPreviewResponse response = validator.validate(minimalRequest());

    assertThat(response.isValid()).isTrue();
    assertThat(response.isAccepted()).isTrue();
    assertThat(response.getErrors()).isEmpty();
    assertThat(response.getItemCount()).isEqualTo(1);
    assertThat(response.getIngestStatus()).isEqualTo("RECEIVED");
  }

  @Test
  void noFormNoFails() {
    QuoteIngestRequest request = minimalRequest();
    request.setOaNo(" ");
    request.setExternalFormNo(null);

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getIngestStatus()).isEqualTo("REJECTED");
    assertThat(response.getErrors()).extracting("code").contains("FORM_NO_REQUIRED");
  }

  @Test
  void noItemsFails() {
    QuoteIngestRequest request = minimalRequest();
    request.setItems(new ArrayList<>());

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("ITEMS_REQUIRED");
  }

  @Test
  void itemWithoutMaterialNoAndModelFails() {
    QuoteIngestRequest request = minimalRequest();
    request.getItems().get(0).setMaterialNo(" ");
    request.getItems().get(0).setSunlModel(null);

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("PRODUCT_KEY_REQUIRED");
    assertThat(response.getErrors().get(0).getRowNo()).isEqualTo(1);
  }

  @Test
  void invalidNumberFailsWithStructuredError() {
    QuoteIngestRequest request = minimalRequest();
    request.getHeader().setCopperPrice("abc");
    request.getHeader().setExchangeRate("x7");
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setAmount("12x");
    request.setExtraFees(List.of(fee));

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("NUMBER_INVALID");
    assertThat(response.getErrors())
        .extracting("fieldPath")
        .contains("header.copperPrice", "header.exchangeRate", "extraFees[0].amount");
  }

  @Test
  void missingApplyDateFails() {
    QuoteIngestRequest request = minimalRequest();
    request.getHeader().setApplyDate(" ");

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("APPLY_DATE_REQUIRED");
    assertThat(response.getErrors()).extracting("fieldPath").contains("header.applyDate");
  }

  @Test
  void invalidDateFailsWithStructuredError() {
    QuoteIngestRequest request = minimalRequest();
    request.getHeader().setApplyDate("2026-99-99");

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("DATE_INVALID");
    assertThat(response.getErrors()).extracting("fieldPath").contains("header.applyDate");
  }

  @Test
  void weaverOaSourceTypePasses() {
    QuoteIngestRequest request = minimalRequest();
    request.setSourceType("WEAVER_OA");

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isTrue();
    assertThat(response.getErrors()).isEmpty();
  }

  @Test
  void fiSr005WithoutBusinessTypeWarnsButDoesNotFail() {
    QuoteIngestRequest request = minimalRequest();
    request.getHeader().setProcessCode("FI-SR-005");
    request.getItems().get(0).setBusinessType(null);

    QuoteIngestPreviewResponse response = validator.validate(request);

    assertThat(response.isValid()).isTrue();
    assertThat(response.isClassificationPending()).isTrue();
    assertThat(response.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(response.getQuoteScenario()).isEqualTo("UNKNOWN");
    assertThat(response.getIngestStatus()).isEqualTo("CLASSIFY_PENDING");
    assertThat(response.getWarnings()).extracting("code").contains("FI_SR_005_BUSINESS_TYPE_EMPTY");
  }

  private QuoteIngestRequest minimalRequest() {
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode("FI-SC-020");
    header.setApplyDate("2026-05-11");
    header.setCopperPrice("78000.50");

    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    item.setSeq(1);
    item.setMaterialNo("1001");
    item.setSunlModel("SHF-A");
    item.setPackageQty("1");
    item.setShippingFee("2.35");
    item.setBusinessType("新品");

    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType("EXCEL");
    request.setExternalFormNo("EXT-001");
    request.setHeader(header);
    request.setItems(List.of(item));
    return request;
  }
}
