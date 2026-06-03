package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteClassificationResult;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedExtraFee;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedExtraField;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedHeader;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedItem;
import com.sanhua.marketingcost.enums.QuoteExtraFeeCategory;
import com.sanhua.marketingcost.enums.QuoteSourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class QuoteNormalizeService {
  private static final String SCOPE_HEADER = "HEADER";
  private static final String SCOPE_ITEM = "ITEM";
  private static final String SOURCE_SYSTEM_EXCEL_TEMPLATE = "EXCEL_TEMPLATE";
  private static final String SOURCE_SYSTEM_WEAVER_ECOLOGY = "WEAVER_ECOLOGY";
  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      Arrays.asList(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("yyyy/M/d"),
          DateTimeFormatter.ofPattern("yyyy.MM.dd"));

  private final QuoteIngestRequestValidator validator;
  private final QuoteClassifyService classifyService;

  public QuoteNormalizeService(
      QuoteIngestRequestValidator validator, QuoteClassifyService classifyService) {
    this.validator = validator;
    this.classifyService = classifyService;
  }

  public QuoteNormalizedDocument normalize(QuoteIngestRequest request) {
    applyDerivedProcessCode(request);
    QuoteIngestPreviewResponse validation = validator.validate(request);
    QuoteClassificationResult classification = classifyService.classify(request);

    QuoteNormalizedDocument document = new QuoteNormalizedDocument();
    document.setClassification(classification);
    document.setErrors(validation.getErrors());
    document.getWarnings().addAll(validation.getWarnings());
    document.getWarnings().addAll(classification.getWarnings());

    QuoteIngestHeaderRequest headerRequest = request == null ? null : request.getHeader();
    document.setHeader(normalizeHeader(request, headerRequest, classification));
    normalizeHeaderExtras(document, request);
    normalizeItems(document, request, headerRequest, classification);
    return document;
  }

  private void applyDerivedProcessCode(QuoteIngestRequest request) {
    if (request == null || request.getHeader() == null) {
      return;
    }
    String processCode =
        QuoteProcessCodeResolver.resolve(
            request.getHeader().getProcessCode(), request.getOaNo(), request.getExternalFormNo());
    request.getHeader().setProcessCode(processCode);
  }

  private QuoteNormalizedHeader normalizeHeader(
      QuoteIngestRequest request,
      QuoteIngestHeaderRequest headerRequest,
      QuoteClassificationResult classification) {
    QuoteNormalizedHeader header = new QuoteNormalizedHeader();
    if (request != null) {
      String sourceType = trimToNull(request.getSourceType());
      header.setSourceType(sourceType);
      header.setSourceSystem(resolveSourceSystem(sourceType, request.getSourceSystem()));
      header.setExternalFormNo(trimToNull(request.getExternalFormNo()));
      header.setOaNo(trimToNull(request.getOaNo()));
    }
    header.setBusinessUnitType(classification.getBusinessUnitType());
    header.setQuoteScenario(classification.getQuoteScenario());
    header.setClassificationStatus(classification.getClassificationStatus());
    if (headerRequest == null) {
      return header;
    }
    header.setProcessCode(trimToNull(headerRequest.getProcessCode()));
    header.setProcessName(trimToNull(headerRequest.getProcessName()));
    header.setFormType(trimToNull(headerRequest.getFormType()));
    LocalDate applyDate = parseDate(headerRequest.getApplyDate());
    header.setApplyDate(applyDate);
    header.setAccountingPeriodMonth(toAccountingPeriodMonth(applyDate));
    header.setCustomer(trimToNull(headerRequest.getCustomer()));
    header.setApplicantUnit(trimToNull(headerRequest.getApplicantUnit()));
    header.setSourceCompany(trimToNull(headerRequest.getSourceCompany()));
    header.setSourceBusinessDivision(sanitizeBusinessDivision(headerRequest.getSourceBusinessDivision()));
    header.setExpenseProductCategory(
        coalesce(classification.getExpenseProductCategory(), headerRequest.getExpenseProductCategory()));
    header.setApplicantDept(trimToNull(headerRequest.getApplicantDept()));
    header.setApplicantOffice(trimToNull(headerRequest.getApplicantOffice()));
    header.setApplicantName(trimToNull(headerRequest.getApplicantName()));
    header.setUrgency(trimToNull(headerRequest.getUrgency()));
    header.setProductAttr(trimToNull(headerRequest.getProductAttr()));
    header.setPriceLinkMode(trimToNull(headerRequest.getPriceLinkMode()));
    header.setOverseasSalesMode(trimToNull(headerRequest.getOverseasSalesMode()));
    header.setTradeTerms(trimToNull(headerRequest.getTradeTerms()));
    header.setExchangeRate(parseNumber(headerRequest.getExchangeRate()));
    header.setCopperPrice(parseNumber(headerRequest.getCopperPrice()));
    header.setZincPrice(parseNumber(headerRequest.getZincPrice()));
    header.setAluminumPrice(parseNumber(headerRequest.getAluminumPrice()));
    header.setSteelPrice(parseNumber(headerRequest.getSteelPrice()));
    header.setSilverPrice(parseNumber(headerRequest.getSilverPrice()));
    header.setGoldPrice(parseNumber(headerRequest.getGoldPrice()));
    header.setSus304Price(parseNumber(headerRequest.getSus304Price()));
    header.setSus316lPrice(parseNumber(headerRequest.getSus316lPrice()));
    header.setOtherMaterial(parseNumber(headerRequest.getOtherMaterial()));
    header.setBaseShipping(parseNumber(headerRequest.getBaseShipping()));
    header.setSaleLink(trimToNull(headerRequest.getSaleLink()));
    header.setRemark(trimToNull(headerRequest.getRemark()));
    return header;
  }

  private String resolveSourceSystem(String sourceType, String sourceSystem) {
    String value = trimToNull(sourceSystem);
    if (value != null) {
      return value;
    }
    if (QuoteSourceType.EXCEL.getCode().equalsIgnoreCase(sourceType)) {
      return SOURCE_SYSTEM_EXCEL_TEMPLATE;
    }
    if (QuoteSourceType.WEAVER_OA.getCode().equalsIgnoreCase(sourceType)) {
      return SOURCE_SYSTEM_WEAVER_ECOLOGY;
    }
    return null;
  }

  private String toAccountingPeriodMonth(LocalDate applyDate) {
    if (applyDate == null) {
      return null;
    }
    return String.format("%04d-%02d", applyDate.getYear(), applyDate.getMonthValue());
  }

  private void normalizeHeaderExtras(QuoteNormalizedDocument document, QuoteIngestRequest request) {
    if (request == null) {
      return;
    }
    if (request.getExtraFees() != null) {
      for (QuoteExtraFeeRequest fee : request.getExtraFees()) {
        document.getExtraFees().add(normalizeFee(fee, SCOPE_HEADER, null, null));
      }
    }
    if (request.getExtraFields() != null) {
      for (QuoteExtraFieldRequest field : request.getExtraFields()) {
        document.getExtraFields().add(normalizeField(field, SCOPE_HEADER, null, null));
      }
    }
  }

  private void normalizeItems(
      QuoteNormalizedDocument document,
      QuoteIngestRequest request,
      QuoteIngestHeaderRequest headerRequest,
      QuoteClassificationResult classification) {
    if (request == null || request.getItems() == null) {
      return;
    }
    String headerProductAttr = headerRequest == null ? null : headerRequest.getProductAttr();
    for (int index = 0; index < request.getItems().size(); index++) {
      QuoteIngestItemRequest itemRequest = request.getItems().get(index);
      if (itemRequest == null) {
        continue;
      }
      int fallbackSeq = index + 1;
      QuoteNormalizedItem item = normalizeItem(itemRequest, headerProductAttr, classification, fallbackSeq);
      document.getItems().add(item);

      if (itemRequest.getExtraFees() != null) {
        for (QuoteExtraFeeRequest fee : itemRequest.getExtraFees()) {
          document.getExtraFees().add(normalizeFee(fee, SCOPE_ITEM, item.getSeq(), item.getExternalLineId()));
        }
      }
      if (itemRequest.getExtraFields() != null) {
        for (QuoteExtraFieldRequest field : itemRequest.getExtraFields()) {
          document
              .getExtraFields()
              .add(normalizeField(field, SCOPE_ITEM, item.getSeq(), item.getExternalLineId()));
        }
      }
    }
  }

  private QuoteNormalizedItem normalizeItem(
      QuoteIngestItemRequest source,
      String headerProductAttr,
      QuoteClassificationResult classification,
      int fallbackSeq) {
    QuoteNormalizedItem item = new QuoteNormalizedItem();
    item.setExternalLineId(trimToNull(source.getExternalLineId()));
    item.setSeq(source.getSeq() == null ? fallbackSeq : source.getSeq());
    item.setProductName(trimToNull(source.getProductName()));
    item.setCustomerDrawing(trimToNull(source.getCustomerDrawing()));
    item.setCustomerCode(trimToNull(source.getCustomerCode()));
    item.setMaterialNo(trimToNull(source.getMaterialNo()));
    item.setSunlModel(trimToNull(source.getSunlModel()));
    item.setSpec(trimToNull(source.getSpec()));
    item.setProductAttr(coalesce(source.getProductAttr(), headerProductAttr));
    item.setBusinessType(trimToNull(source.getBusinessType()));
    item.setFirstQuoteFlag(source.getFirstQuoteFlag());
    item.setCertificationRequired(source.getCertificationRequired());
    item.setOriginCountry(trimToNull(source.getOriginCountry()));
    item.setTechnicianName(trimToNull(source.getTechnicianName()));
    item.setPackageType(trimToNull(source.getPackageType()));
    item.setPackageMethod(trimToNull(source.getPackageMethod()));
    item.setPackageComponentCode(trimToNull(source.getPackageComponentCode()));
    item.setPackageQty(parseNumber(source.getPackageQty()));
    item.setShippingFee(parseNumber(source.getShippingFee()));
    item.setSupportQty(parseNumber(source.getSupportQty()));
    item.setAnnualVolume(parseNumber(source.getAnnualVolume()));
    item.setProjectNo(trimToNull(source.getProjectNo()));
    item.setProductStatus(trimToNull(source.getProductStatus()));
    item.setScrapRate(parseNumber(source.getScrapRate()));
    item.setUnitLaborCost(parseNumber(source.getUnitLaborCost()));
    item.setTotalWithShip(parseNumber(source.getTotalWithShip()));
    item.setTotalNoShip(parseNumber(source.getTotalNoShip()));
    item.setMaterialCost(parseNumber(source.getMaterialCost()));
    item.setLaborCost(parseNumber(source.getLaborCost()));
    item.setManufacturingCost(parseNumber(source.getManufacturingCost()));
    item.setManagementCost(parseNumber(source.getManagementCost()));
    item.setValidMonth(parseInteger(source.getValidMonth()));
    item.setSus304WeightG(parseNumber(source.getSus304WeightG()));
    item.setSus316WeightG(parseNumber(source.getSus316WeightG()));
    item.setCopperWeightG(parseNumber(source.getCopperWeightG()));
    item.setValidDate(parseDate(source.getValidDate()));
    item.setBusinessUnitType(classification.getBusinessUnitType());
    item.setQuoteScenario(classification.getQuoteScenario());
    item.setClassificationStatus(classification.getClassificationStatus());
    return item;
  }

  private QuoteNormalizedExtraFee normalizeFee(
      QuoteExtraFeeRequest source, String scope, Integer itemSeq, String externalLineId) {
    QuoteNormalizedExtraFee fee = new QuoteNormalizedExtraFee();
    if (source == null) {
      return fee;
    }
    fee.setScope(scope);
    fee.setItemSeq(itemSeq);
    fee.setExternalLineId(externalLineId);
    fee.setFeeCode(trimToNull(source.getFeeCode()));
    fee.setFeeName(trimToNull(source.getFeeName()));
    fee.setFeeCategory(normalizeFeeCategory(source));
    fee.setAmount(parseNumber(source.getAmount()));
    fee.setUnit(trimToNull(source.getUnit()));
    fee.setRemark(trimToNull(source.getRemark()));
    fee.setSourceFieldName(trimToNull(source.getSourceFieldName()));
    fee.setSourceFieldPath(trimToNull(source.getSourceFieldPath()));
    return fee;
  }

  private QuoteNormalizedExtraField normalizeField(
      QuoteExtraFieldRequest source, String scope, Integer itemSeq, String externalLineId) {
    QuoteNormalizedExtraField field = new QuoteNormalizedExtraField();
    if (source == null) {
      return field;
    }
    String valueType = coalesce(source.getValueType(), "TEXT").toUpperCase();
    String value = trimToNull(source.getFieldValue());
    field.setScope(scope);
    field.setItemSeq(itemSeq);
    field.setExternalLineId(externalLineId);
    field.setFieldCode(trimToNull(source.getFieldCode()));
    field.setFieldName(trimToNull(source.getFieldName()));
    field.setFieldValue(value);
    field.setValueType(valueType);
    field.setSourceFieldName(trimToNull(source.getSourceFieldName()));
    field.setSourceFieldPath(trimToNull(source.getSourceFieldPath()));
    if ("NUMBER".equals(valueType)) {
      field.setFieldValueNumber(parseNumber(value));
    } else if ("DATE".equals(valueType)) {
      field.setFieldValueDate(parseDate(value));
    }
    return field;
  }

  private String normalizeFeeCategory(QuoteExtraFeeRequest source) {
    String category = trimToNull(source.getFeeCategory());
    if (isKnownFeeCategory(category)) {
      return category.toUpperCase();
    }
    String text = coalesce(source.getFeeName(), source.getFeeCode(), "");
    if (text.contains("工装")) {
      return QuoteExtraFeeCategory.TOOLING.getCode();
    }
    if (text.contains("模具")) {
      return QuoteExtraFeeCategory.MOLD.getCode();
    }
    if (text.contains("认证")) {
      return QuoteExtraFeeCategory.CERTIFICATION.getCode();
    }
    if (text.contains("设备")) {
      return QuoteExtraFeeCategory.EQUIPMENT.getCode();
    }
    if (text.contains("刀具")) {
      return QuoteExtraFeeCategory.CUTTER.getCode();
    }
    if (text.contains("人工") || text.contains("工资")) {
      return QuoteExtraFeeCategory.LABOR.getCode();
    }
    if (text.contains("报废")) {
      return QuoteExtraFeeCategory.SCRAP.getCode();
    }
    return QuoteExtraFeeCategory.OTHER.getCode();
  }

  private boolean isKnownFeeCategory(String category) {
    if (category == null) {
      return false;
    }
    for (QuoteExtraFeeCategory value : QuoteExtraFeeCategory.values()) {
      if (value.getCode().equalsIgnoreCase(category)) {
        return true;
      }
    }
    return false;
  }

  private BigDecimal parseNumber(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      if (normalized.endsWith("%")) {
        return new BigDecimal(normalized.substring(0, normalized.length() - 1).replace(",", ""))
            .movePointLeft(2);
      }
      return new BigDecimal(normalized.replace(",", ""));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Integer parseInteger(String value) {
    BigDecimal decimal = parseNumber(normalizeMonth(value));
    return decimal == null ? null : decimal.intValue();
  }

  private String normalizeMonth(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return normalized.replace("个月", "").replace("月", "").trim();
  }

  private LocalDate parseDate(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    for (DateTimeFormatter formatter : DATE_FORMATTERS) {
      try {
        return LocalDate.parse(normalized, formatter);
      } catch (DateTimeParseException ignored) {
        // Try next supported ingest date format.
      }
    }
    return null;
  }

  private String coalesce(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private String coalesce(String first, String second, String third) {
    String value = coalesce(first, second);
    return value == null ? trimToNull(third) : value;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String sanitizeBusinessDivision(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    String cleaned = removeLeadingLabel(trimmed, "事业部");
    cleaned = removeLeadingParentheticalNotes(cleaned);
    return trimToNull(removeLeadingLabel(cleaned, "事业部"));
  }

  private String removeLeadingLabel(String value, String label) {
    String trimmed = trimToNull(value);
    if (trimmed == null || !trimmed.startsWith(label)) {
      return trimmed;
    }
    return trimToNull(trimmed.substring(label.length()));
  }

  private String removeLeadingParentheticalNotes(String value) {
    String result = trimToNull(value);
    while (result != null && (result.startsWith("（") || result.startsWith("("))) {
      char close = result.startsWith("（") ? '）' : ')';
      int closeIndex = result.indexOf(close);
      if (closeIndex < 0) {
        return null;
      }
      result = trimToNull(result.substring(closeIndex + 1));
    }
    return result;
  }
}
