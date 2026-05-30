package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.AccountingContext;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.FeeSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.HeaderSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.ItemSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import java.math.BigDecimal;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteOaPdfT0BaselineTest {
  private static final Path FI_SC_006_EXCEL =
      Path.of("/Users/xiexicheng/Desktop/demo3/报价单导入模板_02_FI-SC-006_标准品批量品.xlsx");
  private static final String GOLDEN_RESOURCE = "/quote-ingest/pdf/golden-fi-sc-006-from-excel.json";

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private QuoteIngestService quoteIngestService;
  private QuoteExcelImportServiceImpl service;

  @BeforeEach
  void setUp() {
    quoteIngestService = mock(QuoteIngestService.class);
    QuoteNormalizeService normalizeService =
        new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService());
    service = new QuoteExcelImportServiceImpl(normalizeService, quoteIngestService);
  }

  @Test
  void fiSc006DesktopExcelMatchesT0GoldenSnapshotWhenAvailable() throws Exception {
    Assumptions.assumeTrue(Files.exists(FI_SC_006_EXCEL), "FI-SC-006 desktop Excel sample is required for T0");

    QuoteExcelImportPreviewResponse preview;
    try (InputStream inputStream = Files.newInputStream(FI_SC_006_EXCEL)) {
      preview = service.preview(inputStream, FI_SC_006_EXCEL.getFileName().toString());
    }

    QuoteIngestResponse ingestResponse = new QuoteIngestResponse();
    ingestResponse.setAccepted(true);
    when(quoteIngestService.ingest(any())).thenReturn(ingestResponse);
    try (InputStream inputStream = Files.newInputStream(FI_SC_006_EXCEL)) {
      QuoteExcelImportCommitResponse commit =
          service.commit(inputStream, FI_SC_006_EXCEL.getFileName().toString());
      assertThat(commit.isCommitted()).isTrue();
    }

    ArgumentCaptor<QuoteIngestRequest> requestCaptor = ArgumentCaptor.forClass(QuoteIngestRequest.class);
    verify(quoteIngestService).ingest(requestCaptor.capture());

    JsonNode actual = objectMapper.valueToTree(toGoldenSnapshot(preview, requestCaptor.getValue()));
    JsonNode expected;
    try (InputStream inputStream = getClass().getResourceAsStream(GOLDEN_RESOURCE)) {
      assertThat(inputStream).as(GOLDEN_RESOURCE).isNotNull();
      expected = objectMapper.readTree(inputStream);
    }

    assertThat(actual).isEqualTo(expected);
  }

  private Map<String, Object> toGoldenSnapshot(
      QuoteExcelImportPreviewResponse preview, QuoteIngestRequest request) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("valid", preview.isValid());
    snapshot.put("formCount", preview.getFormCount());
    snapshot.put("itemCount", preview.getItemCount());
    snapshot.put("feeCount", preview.getFeeCount());
    snapshot.put("warnings", preview.getWarnings().stream().map(this::warning).toList());
    snapshot.put("forms", preview.getForms().stream().map(this::form).toList());
    snapshot.put("extraFields", request.getExtraFields().stream().map(this::extraField).toList());
    snapshot.put("itemExtraFields", itemExtraFields(request));
    return snapshot;
  }

  private Map<String, Object> form(QuoteIngestPreviewResponse form) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("valid", form.isValid());
    map.put("accepted", form.isAccepted());
    put(map, "ingestStatus", form.getIngestStatus());
    put(map, "sourceType", form.getSourceType());
    put(map, "externalFormNo", form.getExternalFormNo());
    put(map, "oaNo", form.getOaNo());
    put(map, "processCode", form.getProcessCode());
    put(map, "quoteScenario", form.getQuoteScenario());
    put(map, "classificationStatus", form.getClassificationStatus());
    map.put("classificationPending", form.isClassificationPending());
    map.put("itemCount", form.getItemCount());
    put(map, "accountingContext", accountingContext(form.getAccountingContext()));
    put(map, "header", header(form.getHeaderSummary()));
    map.put("items", form.getItems().stream().map(this::item).toList());
    map.put("extraFees", form.getExtraFees().stream().map(this::fee).toList());
    return map;
  }

  private Map<String, Object> accountingContext(AccountingContext context) {
    if (context == null) {
      return null;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "businessUnitType", context.getBusinessUnitType());
    put(map, "accountingPeriodMonth", context.getAccountingPeriodMonth());
    put(map, "expenseProductCategory", context.getExpenseProductCategory());
    put(map, "sourceCompany", context.getSourceCompany());
    put(map, "sourceBusinessDivision", context.getSourceBusinessDivision());
    put(map, "customer", context.getCustomer());
    put(map, "productAttr", context.getProductAttr());
    put(map, "quoteScenario", context.getQuoteScenario());
    put(map, "classificationStatus", context.getClassificationStatus());
    put(map, "ruleCode", context.getRuleCode());
    map.put("confidence", context.getConfidence());
    return map;
  }

  private Map<String, Object> header(HeaderSummary header) {
    if (header == null) {
      return null;
    }
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "sourceType", header.getSourceType());
    put(map, "sourceSystem", header.getSourceSystem());
    put(map, "externalFormNo", header.getExternalFormNo());
    put(map, "oaNo", header.getOaNo());
    put(map, "processCode", header.getProcessCode());
    put(map, "processName", header.getProcessName());
    put(map, "applyDate", header.getApplyDate());
    put(map, "customer", header.getCustomer());
    put(map, "applicantUnit", header.getApplicantUnit());
    put(map, "sourceCompany", header.getSourceCompany());
    put(map, "sourceBusinessDivision", header.getSourceBusinessDivision());
    put(map, "expenseProductCategory", header.getExpenseProductCategory());
    put(map, "applicantDept", header.getApplicantDept());
    put(map, "applicantOffice", header.getApplicantOffice());
    put(map, "applicantName", header.getApplicantName());
    put(map, "urgency", header.getUrgency());
    put(map, "productAttr", header.getProductAttr());
    put(map, "priceLinkMode", header.getPriceLinkMode());
    put(map, "overseasSalesMode", header.getOverseasSalesMode());
    put(map, "tradeTerms", header.getTradeTerms());
    put(map, "exchangeRate", header.getExchangeRate());
    put(map, "copperPrice", header.getCopperPrice());
    put(map, "zincPrice", header.getZincPrice());
    put(map, "aluminumPrice", header.getAluminumPrice());
    put(map, "steelPrice", header.getSteelPrice());
    put(map, "sus304Price", header.getSus304Price());
    put(map, "sus316lPrice", header.getSus316lPrice());
    put(map, "silverPrice", header.getSilverPrice());
    put(map, "goldPrice", header.getGoldPrice());
    put(map, "remark", header.getRemark());
    return map;
  }

  private Map<String, Object> item(ItemSummary item) {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "externalLineId", item.getExternalLineId());
    put(map, "seq", item.getSeq());
    put(map, "productName", item.getProductName());
    put(map, "customerDrawing", item.getCustomerDrawing());
    put(map, "customerCode", item.getCustomerCode());
    put(map, "materialNo", item.getMaterialNo());
    put(map, "sunlModel", item.getSunlModel());
    put(map, "spec", item.getSpec());
    put(map, "productAttr", item.getProductAttr());
    put(map, "businessType", item.getBusinessType());
    put(map, "supportQty", item.getSupportQty());
    put(map, "annualVolume", item.getAnnualVolume());
    put(map, "scrapRate", item.getScrapRate());
    put(map, "unitLaborCost", item.getUnitLaborCost());
    put(map, "totalWithShip", item.getTotalWithShip());
    put(map, "totalNoShip", item.getTotalNoShip());
    put(map, "materialCost", item.getMaterialCost());
    put(map, "laborCost", item.getLaborCost());
    put(map, "manufacturingCost", item.getManufacturingCost());
    put(map, "managementCost", item.getManagementCost());
    put(map, "validMonth", item.getValidMonth());
    put(map, "sus304WeightG", item.getSus304WeightG());
    put(map, "sus316WeightG", item.getSus316WeightG());
    put(map, "copperWeightG", item.getCopperWeightG());
    put(map, "classificationStatus", item.getClassificationStatus());
    put(map, "quoteScenario", item.getQuoteScenario());
    put(map, "businessUnitType", item.getBusinessUnitType());
    put(map, "validDate", item.getValidDate());
    return map;
  }

  private Map<String, Object> fee(FeeSummary fee) {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "scope", fee.getScope());
    put(map, "itemSeq", fee.getItemSeq());
    put(map, "externalLineId", fee.getExternalLineId());
    put(map, "feeCode", fee.getFeeCode());
    put(map, "feeName", fee.getFeeName());
    put(map, "feeCategory", fee.getFeeCategory());
    put(map, "amount", fee.getAmount());
    put(map, "unit", fee.getUnit());
    put(map, "remark", fee.getRemark());
    put(map, "sourceFieldName", fee.getSourceFieldName());
    put(map, "sourceFieldPath", fee.getSourceFieldPath());
    return map;
  }

  private Map<String, Object> warning(QuoteValidationWarning warning) {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "fieldPath", warning.getFieldPath());
    put(map, "code", warning.getCode());
    put(map, "rowNo", warning.getRowNo());
    return map;
  }

  private Map<String, Object> extraField(QuoteExtraFieldRequest field) {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "fieldCode", field.getFieldCode());
    put(map, "fieldName", field.getFieldName());
    put(map, "fieldValue", field.getFieldValue());
    put(map, "valueType", field.getValueType());
    put(map, "sourceFieldName", field.getSourceFieldName());
    put(map, "sourceFieldPath", field.getSourceFieldPath());
    return map;
  }

  private List<Map<String, Object>> itemExtraFields(QuoteIngestRequest request) {
    List<Map<String, Object>> fields = new ArrayList<>();
    for (QuoteIngestItemRequest item : request.getItems()) {
      for (QuoteExtraFieldRequest field : item.getExtraFields()) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "seq", item.getSeq());
        map.putAll(extraField(field));
        fields.add(map);
      }
    }
    return fields;
  }

  private void put(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      if (value instanceof BigDecimal decimal) {
        map.put(key, decimal.toPlainString());
        return;
      }
      map.put(key, value);
    }
  }
}
