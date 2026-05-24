package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteNormalizeServiceTest {
  private final QuoteNormalizeService service =
      new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());

  @Test
  void normalizesHeaderItemsAmountsAndClassification() {
    QuoteNormalizedDocument document = service.normalize(sampleRequest());

    assertThat(document.getErrors()).isEmpty();
    assertThat(document.getHeader().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(document.getHeader().getQuoteScenario()).isEqualTo("DIRECT_SALE");
    assertThat(document.getHeader().getClassificationStatus()).isEqualTo("CONFIRMED");
    assertThat(document.getHeader().getApplyDate()).isEqualTo(LocalDate.of(2026, 5, 11));
    assertThat(document.getHeader().getAccountingPeriodMonth()).isEqualTo("2026-05");
    assertThat(document.getHeader().getSourceSystem()).isEqualTo("EXCEL_TEMPLATE");
    assertThat(document.getHeader().getApplicantUnit()).isEqualTo("申请单位A");
    assertThat(document.getHeader().getSourceCompany()).isEqualTo("来源公司A");
    assertThat(document.getHeader().getSourceBusinessDivision()).isEqualTo("商用事业部");
    assertThat(document.getHeader().getExpenseProductCategory()).isEqualTo("商用直销产品");
    assertThat(document.getHeader().getTradeTerms()).isEqualTo("FOB");
    assertThat(document.getHeader().getExchangeRate()).isEqualByComparingTo("7.12");
    assertThat(document.getHeader().getCopperPrice()).isEqualByComparingTo("78000.50");
    assertThat(document.getItems()).hasSize(2);
    assertThat(document.getItems().get(0).getSeq()).isEqualTo(1);
    assertThat(document.getItems().get(0).getPackageQty()).isEqualByComparingTo("12.5");
  }

  @Test
  void itemProductAttrOverridesHeaderProductAttr() {
    QuoteNormalizedDocument document = service.normalize(sampleRequest());

    assertThat(document.getItems().get(0).getProductAttr()).isEqualTo("行级属性");
    assertThat(document.getItems().get(1).getProductAttr()).isEqualTo("表头属性");
  }

  @Test
  void extraFeesNormalizeToStandardFeeItems() {
    QuoteNormalizedDocument document = service.normalize(sampleRequest());

    assertThat(document.getExtraFees()).hasSize(2);
    assertThat(document.getExtraFees().get(0).getScope()).isEqualTo("HEADER");
    assertThat(document.getExtraFees().get(0).getFeeCategory()).isEqualTo("MOLD");
    assertThat(document.getExtraFees().get(0).getAmount()).isEqualByComparingTo(new BigDecimal("1234.56"));
    assertThat(document.getExtraFees().get(1).getScope()).isEqualTo("ITEM");
    assertThat(document.getExtraFees().get(1).getItemSeq()).isEqualTo(1);
    assertThat(document.getExtraFees().get(1).getFeeCategory()).isEqualTo("CERTIFICATION");
  }

  @Test
  void unknownFieldsGoToExtraFieldModel() {
    QuoteNormalizedDocument document = service.normalize(sampleRequest());

    assertThat(document.getExtraFields()).hasSize(2);
    assertThat(document.getExtraFields().get(0).getScope()).isEqualTo("HEADER");
    assertThat(document.getExtraFields().get(0).getFieldCode()).isEqualTo("custom_header_field");
    assertThat(document.getExtraFields().get(0).getFieldValueNumber()).isEqualByComparingTo("88.5");
    assertThat(document.getExtraFields().get(1).getScope()).isEqualTo("ITEM");
    assertThat(document.getExtraFields().get(1).getItemSeq()).isEqualTo(1);
    assertThat(document.getExtraFields().get(1).getFieldCode()).isEqualTo("custom_item_field");
  }

  private QuoteIngestRequest sampleRequest() {
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode("FI-SC-020");
    header.setApplyDate("2026/5/11");
    header.setCustomer("客户A");
    header.setApplicantUnit("申请单位A");
    header.setSourceCompany("来源公司A");
    header.setSourceBusinessDivision("商用事业部");
    header.setExpenseProductCategory("控制器");
    header.setProductAttr("表头属性");
    header.setTradeTerms("FOB");
    header.setExchangeRate("7.12");
    header.setCopperPrice("78,000.50");

    QuoteIngestItemRequest first = new QuoteIngestItemRequest();
    first.setSeq(1);
    first.setMaterialNo("1001");
    first.setSunlModel("SHF-A");
    first.setProductAttr("行级属性");
    first.setPackageQty("12.5");
    first.setExtraFees(List.of(fee("认证费", null, "200")));
    first.setExtraFields(List.of(extraField("custom_item_field", "未识别行字段", "abc", "TEXT")));

    QuoteIngestItemRequest second = new QuoteIngestItemRequest();
    second.setSeq(2);
    second.setMaterialNo("1002");
    second.setSunlModel("SHF-B");

    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType("EXCEL");
    request.setExternalFormNo("EXT-001");
    request.setHeader(header);
    request.setItems(List.of(first, second));
    request.setExtraFees(List.of(fee("模具费", null, "1,234.56")));
    request.setExtraFields(List.of(extraField("custom_header_field", "未识别表头字段", "88.5", "NUMBER")));
    return request;
  }

  private QuoteExtraFeeRequest fee(String feeName, String category, String amount) {
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setFeeName(feeName);
    fee.setFeeCategory(category);
    fee.setAmount(amount);
    return fee;
  }

  private QuoteExtraFieldRequest extraField(
      String fieldCode, String fieldName, String fieldValue, String valueType) {
    QuoteExtraFieldRequest field = new QuoteExtraFieldRequest();
    field.setFieldCode(fieldCode);
    field.setFieldName(fieldName);
    field.setFieldValue(fieldValue);
    field.setValueType(valueType);
    return field;
  }
}
