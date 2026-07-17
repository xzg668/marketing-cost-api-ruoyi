package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareDifferenceResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareDifferenceSummary;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareScenarioResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuotePricePrepareWorkbenchServiceImpl implements QuotePricePrepareWorkbenchService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 200;
  private static final int SCENARIO_PAGE_SIZE = 500;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomConfirmationMapper bomConfirmationMapper;
  private final QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper;
  private final PricePrepareService pricePrepareService;
  private final FinancePricePrepareService financePricePrepareService;
  private final FinanceQuoteBasePriceService financeQuoteBasePriceService;
  private final PricePrepareQueryService pricePrepareQueryService;
  private final PricePrepareReadinessService pricePrepareReadinessService;

  public QuotePricePrepareWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomConfirmationMapper bomConfirmationMapper,
      QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper,
      PricePrepareService pricePrepareService,
      FinancePricePrepareService financePricePrepareService,
      FinanceQuoteBasePriceService financeQuoteBasePriceService,
      PricePrepareQueryService pricePrepareQueryService,
      PricePrepareReadinessService pricePrepareReadinessService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.bomConfirmationMapper = bomConfirmationMapper;
    this.priceTypeConfirmBatchMapper = priceTypeConfirmBatchMapper;
    this.pricePrepareService = pricePrepareService;
    this.financePricePrepareService = financePricePrepareService;
    this.financeQuoteBasePriceService = financeQuoteBasePriceService;
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
  @Transactional(readOnly = true)
  public QuotePricePrepareWorkbenchResponse checkPriceSources(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request) {
    Scope scope =
        resolveScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    requireBomConfirmed(scope);
    QuotePriceTypeConfirmBatch confirm =
        requireConfirmedPriceType(
            scope, request == null ? null : request.getPriceTypeConfirmNo());
    QuotePricePrepareWorkbenchResponse persisted = queryResponse(scope);
    if (hasCompletedScenarioPair(persisted)) {
      persisted.setLatestPriceTypeConfirmNo(confirm.getConfirmNo());
      return persisted;
    }
    PricePrepareCalculationResult calculation =
        pricePrepareService.calculate(buildOaGenerateRequest(scope, confirm, request, true));
    return previewResponse(scope, confirm, calculation);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuotePricePrepareWorkbenchResponse generate(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request) {
    Scope scope = resolveScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    requireBomConfirmed(scope);
    QuotePriceTypeConfirmBatch confirm =
        requireConfirmedPriceType(scope, request == null ? null : request.getPriceTypeConfirmNo());
    if (request != null
        && request.getScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE) {
      if (request.getVariableOverrides() != null && !request.getVariableOverrides().isEmpty()) {
        throw new QuoteIngestException("财务Cu价格必须读取当月财务基准，不接受页面传价");
      }
      FinancePricePrepareGenerateResult finance = financePricePrepareService.generateFromOa(
          requireText(request.getSourcePrepareNo(), "OA价格准备批次号"));
      PricePrepareGenerateResult result = finance.prepareResult();
      validateFinanceResult(scope, confirm, result);
      QuotePricePrepareWorkbenchResponse response = queryResponse(scope);
      response.setLatestPriceTypeConfirmNo(confirm.getConfirmNo());
      response.setGeneratedResult(result);
      return response;
    }
    com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest generateRequest =
        buildOaGenerateRequest(scope, confirm, request, false);
    PricePrepareGenerateResult result = pricePrepareService.generate(generateRequest);
    FinancePricePrepareGenerateResult finance = null;
    if (shouldGenerateFinanceComparison(request, result)) {
      finance = financePricePrepareService.generateFromOa(result.getPrepareNo());
      validateFinanceResult(scope, confirm, finance.prepareResult());
    }
    QuotePricePrepareWorkbenchResponse response = queryResponse(scope);
    response.setLatestPriceTypeConfirmNo(confirm.getConfirmNo());
    response.setGeneratedResult(result);
    if (finance != null) {
      response.setFinanceGeneratedResult(finance.prepareResult());
      response.setFinanceBasePriceId(finance.financeBasePriceId());
      response.setFinanceCuPricePerKg(finance.financeCuPricePerKg());
      response.setFinanceCuPricePerTon(toPerTon(finance.financeCuPricePerKg()));
    }
    return response;
  }

  private com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest
      buildOaGenerateRequest(
          Scope scope,
          QuotePriceTypeConfirmBatch confirm,
          QuotePricePrepareGenerateRequest request,
          boolean preview) {
    com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest generateRequest =
        new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    generateRequest.setOaNo(scope.oaNo());
    generateRequest.setOaFormItemId(scope.oaFormItemId());
    generateRequest.setTopProductCode(scope.topProductCode());
    generateRequest.setTopProductCodes(List.of(scope.topProductCode()));
    generateRequest.setPriceTypeConfirmNo(confirm.getConfirmNo());
    generateRequest.setPeriodMonth(scope.periodMonth());
    generateRequest.setPriceAsOfTime(request == null ? null : request.getPriceAsOfTime());
    generateRequest.setBusinessUnitType(scope.businessUnitType());
    if (preview) {
      generateRequest.setScenarioType(QuotePriceScenarioType.OA_LOCKED);
    } else if (request != null) {
      generateRequest.setScenarioType(request.getScenarioType());
      generateRequest.setScenarioGroupNo(request.getScenarioGroupNo());
      generateRequest.setSourcePrepareNo(request.getSourcePrepareNo());
      generateRequest.setVariableOverrides(request.getVariableOverrides());
    }
    return generateRequest;
  }

  private QuotePricePrepareWorkbenchResponse previewResponse(
      Scope scope,
      QuotePriceTypeConfirmBatch confirm,
      PricePrepareCalculationResult calculation) {
    QuotePricePrepareWorkbenchResponse response = queryResponse(scope);
    response.setLatestPriceTypeConfirmNo(confirm.getConfirmNo());
    List<PricePrepareItem> items =
        calculation == null || calculation.getItems() == null
            ? List.of()
            : calculation.getItems();
    List<PricePrepareGap> gapRecords =
        calculation == null || calculation.getGaps() == null
            ? List.of()
            : calculation.getGaps();
    response.setItems(new PricePrepareItemPageResponse(items.size(), items));
    PricePrepareGapPageResponse gaps =
        new PricePrepareGapPageResponse(gapRecords.size(), gapRecords);
    response.setGaps(gaps);
    PricePrepareGenerateResult preview = calculation == null ? null : calculation.getSummary();
    response.setReadiness(previewReadiness(preview, gaps));
    return response;
  }

  private PricePrepareReadinessResult previewReadiness(
      PricePrepareGenerateResult preview, PricePrepareGapPageResponse gaps) {
    if (preview != null
        && "SUCCESS".equalsIgnoreCase(preview.getStatus())
        && preview.getGapCount() == 0) {
      PricePrepareReadinessResult ready =
          PricePrepareReadinessResult.ready(null, preview.getPeriodMonth(), preview.getStatus());
      ready.setMessage("价格源检查已完成，未发现缺口");
      return ready;
    }
    String status =
        preview == null || !StringUtils.hasText(preview.getStatus())
            ? "FAILED"
            : preview.getStatus();
    int gapCount = preview == null ? 0 : preview.getGapCount();
    String message =
        preview == null || !StringUtils.hasText(preview.getMessage())
            ? "价格源检查失败"
            : preview.getMessage();
    return PricePrepareReadinessResult.notReady(
        status,
        true,
        false,
        message,
        null,
        preview == null ? null : preview.getPeriodMonth(),
        status,
        gapCount,
        previewGapSummaries(gaps));
  }

  private List<String> previewGapSummaries(PricePrepareGapPageResponse page) {
    List<PricePrepareGap> gaps =
        page == null || page.getRecords() == null ? List.of() : page.getRecords();
    return gaps.stream()
        .limit(5)
        .map(
            gap ->
                firstText(
                        firstText(gap.getGapMaterialCode(), gap.getMaterialCode()), "-")
                    + ": "
                    + firstText(gap.getMessage(), "未说明"))
        .toList();
  }

  private boolean hasCompletedScenarioPair(QuotePricePrepareWorkbenchResponse response) {
    return response != null
        && response.getOaScenario() != null
        && response.getFinanceScenario() != null
        && isSuccessfulBatch(response.getOaScenario().getBatch())
        && isSuccessfulBatch(response.getFinanceScenario().getBatch());
  }

  private boolean shouldGenerateFinanceComparison(
      QuotePricePrepareGenerateRequest request, PricePrepareGenerateResult result) {
    return result != null
        && "SUCCESS".equals(result.getStatus())
        && result.getGapCount() == 0
        && (request == null || !Boolean.FALSE.equals(request.getIncludeFinanceComparison()));
  }

  private void validateFinanceResult(
      Scope scope,
      QuotePriceTypeConfirmBatch confirm,
      PricePrepareGenerateResult result) {
    if (result == null
        || !scope.oaNo().equals(result.getOaNo())
        || !scope.oaFormItemId().equals(result.getOaFormItemId())
        || !scope.topProductCode().equals(result.getTopProductCode())
        || !scope.periodMonth().equals(result.getPeriodMonth())
        || !confirm.getConfirmNo().equals(result.getPriceTypeConfirmNo())) {
      throw new QuoteIngestException("OA来源批次与当前产品、月份或价格类型确认批次不一致");
    }
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
    PricePrepareBatchPageResponse batches = pricePrepareQueryService.pageBatches(batchQuery);
    response.setBatches(batches);
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
    populateScenarioComparison(response, scope, batches);
    return response;
  }

  private void populateScenarioComparison(
      QuotePricePrepareWorkbenchResponse response,
      Scope scope,
      PricePrepareBatchPageResponse batches) {
    List<PricePrepareBatch> records =
        batches == null || batches.getRecords() == null ? List.of() : batches.getRecords();
    ScenarioPair pair = selectScenarioPair(records);
    PricePrepareBatch oaBatch = pair == null ? null : pair.oaBatch();
    PricePrepareBatch financeBatch = pair == null ? null : pair.financeBatch();

    QuotePricePrepareScenarioResponse oaScenario = scenario(
        QuotePriceScenarioType.OA_LOCKED.name(), oaBatch);
    QuotePricePrepareScenarioResponse financeScenario = scenario(
        QuotePriceScenarioType.FINANCE_QUOTE_BASE.name(), financeBatch);
    response.setOaScenario(oaScenario);
    response.setFinanceScenario(financeScenario);
    populateFinanceBase(response, scope.periodMonth());
    populateDifferences(response, oaScenario.getItems(), financeScenario.getItems());
  }

  private QuotePricePrepareScenarioResponse scenario(
      String scenarioType, PricePrepareBatch batch) {
    QuotePricePrepareScenarioResponse response = new QuotePricePrepareScenarioResponse();
    response.setScenarioType(scenarioType);
    response.setBatch(batch);
    response.setItems(batch == null
        ? new PricePrepareItemPageResponse(0, List.of())
        : loadItems(batch.getPrepareNo()));
    return response;
  }

  private PricePrepareItemPageResponse loadItems(String prepareNo) {
    PricePrepareItemQueryRequest query = new PricePrepareItemQueryRequest();
    query.setPrepareNo(prepareNo);
    query.setPage(DEFAULT_PAGE);
    query.setPageSize(SCENARIO_PAGE_SIZE);
    return pricePrepareQueryService.pageItems(query);
  }

  private boolean isOaBatch(PricePrepareBatch batch) {
    return batch != null
        && (!StringUtils.hasText(batch.getScenarioType())
            || QuotePriceScenarioType.OA_LOCKED.name().equals(batch.getScenarioType()));
  }

  private boolean isFinanceBatchFor(PricePrepareBatch batch, String oaPrepareNo) {
    return batch != null
        && QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(batch.getScenarioType())
        && oaPrepareNo != null
        && oaPrepareNo.equals(batch.getSourcePrepareNo());
  }

  private ScenarioPair selectScenarioPair(List<PricePrepareBatch> records) {
    ScenarioPair latestOa = null;
    for (PricePrepareBatch batch : records == null ? List.<PricePrepareBatch>of() : records) {
      if (!isOaBatch(batch)) {
        continue;
      }
      PricePrepareBatch finance = findFinanceBatch(records, batch);
      ScenarioPair candidate = new ScenarioPair(batch, finance);
      if (latestOa == null) {
        latestOa = candidate;
      }
      if (isCompletedScenarioPair(candidate)) {
        return candidate;
      }
    }
    return latestOa;
  }

  private PricePrepareBatch findFinanceBatch(
      List<PricePrepareBatch> records, PricePrepareBatch oaBatch) {
    if (oaBatch == null) {
      return null;
    }
    return (records == null ? List.<PricePrepareBatch>of() : records).stream()
        .filter(batch -> isFinanceBatchFor(batch, oaBatch.getPrepareNo()))
        .filter(
            batch ->
                !StringUtils.hasText(oaBatch.getScenarioGroupNo())
                    || oaBatch.getScenarioGroupNo().equals(batch.getScenarioGroupNo()))
        .findFirst()
        .orElse(null);
  }

  private boolean isCompletedScenarioPair(ScenarioPair pair) {
    return pair != null
        && isSuccessfulBatch(pair.oaBatch())
        && isSuccessfulBatch(pair.financeBatch());
  }

  private boolean isSuccessfulBatch(PricePrepareBatch batch) {
    return batch != null
        && "SUCCESS".equalsIgnoreCase(batch.getStatus())
        && (batch.getGapCount() == null || batch.getGapCount() == 0);
  }

  private void populateFinanceBase(
      QuotePricePrepareWorkbenchResponse response, String periodMonth) {
    try {
      FinanceBasePrice base = financeQuoteBasePriceService.getRequired(periodMonth);
      if (base != null) {
        response.setFinanceBasePriceId(base.getId());
        response.setFinanceCuPricePerKg(base.getPrice());
        response.setFinanceCuPricePerTon(toPerTon(base.getPrice()));
      }
    } catch (IllegalArgumentException ignored) {
      // 查询页面允许展示“未配置”；真正生成时仍由财务场景服务严格阻断。
    }
  }

  private void populateDifferences(
      QuotePricePrepareWorkbenchResponse response,
      PricePrepareItemPageResponse oaPage,
      PricePrepareItemPageResponse financePage) {
    List<PricePrepareItem> oaItems = records(oaPage);
    List<PricePrepareItem> financeItems = records(financePage);
    if (oaItems.isEmpty() || financeItems.isEmpty()) {
      QuotePricePrepareDifferenceSummary emptySummary =
          new QuotePricePrepareDifferenceSummary();
      emptySummary.setTotalCount(0);
      emptySummary.setDifferentCount(0);
      response.setDifferences(List.of());
      response.setDifferenceSummary(emptySummary);
      return;
    }
    Map<String, ItemPair> pairs = new LinkedHashMap<>();
    for (PricePrepareItem item : oaItems) {
      pairs.computeIfAbsent(comparisonKey(item), ignored -> new ItemPair()).oa = item;
    }
    for (PricePrepareItem item : financeItems) {
      pairs.computeIfAbsent(comparisonKey(item), ignored -> new ItemPair()).finance = item;
    }

    List<QuotePricePrepareDifferenceResponse> differences = pairs.values().stream()
        .map(this::difference)
        .toList();
    QuotePricePrepareDifferenceSummary summary = new QuotePricePrepareDifferenceSummary();
    summary.setTotalCount(differences.size());
    summary.setDifferentCount((int) differences.stream().filter(
        QuotePricePrepareDifferenceResponse::isDifferent).count());
    summary.setFinanceTotalAmount(differences.stream()
        .map(QuotePricePrepareDifferenceResponse::getFinanceAmount)
        .map(this::zeroIfNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add));
    summary.setOaTotalAmount(differences.stream()
        .map(QuotePricePrepareDifferenceResponse::getOaAmount)
        .map(this::zeroIfNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add));
    summary.setAmountDifference(
        summary.getOaTotalAmount().subtract(summary.getFinanceTotalAmount()));
    response.setDifferences(differences);
    response.setDifferenceSummary(summary);
  }

  private QuotePricePrepareDifferenceResponse difference(ItemPair pair) {
    PricePrepareItem reference = pair.oa == null ? pair.finance : pair.oa;
    BigDecimal financeUnitPrice = value(pair.finance, PricePrepareItem::getUnitPrice);
    BigDecimal oaUnitPrice = value(pair.oa, PricePrepareItem::getUnitPrice);
    BigDecimal financeAmount = value(pair.finance, PricePrepareItem::getAmount);
    BigDecimal oaAmount = value(pair.oa, PricePrepareItem::getAmount);
    BigDecimal unitDifference = zeroIfNull(oaUnitPrice).subtract(zeroIfNull(financeUnitPrice));
    BigDecimal amountDifference = zeroIfNull(oaAmount).subtract(zeroIfNull(financeAmount));

    QuotePricePrepareDifferenceResponse response = new QuotePricePrepareDifferenceResponse();
    response.setSettlementKey(reference == null ? null : reference.getSettlementKey());
    response.setMaterialCode(reference == null ? null : reference.getMaterialCode());
    response.setMaterialName(reference == null ? null : reference.getMaterialName());
    response.setItemType(reference == null ? null : reference.getItemType());
    response.setQuantity(reference == null ? null : reference.getQuantity());
    response.setFinanceUnitPrice(financeUnitPrice);
    response.setOaUnitPrice(oaUnitPrice);
    response.setUnitPriceDifference(unitDifference);
    response.setFinanceAmount(financeAmount);
    response.setOaAmount(oaAmount);
    response.setAmountDifference(amountDifference);
    response.setDifferenceRate(percent(amountDifference, financeAmount));
    response.setDifferent(
        pair.oa == null
            || pair.finance == null
            || unitDifference.compareTo(BigDecimal.ZERO) != 0
            || amountDifference.compareTo(BigDecimal.ZERO) != 0);
    return response;
  }

  private BigDecimal percent(BigDecimal difference, BigDecimal base) {
    if (base == null || base.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    return difference
        .divide(base.abs(), 8, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(4, RoundingMode.HALF_UP);
  }

  private List<PricePrepareItem> records(PricePrepareItemPageResponse page) {
    return page == null || page.getRecords() == null ? List.of() : page.getRecords();
  }

  private String comparisonKey(PricePrepareItem item) {
    if (item != null && StringUtils.hasText(item.getSettlementKey())) {
      return item.getSettlementKey().trim();
    }
    return String.valueOf(item == null ? null : item.getMaterialCode())
        + "|"
        + String.valueOf(item == null ? null : item.getItemType())
        + "|"
        + String.valueOf(item == null ? null : item.getBomRowId());
  }

  private BigDecimal value(
      PricePrepareItem item,
      java.util.function.Function<PricePrepareItem, BigDecimal> getter) {
    return item == null ? null : getter.apply(item);
  }

  private BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal toPerTon(BigDecimal pricePerKg) {
    return pricePerKg == null ? null : pricePerKg.multiply(new BigDecimal("1000"));
  }

  private static final class ItemPair {
    private PricePrepareItem oa;
    private PricePrepareItem finance;
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

  private record ScenarioPair(
      PricePrepareBatch oaBatch, PricePrepareBatch financeBatch) {}
}
