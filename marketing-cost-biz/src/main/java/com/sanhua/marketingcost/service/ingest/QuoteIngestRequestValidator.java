package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import com.sanhua.marketingcost.enums.QuoteClassificationStatus;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import com.sanhua.marketingcost.enums.QuoteScenario;
import com.sanhua.marketingcost.enums.QuoteSourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QuoteIngestRequestValidator {
  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      Arrays.asList(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("yyyy/M/d"),
          DateTimeFormatter.ofPattern("yyyy.MM.dd"));

  public QuoteIngestPreviewResponse validate(QuoteIngestRequest request) {
    QuoteIngestPreviewResponse response = new QuoteIngestPreviewResponse();
    response.setIngestStatus(QuoteIngestStatus.VALIDATING.getCode());
    response.setClassificationStatus(QuoteClassificationStatus.CONFIRMED.getCode());

    if (request == null) {
      addError(response, "$", "REQUEST_REQUIRED", "接入请求不能为空");
      complete(response);
      return response;
    }

    fillEchoFields(response, request);
    validateHeaderFields(response, request);
    validateItems(response, request);
    validateExtraFees(response, "extraFees", request.getExtraFees(), null);
    warnIfFiSr005NeedsClassification(response, request);
    complete(response);
    return response;
  }

  private void fillEchoFields(QuoteIngestPreviewResponse response, QuoteIngestRequest request) {
    response.setRequestId(trimToNull(request.getRequestId()));
    response.setIdempotencyKey(trimToNull(request.getIdempotencyKey()));
    response.setSourceType(trimToNull(request.getSourceType()));
    response.setExternalFormNo(trimToNull(request.getExternalFormNo()));
    response.setOaNo(trimToNull(request.getOaNo()));
    response.setItemCount(request.getItems() == null ? 0 : request.getItems().size());
    if (request.getHeader() != null) {
      response.setProcessCode(trimToNull(request.getHeader().getProcessCode()));
      response.setQuoteScenario(trimToNull(request.getHeader().getQuoteScenario()));
    }
  }

  private void validateHeaderFields(QuoteIngestPreviewResponse response, QuoteIngestRequest request) {
    String sourceType = trimToNull(request.getSourceType());
    if (sourceType == null) {
      addError(response, "sourceType", "SOURCE_TYPE_REQUIRED", "接入来源不能为空");
    } else if (!isKnownSourceType(sourceType)) {
      addError(response, "sourceType", "SOURCE_TYPE_INVALID", "接入来源不在系统字典范围内");
    }

    if (trimToNull(request.getOaNo()) == null && trimToNull(request.getExternalFormNo()) == null) {
      addError(response, "oaNo", "FORM_NO_REQUIRED", "报价单号或外部单号至少填写一个");
    }

    QuoteIngestHeaderRequest header = request.getHeader();
    if (requiresProcessCode(sourceType) && (header == null || trimToNull(header.getProcessCode()) == null)) {
      addError(response, "header.processCode", "PROCESS_CODE_REQUIRED", "OA/Excel 接入必须提供流程编号");
    }
    if (header == null) {
      addError(response, "header.applyDate", "APPLY_DATE_REQUIRED", "申请日期不能为空");
      return;
    }

    if (trimToNull(header.getApplyDate()) == null) {
      addError(response, "header.applyDate", "APPLY_DATE_REQUIRED", "申请日期不能为空");
    }
    validateDate(response, "header.applyDate", header.getApplyDate(), null);
    validateNumber(response, "header.exchangeRate", header.getExchangeRate(), null);
    validateNumber(response, "header.copperPrice", header.getCopperPrice(), null);
    validateNumber(response, "header.zincPrice", header.getZincPrice(), null);
    validateNumber(response, "header.aluminumPrice", header.getAluminumPrice(), null);
    validateNumber(response, "header.steelPrice", header.getSteelPrice(), null);
    validateNumber(response, "header.silverPrice", header.getSilverPrice(), null);
    validateNumber(response, "header.goldPrice", header.getGoldPrice(), null);
    validateNumber(response, "header.sus304Price", header.getSus304Price(), null);
    validateNumber(response, "header.sus316lPrice", header.getSus316lPrice(), null);
    validateNumber(response, "header.otherMaterial", header.getOtherMaterial(), null);
    validateNumber(response, "header.baseShipping", header.getBaseShipping(), null);
  }

  private void validateItems(QuoteIngestPreviewResponse response, QuoteIngestRequest request) {
    List<QuoteIngestItemRequest> items = request.getItems();
    if (items == null || items.isEmpty()) {
      addError(response, "items", "ITEMS_REQUIRED", "产品明细至少需要一条");
      return;
    }

    for (int index = 0; index < items.size(); index++) {
      QuoteIngestItemRequest item = items.get(index);
      int rowNo = index + 1;
      String path = "items[" + index + "]";
      if (item == null) {
        addError(response, path, "ITEM_REQUIRED", "产品明细行不能为空", rowNo);
        continue;
      }
      if (trimToNull(item.getMaterialNo()) == null && trimToNull(item.getSunlModel()) == null) {
        addError(response, path + ".materialNo", "PRODUCT_KEY_REQUIRED", "产品料号或三花型号至少填写一个", rowNo);
      }
      validateDate(response, path + ".validDate", item.getValidDate(), rowNo);
      validateNumber(response, path + ".packageQty", item.getPackageQty(), rowNo);
      validateNumber(response, path + ".shippingFee", item.getShippingFee(), rowNo);
      validateNumber(response, path + ".supportQty", item.getSupportQty(), rowNo);
      validateNumber(response, path + ".annualVolume", item.getAnnualVolume(), rowNo);
      validateNumber(response, path + ".scrapRate", item.getScrapRate(), rowNo);
      validateNumber(response, path + ".unitLaborCost", item.getUnitLaborCost(), rowNo);
      validateNumber(response, path + ".validMonth", normalizeMonth(item.getValidMonth()), rowNo);
      validateNumber(response, path + ".sus304WeightG", item.getSus304WeightG(), rowNo);
      validateNumber(response, path + ".sus316WeightG", item.getSus316WeightG(), rowNo);
      validateNumber(response, path + ".copperWeightG", item.getCopperWeightG(), rowNo);
      validateExtraFees(response, path + ".extraFees", item.getExtraFees(), rowNo);
    }
  }

  private void validateExtraFees(
      QuoteIngestPreviewResponse response,
      String path,
      List<QuoteExtraFeeRequest> extraFees,
      Integer rowNo) {
    if (extraFees == null || extraFees.isEmpty()) {
      return;
    }
    for (int index = 0; index < extraFees.size(); index++) {
      QuoteExtraFeeRequest fee = extraFees.get(index);
      if (fee == null) {
        continue;
      }
      validateNumber(response, path + "[" + index + "].amount", fee.getAmount(), rowNo);
    }
  }

  private void warnIfFiSr005NeedsClassification(
      QuoteIngestPreviewResponse response, QuoteIngestRequest request) {
    QuoteIngestHeaderRequest header = request.getHeader();
    if (header == null || !"FI-SR-005".equalsIgnoreCase(trimToNull(header.getProcessCode()))) {
      return;
    }
    boolean hasBusinessType = false;
    if (request.getItems() != null) {
      for (QuoteIngestItemRequest item : request.getItems()) {
        if (item != null && trimToNull(item.getBusinessType()) != null) {
          hasBusinessType = true;
          break;
        }
      }
    }
    if (!hasBusinessType) {
      response.setClassificationPending(true);
      response.setClassificationStatus(QuoteClassificationStatus.PENDING.getCode());
      response.setQuoteScenario(QuoteScenario.UNKNOWN.getCode());
      response
          .getWarnings()
          .add(
              new QuoteValidationWarning(
                  "items[].businessType",
                  "FI_SR_005_BUSINESS_TYPE_EMPTY",
                  "FI-SR-005 未提供业务类型，接入后需要人工确认报价场景"));
    }
  }

  private void complete(QuoteIngestPreviewResponse response) {
    boolean valid = response.getErrors() == null || response.getErrors().isEmpty();
    response.setValid(valid);
    response.setAccepted(valid);
    if (!valid) {
      response.setIngestStatus(QuoteIngestStatus.REJECTED.getCode());
      return;
    }
    if (response.isClassificationPending()) {
      response.setIngestStatus(QuoteIngestStatus.CLASSIFY_PENDING.getCode());
    } else {
      response.setIngestStatus(QuoteIngestStatus.RECEIVED.getCode());
    }
  }

  private boolean requiresProcessCode(String sourceType) {
    return QuoteSourceType.OA.getCode().equalsIgnoreCase(sourceType)
        || QuoteSourceType.WEAVER_OA.getCode().equalsIgnoreCase(sourceType)
        || QuoteSourceType.MOCK_OA.getCode().equalsIgnoreCase(sourceType)
        || QuoteSourceType.EXCEL.getCode().equalsIgnoreCase(sourceType);
  }

  private boolean isKnownSourceType(String sourceType) {
    for (QuoteSourceType value : QuoteSourceType.values()) {
      if (value.getCode().equalsIgnoreCase(sourceType)) {
        return true;
      }
    }
    return false;
  }

  private void validateNumber(
      QuoteIngestPreviewResponse response, String fieldPath, String value, Integer rowNo) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return;
    }
    try {
      if (normalized.endsWith("%")) {
        new BigDecimal(normalized.substring(0, normalized.length() - 1).replace(",", ""));
      } else {
        new BigDecimal(normalized.replace(",", ""));
      }
    } catch (NumberFormatException ex) {
      addError(response, fieldPath, "NUMBER_INVALID", "金额或数量字段必须是可解析数字", rowNo);
    }
  }

  private void validateDate(
      QuoteIngestPreviewResponse response, String fieldPath, String value, Integer rowNo) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return;
    }
    for (DateTimeFormatter formatter : DATE_FORMATTERS) {
      try {
        LocalDate.parse(normalized, formatter);
        return;
      } catch (DateTimeParseException ignored) {
        // Try next supported ingest date format.
      }
    }
    addError(response, fieldPath, "DATE_INVALID", "日期字段必须是可解析日期", rowNo);
  }

  private void addError(
      QuoteIngestPreviewResponse response, String fieldPath, String code, String message) {
    addError(response, fieldPath, code, message, null);
  }

  private void addError(
      QuoteIngestPreviewResponse response,
      String fieldPath,
      String code,
      String message,
      Integer rowNo) {
    response.getErrors().add(new QuoteValidationError(fieldPath, code, message, rowNo));
  }

  private String normalizeMonth(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return normalized.replace("个月", "").replace("月", "").trim();
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
