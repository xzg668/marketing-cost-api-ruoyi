package com.sanhua.marketingcost.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.AccountingContext;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.FeeSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.HeaderSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse.ItemSummary;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

final class QuoteIngestGoldenSnapshotSupport {
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  JsonNode previewBusinessSnapshot(QuoteExcelImportPreviewResponse preview) {
    return objectMapper.valueToTree(toBusinessSnapshot(preview));
  }

  boolean representsSameFiSc006BusinessData(
      QuoteExcelImportPreviewResponse excel, QuoteExcelImportPreviewResponse pdf) {
    if (excel == null || pdf == null || excel.getForms().isEmpty() || pdf.getForms().isEmpty()) {
      return false;
    }
    QuoteIngestPreviewResponse excelForm = excel.getForms().get(0);
    QuoteIngestPreviewResponse pdfForm = pdf.getForms().get(0);
    if (!StringUtils.hasText(excelForm.getOaNo()) || !excelForm.getOaNo().equals(pdfForm.getOaNo())) {
      return false;
    }
    if (excelForm.getItems().isEmpty() || pdfForm.getItems().isEmpty()) {
      return false;
    }
    String excelMaterialNo = excelForm.getItems().get(0).getMaterialNo();
    String pdfMaterialNo = pdfForm.getItems().get(0).getMaterialNo();
    return StringUtils.hasText(excelMaterialNo) && excelMaterialNo.equals(pdfMaterialNo);
  }

  private Map<String, Object> toBusinessSnapshot(QuoteExcelImportPreviewResponse preview) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("valid", preview.isValid());
    snapshot.put("formCount", preview.getFormCount());
    snapshot.put("itemCount", preview.getItemCount());
    snapshot.put("feeCount", preview.getFeeCount());
    snapshot.put("warnings", preview.getWarnings().stream().map(this::warning).toList());
    snapshot.put("forms", preview.getForms().stream().map(this::form).toList());
    return snapshot;
  }

  private Map<String, Object> form(QuoteIngestPreviewResponse form) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("valid", form.isValid());
    map.put("accepted", form.isAccepted());
    put(map, "ingestStatus", form.getIngestStatus());
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
    put(map, "feeCode", fee.getFeeCode());
    put(map, "feeName", fee.getFeeName());
    put(map, "feeCategory", fee.getFeeCategory());
    put(map, "amount", fee.getAmount());
    put(map, "unit", fee.getUnit());
    put(map, "remark", fee.getRemark());
    put(map, "sourceFieldName", fee.getSourceFieldName());
    return map;
  }

  private Map<String, Object> warning(QuoteValidationWarning warning) {
    Map<String, Object> map = new LinkedHashMap<>();
    put(map, "fieldPath", warning.getFieldPath());
    put(map, "code", warning.getCode());
    put(map, "rowNo", warning.getRowNo());
    return map;
  }

  private void put(Map<String, Object> map, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof BigDecimal decimal) {
      map.put(key, decimal.toPlainString());
      return;
    }
    map.put(key, value);
  }
}
