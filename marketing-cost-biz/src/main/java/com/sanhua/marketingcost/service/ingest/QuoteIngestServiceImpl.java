package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedExtraFee;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedExtraField;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedHeader;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedItem;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormExtraFee;
import com.sanhua.marketingcost.entity.OaFormHeaderExtraField;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaFormItemExtraField;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import com.sanhua.marketingcost.mapper.OaFormExtraFeeMapper;
import com.sanhua.marketingcost.mapper.OaFormHeaderExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.ProductPropertyAnnualUsageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteIngestServiceImpl implements QuoteIngestService {
  private static final String SCOPE_ITEM = "ITEM";

  private final QuoteNormalizeService quoteNormalizeService;
  private final QuoteIngestLogService quoteIngestLogService;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormExtraFeeMapper oaFormExtraFeeMapper;
  private final OaFormHeaderExtraFieldMapper oaFormHeaderExtraFieldMapper;
  private final OaFormItemExtraFieldMapper oaFormItemExtraFieldMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final ProductPropertyAnnualUsageService productPropertyAnnualUsageService;
  private final ObjectMapper objectMapper;

  public QuoteIngestServiceImpl(
      QuoteNormalizeService quoteNormalizeService,
      QuoteIngestLogService quoteIngestLogService,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      OaFormExtraFeeMapper oaFormExtraFeeMapper,
      OaFormHeaderExtraFieldMapper oaFormHeaderExtraFieldMapper,
      OaFormItemExtraFieldMapper oaFormItemExtraFieldMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      ProductPropertyAnnualUsageService productPropertyAnnualUsageService,
      ObjectMapper objectMapper) {
    this.quoteNormalizeService = quoteNormalizeService;
    this.quoteIngestLogService = quoteIngestLogService;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormExtraFeeMapper = oaFormExtraFeeMapper;
    this.oaFormHeaderExtraFieldMapper = oaFormHeaderExtraFieldMapper;
    this.oaFormItemExtraFieldMapper = oaFormItemExtraFieldMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.productPropertyAnnualUsageService = productPropertyAnnualUsageService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public QuoteIngestResponse ingest(QuoteIngestRequest request) {
    String requestId = resolveRequestId(request);
    String idempotencyKey = resolveIdempotencyKey(request);
    String payloadJson = toJson(request);
    String payloadHash = sha256(payloadJson);
    QuoteNormalizedDocument normalized = quoteNormalizeService.normalize(request);
    String normalizedJson = toJson(normalized);

    QuoteIngestLog existingLog = quoteIngestLogService.findByIdempotencyKey(idempotencyKey);
    if (existingLog != null && payloadHash.equals(existingLog.getPayloadHash())) {
      return duplicateResponse(existingLog, normalized);
    }

    QuoteIngestLog log =
        existingLog == null
            ? quoteIngestLogService.createReceived(
                request, normalized, requestId, idempotencyKey, payloadHash, payloadJson, normalizedJson)
            : existingLog;
    if (existingLog != null) {
      quoteIngestLogService.refreshReceived(
          log, request, normalized, requestId, payloadHash, payloadJson, normalizedJson);
    }

    if (normalized.getErrors() != null && !normalized.getErrors().isEmpty()) {
      quoteIngestLogService.markRejected(log, normalized, "接入校验失败");
      return rejectedResponse(log, normalized, "接入校验失败");
    }

    String oaNo = resolveOaNo(normalized.getHeader(), request);
    OaForm existingForm = findExistingForm(oaNo, request);
    // 已核算单据已经进入成本核算链路，重新接入会改写核算上下文，所以默认拒绝覆盖关键字段。
    if (isCalculated(existingForm)) {
      normalized
          .getErrors()
          .add(
              new QuoteValidationError(
                  "oaNo", "CALCULATED_FORM_LOCKED", "单据已核算，默认禁止通过接入覆盖关键字段"));
      quoteIngestLogService.markRejected(log, normalized, "单据已核算，拒绝覆盖");
      return rejectedResponse(log, normalized, "单据已核算，拒绝覆盖");
    }

    OaForm form = upsertOaForm(existingForm, normalized.getHeader(), request, oaNo, log.getId());
    ItemInsertResult itemInsertResult = replaceItems(form, normalized);
    replaceExtraFees(form, normalized, itemInsertResult.itemIdMap(), log.getId());
    replaceExtraFields(form, normalized, itemInsertResult.itemIdMap(), log.getId());
    replaceBomStatuses(form, normalized, itemInsertResult.itemIdsByPosition());
    ProductPropertyAnnualSyncResult annualUsageSyncResult =
        productPropertyAnnualUsageService.syncFromOaForm(form, listItems(form.getId()));

    quoteIngestLogService.markImported(log, normalized, form.getId(), form.getOaNo());
    return importedResponse(log, normalized, form, annualUsageSyncResult);
  }

  private OaForm upsertOaForm(
      OaForm existing, QuoteNormalizedHeader header, QuoteIngestRequest request, String oaNo, Long logId) {
    OaForm form = existing == null ? new OaForm() : existing;
    form.setOaNo(oaNo);
    form.setSourceType(header.getSourceType());
    form.setSourceSystem(header.getSourceSystem());
    form.setExternalFormNo(header.getExternalFormNo());
    form.setProcessCode(header.getProcessCode());
    form.setProcessName(header.getProcessName());
    form.setQuoteScenario(header.getQuoteScenario());
    form.setClassificationStatus(header.getClassificationStatus());
    form.setBusinessUnitType(header.getBusinessUnitType());
    form.setAccountingPeriodMonth(header.getAccountingPeriodMonth());
    form.setFormType(header.getFormType());
    form.setApplyDate(header.getApplyDate());
    form.setCustomer(header.getCustomer());
    form.setApplicantUnit(header.getApplicantUnit());
    form.setSourceCompany(header.getSourceCompany());
    form.setSourceBusinessDivision(header.getSourceBusinessDivision());
    form.setExpenseProductCategory(header.getExpenseProductCategory());
    form.setApplicantDept(header.getApplicantDept());
    form.setApplicantOffice(header.getApplicantOffice());
    form.setApplicantName(header.getApplicantName());
    form.setUrgency(header.getUrgency());
    form.setProductAttr(header.getProductAttr());
    form.setPriceLinkMode(header.getPriceLinkMode());
    form.setOverseasSalesMode(header.getOverseasSalesMode());
    form.setTradeTerms(header.getTradeTerms());
    form.setExchangeRate(header.getExchangeRate());
    form.setCopperPrice(header.getCopperPrice());
    form.setZincPrice(header.getZincPrice());
    form.setAluminumPrice(header.getAluminumPrice());
    form.setSteelPrice(header.getSteelPrice());
    form.setSilverPrice(header.getSilverPrice());
    form.setGoldPrice(header.getGoldPrice());
    form.setSus304Price(header.getSus304Price());
    form.setSus316lPrice(header.getSus316lPrice());
    form.setOtherMaterial(header.getOtherMaterial());
    form.setBaseShipping(header.getBaseShipping());
    form.setSaleLink(header.getSaleLink());
    form.setRemark(header.getRemark());
    form.setIngestLogId(logId);
    if (!StringUtils.hasText(form.getCalcStatus())) {
      form.setCalcStatus("未核算");
    }
    form.setUpdatedAt(LocalDateTime.now());
    if (existing == null) {
      form.setCreatedAt(LocalDateTime.now());
      oaFormMapper.insert(form);
    } else {
      oaFormMapper.updateById(form);
    }
    return form;
  }

  private ItemInsertResult replaceItems(OaForm form, QuoteNormalizedDocument normalized) {
    oaFormItemMapper.delete(
        Wrappers.lambdaQuery(OaFormItem.class).eq(OaFormItem::getOaFormId, form.getId()));
    Map<String, Long> itemIdMap = new HashMap<>();
    List<Long> itemIdsByPosition = new ArrayList<>();
    for (QuoteNormalizedItem source : normalized.getItems()) {
      OaFormItem item = new OaFormItem();
      item.setOaFormId(form.getId());
      item.setExternalLineId(source.getExternalLineId());
      item.setSeq(source.getSeq());
      item.setProductName(source.getProductName());
      item.setCustomerDrawing(source.getCustomerDrawing());
      item.setCustomerCode(source.getCustomerCode());
      item.setMaterialNo(source.getMaterialNo());
      item.setSunlModel(source.getSunlModel());
      item.setSpec(source.getSpec());
      item.setProductAttr(source.getProductAttr());
      item.setBusinessType(source.getBusinessType());
      item.setFirstQuoteFlag(booleanToTinyint(source.getFirstQuoteFlag()));
      item.setCertificationRequired(booleanToTinyint(source.getCertificationRequired()));
      item.setOriginCountry(source.getOriginCountry());
      item.setTechnicianName(source.getTechnicianName());
      item.setPackageType(source.getPackageType());
      item.setPackageMethod(source.getPackageMethod());
      item.setPackageComponentCode(source.getPackageComponentCode());
      item.setPackageQty(source.getPackageQty());
      item.setShippingFee(source.getShippingFee());
      item.setSupportQty(source.getSupportQty());
      item.setAnnualVolume(source.getAnnualVolume());
      item.setProjectNo(source.getProjectNo());
      item.setProductStatus(source.getProductStatus());
      item.setScrapRate(source.getScrapRate());
      item.setUnitLaborCost(source.getUnitLaborCost());
      item.setTotalWithShip(source.getTotalWithShip());
      item.setTotalNoShip(source.getTotalNoShip());
      item.setMaterialCost(source.getMaterialCost());
      item.setLaborCost(source.getLaborCost());
      item.setManufacturingCost(source.getManufacturingCost());
      item.setManagementCost(source.getManagementCost());
      item.setValidMonth(source.getValidMonth());
      item.setSus304WeightG(source.getSus304WeightG());
      item.setSus316WeightG(source.getSus316WeightG());
      item.setCopperWeightG(source.getCopperWeightG());
      item.setClassificationStatus(source.getClassificationStatus());
      item.setBusinessUnitType(source.getBusinessUnitType());
      item.setValidDate(source.getValidDate());
      item.setCreatedAt(LocalDateTime.now());
      item.setUpdatedAt(LocalDateTime.now());
      oaFormItemMapper.insert(item);
      itemIdMap.put(itemKey(source.getExternalLineId(), source.getSeq()), item.getId());
      itemIdsByPosition.add(item.getId());
    }
    return new ItemInsertResult(itemIdMap, itemIdsByPosition);
  }

  private void replaceExtraFees(
      OaForm form, QuoteNormalizedDocument normalized, Map<String, Long> itemIdMap, Long logId) {
    oaFormExtraFeeMapper.delete(
        Wrappers.lambdaQuery(OaFormExtraFee.class).eq(OaFormExtraFee::getOaFormId, form.getId()));
    int fallback = 1;
    for (QuoteNormalizedExtraFee source : normalized.getExtraFees()) {
      OaFormExtraFee fee = new OaFormExtraFee();
      fee.setOaFormId(form.getId());
      fee.setOaFormItemId(itemIdMap.get(itemKey(source.getExternalLineId(), source.getItemSeq())));
      fee.setFeeScope(source.getScope());
      fee.setBusinessUnitType(form.getBusinessUnitType());
      fee.setFeeCode(defaultCode(source.getFeeCode(), "FEE_" + fallback++));
      fee.setFeeName(defaultCode(source.getFeeName(), fee.getFeeCode()));
      fee.setFeeCategory(source.getFeeCategory());
      fee.setAmount(source.getAmount());
      fee.setUnit(source.getUnit());
      fee.setRemark(source.getRemark());
      fee.setSourceType("INGEST");
      fee.setSourceFieldName(source.getSourceFieldName());
      fee.setSourceFieldPath(source.getSourceFieldPath());
      fee.setIngestLogId(logId);
      fee.setCreatedAt(LocalDateTime.now());
      fee.setUpdatedAt(LocalDateTime.now());
      oaFormExtraFeeMapper.insert(fee);
    }
  }

  private void replaceExtraFields(
      OaForm form, QuoteNormalizedDocument normalized, Map<String, Long> itemIdMap, Long logId) {
    // OA 原始表单扩展字段按 HEADER/ITEM 粒度分表落库，旧混合表仅保留历史兼容。
    oaFormHeaderExtraFieldMapper.delete(
        Wrappers.lambdaQuery(OaFormHeaderExtraField.class)
            .eq(OaFormHeaderExtraField::getOaFormId, form.getId()));
    oaFormItemExtraFieldMapper.delete(
        Wrappers.lambdaQuery(OaFormItemExtraField.class)
            .eq(OaFormItemExtraField::getOaFormId, form.getId()));
    int fallback = 1;
    for (QuoteNormalizedExtraField source : normalized.getExtraFields()) {
      String fieldCode = defaultCode(source.getFieldCode(), "FIELD_" + fallback++);
      if (SCOPE_ITEM.equals(source.getScope())) {
        insertItemExtraField(form, source, itemIdMap, logId, fieldCode);
      } else {
        insertHeaderExtraField(form, source, logId, fieldCode);
      }
    }
  }

  private void insertHeaderExtraField(
      OaForm form, QuoteNormalizedExtraField source, Long logId, String fieldCode) {
    OaFormHeaderExtraField field = new OaFormHeaderExtraField();
    field.setOaFormId(form.getId());
    field.setBusinessUnitType(defaultCode(form.getBusinessUnitType(), ""));
    field.setFieldCode(fieldCode);
    field.setFieldName(defaultCode(source.getFieldName(), field.getFieldCode()));
    applyExtraFieldValue(field, source);
    field.setSourceFieldName(source.getSourceFieldName());
    field.setSourceFieldPath(source.getSourceFieldPath());
    field.setIngestLogId(logId);
    field.setCreatedAt(LocalDateTime.now());
    field.setUpdatedAt(LocalDateTime.now());
    oaFormHeaderExtraFieldMapper.insert(field);
  }

  private void insertItemExtraField(
      OaForm form,
      QuoteNormalizedExtraField source,
      Map<String, Long> itemIdMap,
      Long logId,
      String fieldCode) {
    Long itemId = itemIdMap.get(itemKey(source.getExternalLineId(), source.getItemSeq()));
    if (itemId == null) {
      throw new QuoteIngestException("产品行扩展字段无法匹配产品行: " + fieldCode);
    }
    OaFormItemExtraField field = new OaFormItemExtraField();
    field.setOaFormId(form.getId());
    field.setOaFormItemId(itemId);
    field.setBusinessUnitType(defaultCode(form.getBusinessUnitType(), ""));
    field.setFieldCode(fieldCode);
    field.setFieldName(defaultCode(source.getFieldName(), field.getFieldCode()));
    applyExtraFieldValue(field, source);
    field.setSourceFieldName(source.getSourceFieldName());
    field.setSourceFieldPath(source.getSourceFieldPath());
    field.setIngestLogId(logId);
    field.setCreatedAt(LocalDateTime.now());
    field.setUpdatedAt(LocalDateTime.now());
    oaFormItemExtraFieldMapper.insert(field);
  }

  private void applyExtraFieldValue(OaFormHeaderExtraField field, QuoteNormalizedExtraField source) {
    field.setFieldValue(source.getFieldValue());
    field.setFieldValueNumber(source.getFieldValueNumber());
    field.setFieldValueDate(source.getFieldValueDate());
    field.setValueType(defaultCode(source.getValueType(), "TEXT"));
  }

  private void applyExtraFieldValue(OaFormItemExtraField field, QuoteNormalizedExtraField source) {
    field.setFieldValue(source.getFieldValue());
    field.setFieldValueNumber(source.getFieldValueNumber());
    field.setFieldValueDate(source.getFieldValueDate());
    field.setValueType(defaultCode(source.getValueType(), "TEXT"));
  }

  private void replaceBomStatuses(
      OaForm form, QuoteNormalizedDocument normalized, List<Long> itemIdsByPosition) {
    quoteBomStatusMapper.delete(
        Wrappers.lambdaQuery(QuoteBomStatus.class).eq(QuoteBomStatus::getOaFormId, form.getId()));
    for (int index = 0; index < normalized.getItems().size(); index++) {
      QuoteNormalizedItem source = normalized.getItems().get(index);
      QuoteBomStatus status = new QuoteBomStatus();
      status.setOaFormId(form.getId());
      status.setOaFormItemId(itemIdsByPosition.get(index));
      status.setOaNo(form.getOaNo());
      status.setProductCode(source.getMaterialNo());
      status.setProductModel(source.getSunlModel());
      status.setCustomerCode(source.getCustomerCode());
      status.setPackageType(source.getPackageType());
      status.setPackageMethod(source.getPackageMethod());
      status.setTechnicianName(source.getTechnicianName());
      status.setBomStatus(
          StringUtils.hasText(source.getMaterialNo())
              ? QuoteBomStatusCode.NOT_CHECKED.getCode()
              : QuoteBomStatusCode.NO_BOM.getCode());
      status.setCreatedAt(LocalDateTime.now());
      status.setUpdatedAt(LocalDateTime.now());
      quoteBomStatusMapper.insert(status);
    }
  }

  private record ItemInsertResult(Map<String, Long> itemIdMap, List<Long> itemIdsByPosition) {}

  private List<OaFormItem> listItems(Long oaFormId) {
    return oaFormItemMapper.selectList(
        Wrappers.lambdaQuery(OaFormItem.class)
            .eq(OaFormItem::getOaFormId, oaFormId)
            .orderByAsc(OaFormItem::getSeq)
            .orderByAsc(OaFormItem::getId));
  }

  private OaForm findExistingForm(String oaNo, QuoteIngestRequest request) {
    if (StringUtils.hasText(oaNo)) {
      OaForm byOaNo =
          oaFormMapper.selectOne(
              Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo.trim()));
      if (byOaNo != null) {
        return byOaNo;
      }
    }
    if (request != null
        && StringUtils.hasText(request.getSourceType())
        && StringUtils.hasText(request.getExternalFormNo())) {
      return oaFormMapper.selectOne(
          Wrappers.lambdaQuery(OaForm.class)
              .eq(OaForm::getSourceType, request.getSourceType().trim())
              .eq(OaForm::getExternalFormNo, request.getExternalFormNo().trim()));
    }
    return null;
  }

  private QuoteIngestResponse importedResponse(
      QuoteIngestLog log,
      QuoteNormalizedDocument normalized,
      OaForm form,
      ProductPropertyAnnualSyncResult annualUsageSyncResult) {
    QuoteIngestResponse response = baseResponse(log, normalized);
    response.setAccepted(true);
    response.setOaFormId(form.getId());
    response.setOaNo(form.getOaNo());
    response.setIngestStatus(log.getIngestStatus());
    attachAnnualUsageSyncResult(response, annualUsageSyncResult);
    return response;
  }

  private QuoteIngestResponse duplicateResponse(
      QuoteIngestLog log, QuoteNormalizedDocument normalized) {
    QuoteIngestResponse response = baseResponse(log, normalized);
    response.setAccepted(true);
    response.setOaNo(log.getOaNo());
    response.setIngestStatus(log.getIngestStatus());
    return response;
  }

  private QuoteIngestResponse rejectedResponse(
      QuoteIngestLog log, QuoteNormalizedDocument normalized, String message) {
    QuoteIngestResponse response = baseResponse(log, normalized);
    response.setAccepted(false);
    response.setIngestStatus(QuoteIngestStatus.REJECTED.getCode());
    if (response.getErrors().isEmpty()) {
      response.getErrors().add(new QuoteValidationError("$", "INGEST_REJECTED", message));
    }
    return response;
  }

  private QuoteIngestResponse baseResponse(QuoteIngestLog log, QuoteNormalizedDocument normalized) {
    QuoteNormalizedHeader header = normalized == null ? null : normalized.getHeader();
    QuoteIngestResponse response = new QuoteIngestResponse();
    response.setIngestLogId(log == null ? null : log.getId());
    response.setRequestId(log == null ? null : log.getRequestId());
    response.setIdempotencyKey(log == null ? null : log.getIdempotencyKey());
    response.setSourceType(header == null ? null : header.getSourceType());
    response.setExternalFormNo(header == null ? null : header.getExternalFormNo());
    response.setOaNo(header == null ? null : header.getOaNo());
    response.setProcessCode(header == null ? null : header.getProcessCode());
    response.setQuoteScenario(header == null ? null : header.getQuoteScenario());
    response.setClassificationStatus(header == null ? null : header.getClassificationStatus());
    response.setItemCount(normalized == null || normalized.getItems() == null ? 0 : normalized.getItems().size());
    response.setErrors(
        normalized == null || normalized.getErrors() == null
            ? new ArrayList<>()
            : new ArrayList<>(normalized.getErrors()));
    response.setWarnings(
        normalized == null || normalized.getWarnings() == null
            ? new ArrayList<>()
            : new ArrayList<>(normalized.getWarnings()));
    return response;
  }

  private void attachAnnualUsageSyncResult(
      QuoteIngestResponse response, ProductPropertyAnnualSyncResult result) {
    response.setAnnualUsageSyncResult(result);
    if (result == null) {
      return;
    }
    List<QuoteValidationWarning> warnings =
        response.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(response.getWarnings());
    for (String message : result.getErrorMessages()) {
      warnings.add(
          new QuoteValidationWarning(
              "annualUsageSync",
              "PRODUCT_PROPERTY_ANNUAL_USAGE_SYNC_ERROR",
              message));
    }
    for (String message : result.getWarnings()) {
      warnings.add(
          new QuoteValidationWarning(
              "annualUsageSync",
              "PRODUCT_PROPERTY_ANNUAL_USAGE_SYNC_WARNING",
              message));
    }
    response.setWarnings(warnings);
  }

  private String resolveRequestId(QuoteIngestRequest request) {
    if (request != null && StringUtils.hasText(request.getRequestId())) {
      return request.getRequestId().trim();
    }
    return "QI-" + UUID.randomUUID();
  }

  private String resolveIdempotencyKey(QuoteIngestRequest request) {
    if (request != null && StringUtils.hasText(request.getIdempotencyKey())) {
      return request.getIdempotencyKey().trim();
    }
    // 幂等键使用来源 + 外部单号 + 版本；Excel 与未来泛微都会适配成同一 DTO，避免同一外部单据重复入库。
    String sourceType = request == null ? "UNKNOWN" : defaultCode(request.getSourceType(), "UNKNOWN");
    String formNo =
        request == null
            ? "UNKNOWN"
            : defaultCode(coalesce(request.getExternalFormNo(), request.getOaNo()), "UNKNOWN");
    String version = request == null ? "1" : defaultCode(request.getVersion(), "1");
    return sourceType + ":" + formNo + ":" + version;
  }

  private String resolveOaNo(QuoteNormalizedHeader header, QuoteIngestRequest request) {
    String oaNo = header == null ? null : header.getOaNo();
    if (StringUtils.hasText(oaNo)) {
      return oaNo.trim();
    }
    if (request != null && StringUtils.hasText(request.getExternalFormNo())) {
      return request.getExternalFormNo().trim();
    }
    return "QI-" + UUID.randomUUID();
  }

  private boolean isCalculated(OaForm form) {
    return form != null && "已核算".equals(form.getCalcStatus());
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new QuoteIngestException("接入报文 JSON 序列化失败");
    }
  }

  private String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new QuoteIngestException("当前 JDK 不支持 SHA-256");
    }
  }

  private Integer booleanToTinyint(Boolean value) {
    return value == null ? null : (value ? 1 : 0);
  }

  private String itemKey(String externalLineId, Integer seq) {
    return StringUtils.hasText(externalLineId) ? "E:" + externalLineId.trim() : "S:" + seq;
  }

  private String defaultCode(String value, String defaultValue) {
    return StringUtils.hasText(value) ? value.trim() : defaultValue;
  }

  private String coalesce(String first, String second) {
    return StringUtils.hasText(first) ? first.trim() : second;
  }
}
