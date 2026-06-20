package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuotePricePrepareWorkbenchServiceImpl implements QuotePricePrepareWorkbenchService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 200;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomConfirmationMapper bomConfirmationMapper;
  private final QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper;
  private final PricePrepareService pricePrepareService;
  private final PricePrepareQueryService pricePrepareQueryService;
  private final PricePrepareReadinessService pricePrepareReadinessService;

  public QuotePricePrepareWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomConfirmationMapper bomConfirmationMapper,
      QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper,
      PricePrepareService pricePrepareService,
      PricePrepareQueryService pricePrepareQueryService,
      PricePrepareReadinessService pricePrepareReadinessService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.bomConfirmationMapper = bomConfirmationMapper;
    this.priceTypeConfirmBatchMapper = priceTypeConfirmBatchMapper;
    this.pricePrepareService = pricePrepareService;
    this.pricePrepareQueryService = pricePrepareQueryService;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
  }

  @Override
  public QuotePricePrepareWorkbenchResponse getPricePrepare(
      String oaNo, Long oaFormItemId, String periodMonth) {
    Scope scope = resolveScope(oaNo, oaFormItemId, periodMonth);
    QuotePriceTypeConfirmBatch latestConfirm = latestConfirmedPriceType(scope);
    QuotePricePrepareWorkbenchResponse response = queryResponse(scope);
    response.setLatestPriceTypeConfirmNo(latestConfirm == null ? null : latestConfirm.getConfirmNo());
    return response;
  }

  @Override
  public QuotePricePrepareWorkbenchResponse generate(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request) {
    Scope scope = resolveScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    requireBomConfirmed(scope);
    QuotePriceTypeConfirmBatch confirm =
        requireConfirmedPriceType(scope, request == null ? null : request.getPriceTypeConfirmNo());
    com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest generateRequest =
        new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    generateRequest.setOaNo(scope.oaNo());
    generateRequest.setOaFormItemId(scope.oaFormItemId());
    generateRequest.setTopProductCode(scope.topProductCode());
    generateRequest.setTopProductCodes(java.util.List.of(scope.topProductCode()));
    generateRequest.setPriceTypeConfirmNo(confirm.getConfirmNo());
    generateRequest.setPeriodMonth(scope.periodMonth());
    generateRequest.setPriceAsOfTime(request == null ? null : request.getPriceAsOfTime());
    generateRequest.setBusinessUnitType(scope.businessUnitType());
    PricePrepareGenerateResult result = pricePrepareService.generate(generateRequest);
    QuotePricePrepareWorkbenchResponse response = queryResponse(scope);
    response.setLatestPriceTypeConfirmNo(confirm.getConfirmNo());
    response.setGeneratedResult(result);
    return response;
  }

  private QuotePricePrepareWorkbenchResponse queryResponse(Scope scope) {
    QuotePricePrepareWorkbenchResponse response = new QuotePricePrepareWorkbenchResponse();
    response.setOaNo(scope.oaNo());
    response.setOaFormItemId(scope.oaFormItemId());
    response.setTopProductCode(scope.topProductCode());
    response.setPeriodMonth(scope.periodMonth());
    QuotePriceTypeConfirmBatch latestConfirm = latestConfirmedPriceType(scope);
    String latestConfirmNo = latestConfirm == null ? null : latestConfirm.getConfirmNo();
    response.setLatestPriceTypeConfirmNo(latestConfirmNo);
    response.setReadiness(
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.topProductCode(),
            scope.periodMonth(),
            latestConfirmNo));
    PricePrepareBatchQueryRequest batchQuery = new PricePrepareBatchQueryRequest();
    batchQuery.setOaNo(scope.oaNo());
    batchQuery.setOaFormItemId(scope.oaFormItemId());
    batchQuery.setTopProductCode(scope.topProductCode());
    batchQuery.setPeriodMonth(scope.periodMonth());
    batchQuery.setPriceTypeConfirmNo(latestConfirmNo);
    batchQuery.setPage(DEFAULT_PAGE);
    batchQuery.setPageSize(DEFAULT_PAGE_SIZE);
    response.setBatches(pricePrepareQueryService.pageBatches(batchQuery));
    PricePrepareItemQueryRequest itemQuery = new PricePrepareItemQueryRequest();
    itemQuery.setOaNo(scope.oaNo());
    itemQuery.setOaFormItemId(scope.oaFormItemId());
    itemQuery.setTopProductCode(scope.topProductCode());
    itemQuery.setPeriodMonth(scope.periodMonth());
    itemQuery.setPriceTypeConfirmNo(latestConfirmNo);
    itemQuery.setPage(DEFAULT_PAGE);
    itemQuery.setPageSize(DEFAULT_PAGE_SIZE);
    response.setItems(pricePrepareQueryService.pageItems(itemQuery));
    PricePrepareGapQueryRequest gapQuery = new PricePrepareGapQueryRequest();
    gapQuery.setOaNo(scope.oaNo());
    gapQuery.setOaFormItemId(scope.oaFormItemId());
    gapQuery.setTopProductCode(scope.topProductCode());
    gapQuery.setPeriodMonth(scope.periodMonth());
    gapQuery.setPriceTypeConfirmNo(latestConfirmNo);
    gapQuery.setPage(DEFAULT_PAGE);
    gapQuery.setPageSize(DEFAULT_PAGE_SIZE);
    response.setGaps(pricePrepareQueryService.pageGaps(gapQuery));
    return response;
  }

  private Scope resolveScope(String oaNo, Long oaFormItemId, String periodMonth) {
    String oaNoValue = requireText(oaNo, "报价单号");
    OaForm form =
        oaFormMapper.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNoValue));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + oaNoValue);
    }
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    String topProductCode = requireText(item.getMaterialNo(), "报价产品料号");
    String period =
        StringUtils.hasText(periodMonth)
            ? CostPricingPeriodUtils.requireCurrentPricingMonth(periodMonth)
            : resolveDefaultPeriod(form);
    return new Scope(
        oaNoValue,
        oaFormItemId,
        topProductCode,
        period,
        firstText(item.getBusinessUnitType(), form.getBusinessUnitType()));
  }

  private void requireBomConfirmed(Scope scope) {
    if (latestBomConfirmation(scope) == null) {
      throw new QuoteIngestException("请先确认当前产品行 BOM 后再执行价格准备");
    }
  }

  private QuoteBomConfirmation latestBomConfirmation(Scope scope) {
    return bomConfirmationMapper.selectOne(
        Wrappers.<QuoteBomConfirmation>lambdaQuery()
            .eq(QuoteBomConfirmation::getOaNo, scope.oaNo())
            .eq(QuoteBomConfirmation::getOaFormItemId, scope.oaFormItemId())
            .eq(QuoteBomConfirmation::getTopProductCode, scope.topProductCode())
            .eq(QuoteBomConfirmation::getPeriodMonth, scope.periodMonth())
            .eq(QuoteBomConfirmation::getConfirmStatus, QuoteBomConfirmation.STATUS_CONFIRMED)
            .orderByDesc(QuoteBomConfirmation::getConfirmedAt)
            .orderByDesc(QuoteBomConfirmation::getId)
            .last("LIMIT 1"));
  }

  private QuotePriceTypeConfirmBatch requireConfirmedPriceType(
      Scope scope, String priceTypeConfirmNo) {
    QuotePriceTypeConfirmBatch batch =
        StringUtils.hasText(priceTypeConfirmNo)
            ? priceTypeByConfirmNo(scope, priceTypeConfirmNo.trim())
            : latestConfirmedPriceType(scope);
    if (batch == null) {
      throw new QuoteIngestException("请先确认当前产品行价格类型后再执行价格准备");
    }
    if (!QuotePriceTypeConfirmBatch.STATUS_CONFIRMED.equals(batch.getStatus())) {
      throw new QuoteIngestException("价格类型确认批次未确认，无法执行价格准备");
    }
    if (batch.getGapCount() != null && batch.getGapCount() > 0) {
      throw new QuoteIngestException("价格类型仍存在缺口，无法执行价格准备");
    }
    return batch;
  }

  private QuotePriceTypeConfirmBatch priceTypeByConfirmNo(Scope scope, String confirmNo) {
    return priceTypeConfirmBatchMapper.selectOne(
        Wrappers.<QuotePriceTypeConfirmBatch>lambdaQuery()
            .eq(QuotePriceTypeConfirmBatch::getConfirmNo, confirmNo)
            .eq(QuotePriceTypeConfirmBatch::getOaNo, scope.oaNo())
            .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, scope.oaFormItemId())
            .eq(QuotePriceTypeConfirmBatch::getProductCode, scope.topProductCode())
            .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, scope.periodMonth())
            .last("LIMIT 1"));
  }

  private QuotePriceTypeConfirmBatch latestConfirmedPriceType(Scope scope) {
    return priceTypeConfirmBatchMapper.selectOne(
        Wrappers.<QuotePriceTypeConfirmBatch>lambdaQuery()
            .eq(QuotePriceTypeConfirmBatch::getOaNo, scope.oaNo())
            .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, scope.oaFormItemId())
            .eq(QuotePriceTypeConfirmBatch::getProductCode, scope.topProductCode())
            .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, scope.periodMonth())
            .eq(QuotePriceTypeConfirmBatch::getStatus, QuotePriceTypeConfirmBatch.STATUS_CONFIRMED)
            .orderByDesc(QuotePriceTypeConfirmBatch::getConfirmedAt)
            .orderByDesc(QuotePriceTypeConfirmBatch::getId)
            .last("LIMIT 1"));
  }

  private String resolveDefaultPeriod(OaForm form) {
    if (StringUtils.hasText(form.getAccountingPeriodMonth())) {
      return CostPricingPeriodUtils.requireCurrentPricingMonth(form.getAccountingPeriodMonth());
    }
    if (form.getApplyDate() != null) {
      return CostPricingPeriodUtils.requireCurrentPricingMonth(YearMonth.from(form.getApplyDate()).toString());
    }
    return CostPricingPeriodUtils.requireCurrentPricingMonth(null);
  }

  private String requireText(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new QuoteIngestException(label + "不能为空");
    }
    return value.trim();
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private record Scope(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth,
      String businessUnitType) {}
}
