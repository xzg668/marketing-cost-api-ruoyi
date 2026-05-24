package com.sanhua.marketingcost.service.ingest;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogListItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestExtraFeeResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestExtraFieldResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestIngestSummaryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormExtraFee;
import com.sanhua.marketingcost.entity.OaFormHeaderExtraField;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaFormItemExtraField;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.enums.QuoteClassificationStatus;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import com.sanhua.marketingcost.mapper.OaFormExtraFeeMapper;
import com.sanhua.marketingcost.mapper.OaFormHeaderExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteIngestLogMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteRequestQueryServiceImpl implements QuoteRequestQueryService {
  private static final int DEFAULT_PAGE_NO = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;
  private static final int SUMMARY_LIMIT = 500;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormExtraFeeMapper oaFormExtraFeeMapper;
  private final OaFormHeaderExtraFieldMapper oaFormHeaderExtraFieldMapper;
  private final OaFormItemExtraFieldMapper oaFormItemExtraFieldMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final QuoteIngestLogMapper quoteIngestLogMapper;
  private final U9ProductPackagingTypeResolver productPackagingTypeResolver;

  public QuoteRequestQueryServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      OaFormExtraFeeMapper oaFormExtraFeeMapper,
      OaFormHeaderExtraFieldMapper oaFormHeaderExtraFieldMapper,
      OaFormItemExtraFieldMapper oaFormItemExtraFieldMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      QuoteIngestLogMapper quoteIngestLogMapper,
      U9ProductPackagingTypeResolver productPackagingTypeResolver) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormExtraFeeMapper = oaFormExtraFeeMapper;
    this.oaFormHeaderExtraFieldMapper = oaFormHeaderExtraFieldMapper;
    this.oaFormItemExtraFieldMapper = oaFormItemExtraFieldMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.quoteIngestLogMapper = quoteIngestLogMapper;
    this.productPackagingTypeResolver = productPackagingTypeResolver;
  }

  @Override
  public PageResult<QuoteRequestListItemResponse> pageRequests(
      Integer pageNo,
      Integer pageSize,
      String oaNo,
      String processCode,
      String sourceType,
      String classificationStatus) {
    LambdaQueryWrapper<OaForm> query =
        Wrappers.lambdaQuery(OaForm.class)
            .like(StringUtils.hasText(oaNo), OaForm::getOaNo, trimToNull(oaNo))
            .eq(StringUtils.hasText(processCode), OaForm::getProcessCode, trimToNull(processCode))
            .eq(StringUtils.hasText(sourceType), OaForm::getSourceType, trimToNull(sourceType))
            .eq(
                StringUtils.hasText(classificationStatus),
                OaForm::getClassificationStatus,
                trimToNull(classificationStatus))
            .orderByDesc(OaForm::getApplyDate)
            .orderByDesc(OaForm::getId);
    Page<OaForm> page = new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize));
    Page<OaForm> result = oaFormMapper.selectPage(page, query);
    List<QuoteRequestListItemResponse> rows = new ArrayList<>();
    for (OaForm form : result.getRecords()) {
      rows.add(toListItem(form));
    }
    return new PageResult<>(rows, result.getTotal());
  }

  @Override
  public QuoteRequestDetailResponse getRequestDetail(String oaNo) {
    OaForm form = requireForm(oaNo);
    return buildDetail(form);
  }

  @Override
  @Transactional
  public QuoteRequestDetailResponse confirmClassification(
      String oaNo, QuoteRequestConfirmClassificationRequest request) {
    OaForm form = requireForm(oaNo);
    String quoteScenario =
        trimToNull(request == null ? null : request.getQuoteScenario());
    if (quoteScenario == null) {
      quoteScenario = trimToNull(form.getQuoteScenario());
    }
    if (quoteScenario == null) {
      throw new QuoteIngestException("报价场景不能为空，无法确认分类");
    }
    String businessUnitType =
        trimToNull(request == null ? null : request.getBusinessUnitType());
    if (businessUnitType == null) {
      businessUnitType = trimToNull(form.getBusinessUnitType());
    }

    form.setQuoteScenario(quoteScenario);
    form.setClassificationStatus(QuoteClassificationStatus.CONFIRMED.getCode());
    if (businessUnitType != null) {
      form.setBusinessUnitType(businessUnitType);
    }
    form.setUpdatedAt(LocalDateTime.now());
    oaFormMapper.updateById(form);

    oaFormItemMapper.update(
        null,
        new UpdateWrapper<OaFormItem>()
            .eq("oa_form_id", form.getId())
            .set("classification_status", QuoteClassificationStatus.CONFIRMED.getCode())
            .set(businessUnitType != null, "business_unit_type", businessUnitType)
            .set("updated_at", LocalDateTime.now()));

    QuoteIngestLog log = findIngestLog(form);
    if (log != null) {
      log.setQuoteScenario(quoteScenario);
      log.setClassificationStatus(QuoteClassificationStatus.CONFIRMED.getCode());
      if (QuoteIngestStatus.CLASSIFY_PENDING.getCode().equals(log.getIngestStatus())) {
        log.setIngestStatus(QuoteIngestStatus.IMPORTED.getCode());
      }
      log.setUpdatedAt(LocalDateTime.now());
      quoteIngestLogMapper.updateById(log);
    }
    return buildDetail(form);
  }

  @Override
  public PageResult<QuoteIngestLogListItemResponse> pageLogs(
      Integer pageNo, Integer pageSize, String oaNo, String sourceType, String ingestStatus) {
    LambdaQueryWrapper<QuoteIngestLog> query =
        Wrappers.lambdaQuery(QuoteIngestLog.class)
            .like(StringUtils.hasText(oaNo), QuoteIngestLog::getOaNo, trimToNull(oaNo))
            .eq(StringUtils.hasText(sourceType), QuoteIngestLog::getSourceType, trimToNull(sourceType))
            .eq(
                StringUtils.hasText(ingestStatus),
                QuoteIngestLog::getIngestStatus,
                trimToNull(ingestStatus))
            .orderByDesc(QuoteIngestLog::getReceivedAt)
            .orderByDesc(QuoteIngestLog::getId);
    Page<QuoteIngestLog> page =
        quoteIngestLogMapper.selectPage(
            new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize)), query);
    List<QuoteIngestLogListItemResponse> rows = new ArrayList<>();
    for (QuoteIngestLog log : page.getRecords()) {
      rows.add(toLogListItem(log));
    }
    return new PageResult<>(rows, page.getTotal());
  }

  @Override
  public QuoteIngestLogDetailResponse getLogDetail(Long id) {
    if (id == null) {
      throw new QuoteIngestException("接入流水 ID 不能为空");
    }
    QuoteIngestLog log = quoteIngestLogMapper.selectById(id);
    if (log == null) {
      throw new QuoteIngestException("接入流水不存在: " + id);
    }
    return toLogDetail(log);
  }

  private QuoteRequestListItemResponse toListItem(OaForm form) {
    List<OaFormItem> items = listItems(form.getId());
    List<QuoteBomStatus> statuses = listBomStatus(form.getOaNo());
    String bomAggregateStatus = aggregateBomStatus(items.size(), statuses);
    QuoteIngestLog log = findIngestLog(form);
    QuoteRequestListItemResponse row = new QuoteRequestListItemResponse();
    row.setId(form.getId());
    row.setOaNo(form.getOaNo());
    row.setProcessCode(form.getProcessCode());
    row.setProcessName(form.getProcessName());
    row.setSourceType(form.getSourceType());
    row.setSourceSystem(form.getSourceSystem());
    row.setQuoteScenario(form.getQuoteScenario());
    row.setCustomer(form.getCustomer());
    row.setApplyDate(form.getApplyDate());
    row.setApplicantUnit(form.getApplicantUnit());
    row.setApplicantDept(form.getApplicantDept());
    row.setApplicantOffice(form.getApplicantOffice());
    row.setProductCount(items.size());
    row.setIngestStatus(log == null ? null : log.getIngestStatus());
    row.setClassificationStatus(form.getClassificationStatus());
    row.setBomAggregateStatus(bomAggregateStatus);
    row.setCalcStatus(form.getCalcStatus());
    row.setCalculable(isCalculable(form, items.size(), bomAggregateStatus));
    row.setIngestAt(log == null ? form.getCreatedAt() : log.getReceivedAt());
    return row;
  }

  private QuoteRequestDetailResponse buildDetail(OaForm form) {
    List<OaFormItem> items = listItems(form.getId());
    List<QuoteBomStatus> statuses = listBomStatus(form.getOaNo());
    Map<Long, QuoteBomStatus> statusByItemId = toStatusMap(statuses);
    String bomAggregateStatus = aggregateBomStatus(items.size(), statuses);

    QuoteRequestDetailResponse response = toDetailHeader(form);
    response.setBomAggregateStatus(bomAggregateStatus);
    response.setCalculable(isCalculable(form, items.size(), bomAggregateStatus));
    for (OaFormItem item : items) {
      response.getItems().add(toItemResponse(item, statusByItemId.get(item.getId())));
    }
    for (OaFormExtraFee fee : listExtraFees(form.getId())) {
      response.getExtraFees().add(toExtraFee(fee));
    }
    for (OaFormHeaderExtraField field : listHeaderExtraFields(form.getId())) {
      response.getExtraFields().add(toExtraField(field));
    }
    for (OaFormItemExtraField field : listItemExtraFields(form.getId())) {
      response.getExtraFields().add(toExtraField(field));
    }
    response.setIngestLog(toIngestSummary(findIngestLog(form)));
    return response;
  }

  private QuoteRequestDetailResponse toDetailHeader(OaForm form) {
    QuoteRequestDetailResponse response = new QuoteRequestDetailResponse();
    response.setId(form.getId());
    response.setOaNo(form.getOaNo());
    response.setSourceType(form.getSourceType());
    response.setSourceSystem(form.getSourceSystem());
    response.setExternalFormNo(form.getExternalFormNo());
    response.setProcessCode(form.getProcessCode());
    response.setProcessName(form.getProcessName());
    response.setQuoteScenario(form.getQuoteScenario());
    response.setExpenseProductCategory(form.getExpenseProductCategory());
    response.setFormType(form.getFormType());
    response.setApplyDate(form.getApplyDate());
    response.setCustomer(form.getCustomer());
    response.setSourceCompany(form.getSourceCompany());
    response.setSourceBusinessDivision(form.getSourceBusinessDivision());
    response.setApplicantDept(form.getApplicantDept());
    response.setApplicantOffice(form.getApplicantOffice());
    response.setApplicantName(form.getApplicantName());
    response.setApplicantUnit(form.getApplicantUnit());
    response.setUrgency(form.getUrgency());
    response.setProductAttr(form.getProductAttr());
    response.setPriceLinkMode(form.getPriceLinkMode());
    response.setOverseasSalesMode(form.getOverseasSalesMode());
    response.setTradeTerms(form.getTradeTerms());
    response.setExchangeRate(form.getExchangeRate());
    response.setCopperPrice(form.getCopperPrice());
    response.setZincPrice(form.getZincPrice());
    response.setAluminumPrice(form.getAluminumPrice());
    response.setSteelPrice(form.getSteelPrice());
    response.setSilverPrice(form.getSilverPrice());
    response.setGoldPrice(form.getGoldPrice());
    response.setSus304Price(form.getSus304Price());
    response.setSus316lPrice(form.getSus316lPrice());
    response.setOtherMaterial(form.getOtherMaterial());
    response.setBaseShipping(form.getBaseShipping());
    response.setCalcStatus(form.getCalcStatus());
    response.setCalcAt(form.getCalcAt());
    response.setClassificationStatus(form.getClassificationStatus());
    response.setSaleLink(form.getSaleLink());
    response.setRemark(form.getRemark());
    response.setBusinessUnitType(form.getBusinessUnitType());
    response.setAccountingPeriodMonth(form.getAccountingPeriodMonth());
    response.setCreatedAt(form.getCreatedAt());
    response.setUpdatedAt(form.getUpdatedAt());
    return response;
  }

  private QuoteRequestItemResponse toItemResponse(OaFormItem item, QuoteBomStatus status) {
    QuoteRequestItemResponse response = new QuoteRequestItemResponse();
    response.setId(item.getId());
    response.setSeq(item.getSeq());
    response.setExternalLineId(item.getExternalLineId());
    response.setProductName(item.getProductName());
    response.setMaterialNo(item.getMaterialNo());
    response.setSunlModel(item.getSunlModel());
    response.setCustomerCode(item.getCustomerCode());
    response.setCustomerDrawing(item.getCustomerDrawing());
    response.setSpec(item.getSpec());
    response.setProductAttr(item.getProductAttr());
    response.setBusinessType(item.getBusinessType());
    response.setFirstQuoteFlag(item.getFirstQuoteFlag());
    response.setCertificationRequired(item.getCertificationRequired());
    response.setOriginCountry(item.getOriginCountry());
    response.setPackageType(item.getPackageType());
    response.setPackageMethod(item.getPackageMethod());
    response.setPackageComponentCode(item.getPackageComponentCode());
    response.setPackageQty(item.getPackageQty());
    response.setShippingFee(item.getShippingFee());
    response.setSupportQty(item.getSupportQty());
    response.setAnnualVolume(item.getAnnualVolume());
    response.setProjectNo(item.getProjectNo());
    response.setProductStatus(item.getProductStatus());
    response.setScrapRate(item.getScrapRate());
    response.setUnitLaborCost(item.getUnitLaborCost());
    response.setTechnicianName(item.getTechnicianName());
    response.setClassificationStatus(item.getClassificationStatus());
    response.setTotalWithShip(item.getTotalWithShip());
    response.setTotalNoShip(item.getTotalNoShip());
    response.setMaterialCost(item.getMaterialCost());
    response.setLaborCost(item.getLaborCost());
    response.setManufacturingCost(item.getManufacturingCost());
    response.setManagementCost(item.getManagementCost());
    response.setValidMonth(item.getValidMonth());
    response.setSus304WeightG(item.getSus304WeightG());
    response.setSus316WeightG(item.getSus316WeightG());
    response.setCopperWeightG(item.getCopperWeightG());
    response.setBusinessUnitType(item.getBusinessUnitType());
    response.setValidDate(item.getValidDate());
    response.setBomStatus(toBomStatusResponse(item, status));
    return response;
  }

  private QuoteBomStatusItemResponse toBomStatusResponse(OaFormItem item, QuoteBomStatus status) {
    QuoteBomStatusItemResponse response = new QuoteBomStatusItemResponse();
    response.setSeq(item.getSeq());
    response.setOaFormItemId(item.getId());
    response.setProductCode(item.getMaterialNo());
    response.setProductModel(item.getSunlModel());
    if (status == null) {
      applyProductPackagingType(response);
      response.setBomStatus(
          StringUtils.hasText(item.getMaterialNo())
              ? QuoteBomStatusCode.NOT_CHECKED.getCode()
              : QuoteBomStatusCode.NO_BOM.getCode());
      response.setErrorMessage(
          StringUtils.hasText(item.getMaterialNo()) ? null : "产品料号为空，无法自动匹配 BOM");
      return response;
    }
    response.setId(status.getId());
    response.setProductCode(status.getProductCode());
    response.setProductModel(status.getProductModel());
    applyProductPackagingType(response);
    response.setBomStatus(status.getBomStatus());
    response.setBomSource(status.getBomSource());
    response.setBomPurpose(status.getBomPurpose());
    response.setBomVersion(status.getBomVersion());
    response.setEffectiveFrom(status.getEffectiveFrom());
    response.setEffectiveTo(status.getEffectiveTo());
    response.setCheckedAt(status.getCheckedAt());
    response.setSyncBatchId(status.getSyncBatchId());
    response.setManualTaskNo(status.getManualTaskNo());
    response.setSupplementTaskId(status.getSupplementTaskId());
    response.setErrorMessage(status.getErrorMessage());
    return response;
  }

  private void applyProductPackagingType(QuoteBomStatusItemResponse response) {
    U9ProductPackagingTypeResolver.Result result =
        productPackagingTypeResolver.resolve(response.getProductCode());
    response.setProductPackagingType(result.productPackagingType());
    response.setMainCategoryCode(result.mainCategoryCode());
  }

  private QuoteRequestExtraFeeResponse toExtraFee(OaFormExtraFee fee) {
    QuoteRequestExtraFeeResponse response = new QuoteRequestExtraFeeResponse();
    response.setId(fee.getId());
    response.setOaFormItemId(fee.getOaFormItemId());
    response.setFeeScope(fee.getFeeScope());
    response.setBusinessUnitType(fee.getBusinessUnitType());
    response.setFeeCode(fee.getFeeCode());
    response.setFeeName(fee.getFeeName());
    response.setFeeCategory(fee.getFeeCategory());
    response.setAmount(fee.getAmount());
    response.setUnit(fee.getUnit());
    response.setTaxIncluded(fee.getTaxIncluded());
    response.setAllocationMethod(fee.getAllocationMethod());
    response.setAllocatedAmount(fee.getAllocatedAmount());
    response.setBearer(fee.getBearer());
    response.setProjectNo(fee.getProjectNo());
    response.setSourceType(fee.getSourceType());
    response.setSourceFieldName(fee.getSourceFieldName());
    response.setSourceFieldPath(fee.getSourceFieldPath());
    response.setRemark(fee.getRemark());
    return response;
  }

  private QuoteRequestExtraFieldResponse toExtraField(OaFormHeaderExtraField field) {
    QuoteRequestExtraFieldResponse response = new QuoteRequestExtraFieldResponse();
    response.setId(field.getId());
    response.setFieldCode(field.getFieldCode());
    response.setFieldName(field.getFieldName());
    response.setFieldValue(field.getFieldValue());
    response.setFieldValueNumber(field.getFieldValueNumber());
    response.setFieldValueDate(field.getFieldValueDate());
    response.setValueType(field.getValueType());
    response.setSourceFieldName(field.getSourceFieldName());
    response.setSourceFieldPath(field.getSourceFieldPath());
    return response;
  }

  private QuoteRequestExtraFieldResponse toExtraField(OaFormItemExtraField field) {
    QuoteRequestExtraFieldResponse response = new QuoteRequestExtraFieldResponse();
    response.setId(field.getId());
    response.setOaFormItemId(field.getOaFormItemId());
    response.setFieldCode(field.getFieldCode());
    response.setFieldName(field.getFieldName());
    response.setFieldValue(field.getFieldValue());
    response.setFieldValueNumber(field.getFieldValueNumber());
    response.setFieldValueDate(field.getFieldValueDate());
    response.setValueType(field.getValueType());
    response.setSourceFieldName(field.getSourceFieldName());
    response.setSourceFieldPath(field.getSourceFieldPath());
    return response;
  }

  private QuoteRequestIngestSummaryResponse toIngestSummary(QuoteIngestLog log) {
    if (log == null) {
      return null;
    }
    QuoteRequestIngestSummaryResponse response = new QuoteRequestIngestSummaryResponse();
    response.setId(log.getId());
    response.setRequestId(log.getRequestId());
    response.setIdempotencyKey(log.getIdempotencyKey());
    response.setSourceType(log.getSourceType());
    response.setSourceSystem(log.getSourceSystem());
    response.setExternalFormNo(log.getExternalFormNo());
    response.setIngestStatus(log.getIngestStatus());
    response.setClassificationStatus(log.getClassificationStatus());
    response.setPayloadSummary(abbreviate(log.getPayloadJson()));
    response.setNormalizedSummary(abbreviate(log.getNormalizedJson()));
    response.setValidationErrors(log.getValidationErrors());
    response.setWarningMessages(log.getWarningMessages());
    response.setErrorMessage(log.getErrorMessage());
    response.setReceivedAt(log.getReceivedAt());
    response.setProcessedAt(log.getProcessedAt());
    return response;
  }

  private QuoteIngestLogListItemResponse toLogListItem(QuoteIngestLog log) {
    QuoteIngestLogListItemResponse response = new QuoteIngestLogListItemResponse();
    response.setId(log.getId());
    response.setRequestId(log.getRequestId());
    response.setIdempotencyKey(log.getIdempotencyKey());
    response.setSourceType(log.getSourceType());
    response.setSourceSystem(log.getSourceSystem());
    response.setExternalFormNo(log.getExternalFormNo());
    response.setOaNo(log.getOaNo());
    response.setProcessCode(log.getProcessCode());
    response.setProcessName(log.getProcessName());
    response.setQuoteScenario(log.getQuoteScenario());
    response.setIngestStatus(log.getIngestStatus());
    response.setClassificationStatus(log.getClassificationStatus());
    response.setErrorMessage(log.getErrorMessage());
    response.setReceivedAt(log.getReceivedAt());
    response.setProcessedAt(log.getProcessedAt());
    return response;
  }

  private QuoteIngestLogDetailResponse toLogDetail(QuoteIngestLog log) {
    QuoteIngestLogDetailResponse response = new QuoteIngestLogDetailResponse();
    response.setId(log.getId());
    response.setRequestId(log.getRequestId());
    response.setIdempotencyKey(log.getIdempotencyKey());
    response.setPayloadHash(log.getPayloadHash());
    response.setSourceType(log.getSourceType());
    response.setSourceSystem(log.getSourceSystem());
    response.setExternalFormNo(log.getExternalFormNo());
    response.setOaNo(log.getOaNo());
    response.setProcessCode(log.getProcessCode());
    response.setProcessName(log.getProcessName());
    response.setQuoteScenario(log.getQuoteScenario());
    response.setIngestStatus(log.getIngestStatus());
    response.setClassificationStatus(log.getClassificationStatus());
    response.setPayloadJson(log.getPayloadJson());
    response.setNormalizedJson(log.getNormalizedJson());
    response.setValidationErrors(log.getValidationErrors());
    response.setWarningMessages(log.getWarningMessages());
    response.setErrorMessage(log.getErrorMessage());
    response.setReceivedAt(log.getReceivedAt());
    response.setProcessedAt(log.getProcessedAt());
    response.setCreatedBy(log.getCreatedBy());
    response.setCreatedAt(log.getCreatedAt());
    response.setUpdatedAt(log.getUpdatedAt());
    return response;
  }

  private OaForm requireForm(String oaNo) {
    String normalized = trimToNull(oaNo);
    if (normalized == null) {
      throw new QuoteIngestException("报价单号不能为空");
    }
    OaForm form =
        oaFormMapper.selectOne(Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private List<OaFormItem> listItems(Long oaFormId) {
    return oaFormItemMapper.selectList(
        Wrappers.lambdaQuery(OaFormItem.class)
            .eq(OaFormItem::getOaFormId, oaFormId)
            .orderByAsc(OaFormItem::getSeq)
            .orderByAsc(OaFormItem::getId));
  }

  private List<OaFormExtraFee> listExtraFees(Long oaFormId) {
    return oaFormExtraFeeMapper.selectList(
        Wrappers.lambdaQuery(OaFormExtraFee.class)
            .eq(OaFormExtraFee::getOaFormId, oaFormId)
            .orderByAsc(OaFormExtraFee::getOaFormItemId)
            .orderByAsc(OaFormExtraFee::getId));
  }

  private List<OaFormHeaderExtraField> listHeaderExtraFields(Long oaFormId) {
    return oaFormHeaderExtraFieldMapper.selectList(
        Wrappers.lambdaQuery(OaFormHeaderExtraField.class)
            .eq(OaFormHeaderExtraField::getOaFormId, oaFormId)
            .orderByAsc(OaFormHeaderExtraField::getId));
  }

  private List<OaFormItemExtraField> listItemExtraFields(Long oaFormId) {
    return oaFormItemExtraFieldMapper.selectList(
        Wrappers.lambdaQuery(OaFormItemExtraField.class)
            .eq(OaFormItemExtraField::getOaFormId, oaFormId)
            .orderByAsc(OaFormItemExtraField::getOaFormItemId)
            .orderByAsc(OaFormItemExtraField::getId));
  }

  private List<QuoteBomStatus> listBomStatus(String oaNo) {
    return quoteBomStatusMapper.selectList(
        Wrappers.lambdaQuery(QuoteBomStatus.class).eq(QuoteBomStatus::getOaNo, oaNo));
  }

  private QuoteIngestLog findIngestLog(OaForm form) {
    if (form == null) {
      return null;
    }
    if (form.getIngestLogId() != null) {
      return quoteIngestLogMapper.selectById(form.getIngestLogId());
    }
    List<QuoteIngestLog> logs =
        quoteIngestLogMapper.selectList(
            Wrappers.lambdaQuery(QuoteIngestLog.class)
                .eq(QuoteIngestLog::getOaNo, form.getOaNo())
                .orderByDesc(QuoteIngestLog::getId));
    return logs.isEmpty() ? null : logs.get(0);
  }

  private Map<Long, QuoteBomStatus> toStatusMap(List<QuoteBomStatus> statuses) {
    Map<Long, QuoteBomStatus> map = new LinkedHashMap<>();
    for (QuoteBomStatus status : statuses) {
      map.put(status.getOaFormItemId(), status);
    }
    return map;
  }

  private String aggregateBomStatus(int itemCount, List<QuoteBomStatus> statuses) {
    if (itemCount <= 0 || statuses.isEmpty()) {
      return QuoteBomStatusCode.NOT_CHECKED.getCode();
    }
    if (hasStatus(statuses, QuoteBomStatusCode.CHECK_FAILED.getCode())) {
      return QuoteBomStatusCode.CHECK_FAILED.getCode();
    }
    if (hasStatus(statuses, QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode())) {
      return QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode();
    }
    if (hasStatus(statuses, QuoteBomStatusCode.ENTRY_PENDING.getCode())) {
      return QuoteBomStatusCode.ENTRY_PENDING.getCode();
    }
    if (hasStatus(statuses, QuoteBomStatusCode.NO_BOM.getCode())) {
      return QuoteBomStatusCode.NO_BOM.getCode();
    }
    if (hasStatus(statuses, QuoteBomStatusCode.EXPIRED.getCode())) {
      return QuoteBomStatusCode.EXPIRED.getCode();
    }
    if (statuses.size() < itemCount) {
      return QuoteBomStatusCode.NOT_CHECKED.getCode();
    }
    if (allStatus(statuses, QuoteBomStatusCode.SYNCED.getCode())) {
      return QuoteBomStatusCode.SYNCED.getCode();
    }
    if (allStatus(statuses, QuoteBomStatusCode.MANUAL_ENTERED.getCode())) {
      return QuoteBomStatusCode.MANUAL_ENTERED.getCode();
    }
    if (allCostReadyStatus(statuses)) {
      return QuoteBomStatusCode.SYNCED.getCode();
    }
    return QuoteBomStatusCode.NOT_CHECKED.getCode();
  }

  private boolean isCalculable(OaForm form, int itemCount, String bomAggregateStatus) {
    return itemCount > 0
        && QuoteClassificationStatus.CONFIRMED.getCode().equals(form.getClassificationStatus())
        && (QuoteBomStatusCode.SYNCED.getCode().equals(bomAggregateStatus)
            || QuoteBomStatusCode.MANUAL_ENTERED.getCode().equals(bomAggregateStatus));
  }

  private boolean hasStatus(List<QuoteBomStatus> statuses, String code) {
    for (QuoteBomStatus status : statuses) {
      if (code.equals(status.getBomStatus())) {
        return true;
      }
    }
    return false;
  }

  private boolean allStatus(List<QuoteBomStatus> statuses, String code) {
    for (QuoteBomStatus status : statuses) {
      if (!code.equals(status.getBomStatus())) {
        return false;
      }
    }
    return true;
  }

  private boolean allCostReadyStatus(List<QuoteBomStatus> statuses) {
    for (QuoteBomStatus status : statuses) {
      if (!QuoteBomStatusCode.SYNCED.getCode().equals(status.getBomStatus())
          && !QuoteBomStatusCode.MANUAL_ENTERED.getCode().equals(status.getBomStatus())) {
        return false;
      }
    }
    return true;
  }

  private long normalizePageNo(Integer pageNo) {
    if (pageNo == null || pageNo < 1) {
      return DEFAULT_PAGE_NO;
    }
    return pageNo.longValue();
  }

  private long normalizePageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String abbreviate(String value) {
    if (value == null || value.length() <= SUMMARY_LIMIT) {
      return value;
    }
    return value.substring(0, SUMMARY_LIMIT);
  }
}
