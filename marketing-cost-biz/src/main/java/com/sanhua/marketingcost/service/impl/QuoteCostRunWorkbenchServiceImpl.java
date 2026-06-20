package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmationSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse.CostRunVersionItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunWorkbenchServiceImpl implements QuoteCostRunWorkbenchService {

  private static final String STATUS_TRIAL = "TRIAL";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_VOIDED = "VOIDED";
  private static final String COST_CODE_TOTAL = "TOTAL";

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final CostRunResultMapper resultMapper;
  private final CostRunPartItemMapper partItemMapper;
  private final CostRunCostItemMapper costItemMapper;
  private final CostRunTraceSnapshotMapper traceSnapshotMapper;
  private final CostRunTaskMapper taskMapper;
  private final QuoteCostingWorkbenchSummaryMapper summaryMapper;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final PricePrepareService pricePrepareService;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final QuoteCostRunVersionService versionService;
  private final QuoteCostRunVersionNoGenerator versionNoGenerator;
  private final CostRunEngine costRunEngine;
  private final CostRunResultWriter resultWriter;

  public QuoteCostRunWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteCostRunVersionMapper versionMapper,
      CostRunResultMapper resultMapper,
      CostRunPartItemMapper partItemMapper,
      CostRunCostItemMapper costItemMapper,
      CostRunTraceSnapshotMapper traceSnapshotMapper,
      CostRunTaskMapper taskMapper,
      QuoteCostingWorkbenchSummaryMapper summaryMapper,
      QuoteProductBomCostingBuildService costingBuildService,
      PricePrepareService pricePrepareService,
      PricePrepareReadinessService pricePrepareReadinessService,
      QuoteCostRunVersionService versionService,
      QuoteCostRunVersionNoGenerator versionNoGenerator,
      CostRunEngine costRunEngine,
      CostRunResultWriter resultWriter) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.versionMapper = versionMapper;
    this.resultMapper = resultMapper;
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
    this.traceSnapshotMapper = traceSnapshotMapper;
    this.taskMapper = taskMapper;
    this.summaryMapper = summaryMapper;
    this.costingBuildService = costingBuildService;
    this.pricePrepareService = pricePrepareService;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
    this.versionService = versionService;
    this.versionNoGenerator = versionNoGenerator;
    this.costRunEngine = costRunEngine;
    this.resultWriter = resultWriter;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteCostRunWorkbenchResponse getCostRun(String oaNo, Long oaFormItemId, String periodMonth) {
    Scope scope = requireScope(oaNo, oaFormItemId, periodMonth);
    cleanupTrialVersions(scope, null);
    QuoteCostRunSummaryResponse latestTrial = null;
    QuoteCostRunSummaryResponse latestConfirmed = latestVersion(scope, STATUS_CONFIRMED);
    QuoteCostRunWorkbenchResponse response = baseResponse(scope, latestTrial, latestConfirmed);
    QuoteCostRunSummaryResponse displayVersion = latestConfirmed;
    response.setCurrentDisplayVersion(displayVersion);
    if (displayVersion != null) {
      fillResultRows(response, displayVersion.getId());
    }
    return response;
  }

  @Override
  public QuoteCostRunWorkbenchResponse trial(
      String oaNo, Long oaFormItemId, QuoteCostRunTrialRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    QuotePriceTypeConfirmationSummaryResponse priceTypeConfirmation =
        summaryMapper.selectLatestPriceTypeConfirmation(
            scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    if (priceTypeConfirmation == null
        || !"CONFIRMED".equalsIgnoreCase(trimToNull(priceTypeConfirmation.getStatus()))) {
      throw new QuoteIngestException("请先确认当前价格类型后再发起成本核算");
    }
    PricePrepareGenerateResult generatedPrepare = refreshCostingInputs(scope);
    QuoteBomConfirmationSummaryResponse bomConfirmation =
        summaryMapper.selectLatestBomConfirmation(
            scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    PricePrepareReadinessResult readiness =
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth(),
            priceTypeConfirmation == null ? null : priceTypeConfirmation.getConfirmNo());
    requireReady(readiness);
    QuotePricePrepareSummaryResponse pricePrepare =
        summaryMapper.selectLatestPricePrepare(
            scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    String pricePrepareNo =
        firstText(
            request == null ? null : request.getPricePrepareNo(),
            generatedPrepare == null ? null : generatedPrepare.getPrepareNo(),
            readiness == null ? null : readiness.getPrepareNo(),
            pricePrepare == null ? null : pricePrepare.getPrepareNo());

    QuoteCostRunVersion version =
        versionService.createTrial(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth(),
            scope.periodMonth(),
            pricePrepareNo,
            priceTypeConfirmation == null ? null : priceTypeConfirmation.getConfirmNo(),
            bomConfirmation == null ? null : bomConfirmation.getConfirmNo(),
            firstText(scope.item().getBusinessUnitType(), scope.form().getBusinessUnitType()));
    LocalDateTime costRunStartedAt =
        version.getTrialStartedAt() == null ? LocalDateTime.now() : version.getTrialStartedAt();
    CostRunContext context =
        CostRunContext.quote(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.item().getPackageMethod(),
            scope.form().getCustomer(),
            firstText(scope.item().getBusinessUnitType(), scope.form().getBusinessUnitType()),
            scope.periodMonth(),
            costRunStartedAt,
            "QUOTE:" + scope.oaFormItemId());
    context.setCostRunVersionId(version.getId());
    context.setCostRunNo(version.getCostRunNo());
    context.setPricePrepareNo(version.getPricePrepareNo());
    context.setPriceTypeConfirmNo(version.getPriceTypeConfirmNo());
    context.setBomConfirmNo(version.getBomConfirmNo());
    CostRunObjectResult result = costRunEngine.run(context);
    resultWriter.writeQuoteResult(result, scope.form(), scope.item());
    versionService.finishTrial(
        version.getId(),
        totalCost(result),
        result.getPartItems().size(),
        result.getCostItems().size());
    cleanupTrialVersions(scope, version.getId());

    QuoteCostRunSummaryResponse trialSummary = summary(version, totalCost(result));
    trialSummary.setPartItemCount(result.getPartItems().size());
    trialSummary.setCostItemCount(result.getCostItems().size());
    QuoteCostRunWorkbenchResponse response =
        baseResponse(scope, trialSummary, latestVersion(scope, STATUS_CONFIRMED));
    response.setCurrentDisplayVersion(trialSummary);
    response.setResultHeader(result.getResult());
    response.setPartItems(new ArrayList<>(result.getPartItems()));
    response.setCostItems(new ArrayList<>(result.getCostItems()));
    response.setCanConfirm(true);
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteCostRunSummaryResponse confirm(
      String oaNo,
      Long oaFormItemId,
      String costRunNo,
      QuoteCostRunConfirmRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId, null);
    QuoteCostRunVersion version =
        versionMapper.selectOne(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getCostRunNo, required("costRunNo", costRunNo))
                .last("LIMIT 1"));
    if (version == null
        || !scope.oaNo().equals(version.getOaNo())
        || !scope.oaFormItemId().equals(version.getOaFormItemId())
        || !scope.productCode().equals(version.getProductCode())) {
      throw new QuoteIngestException("成本试算批次不属于当前产品行");
    }
    if (!STATUS_TRIAL.equals(version.getStatus())) {
      throw new QuoteIngestException("当前成本试算版本不是 TRIAL，不能重复确认");
    }
    LocalDateTime now = LocalDateTime.now();
    String versionNo = versionNoGenerator.nextVersionNo(scope.oaFormItemId(), scope.productCode());
    String confirmedBy = firstText(request == null ? null : request.getConfirmedBy(), "system");

    QuoteCostRunVersion voidPatch = new QuoteCostRunVersion();
    voidPatch.setStatus(STATUS_VOIDED);
    versionMapper.update(
        voidPatch,
        Wrappers.lambdaUpdate(QuoteCostRunVersion.class)
            .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
            .eq(QuoteCostRunVersion::getOaFormItemId, scope.oaFormItemId())
            .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
            .eq(QuoteCostRunVersion::getStatus, STATUS_CONFIRMED));
    CostRunResult voidResult = new CostRunResult();
    voidResult.setResultStatus(STATUS_VOIDED);
    resultMapper.update(
        voidResult,
        Wrappers.lambdaUpdate(CostRunResult.class)
            .eq(CostRunResult::getOaNo, scope.oaNo())
            .eq(CostRunResult::getOaFormItemId, scope.oaFormItemId())
            .eq(CostRunResult::getProductCode, scope.productCode())
            .eq(CostRunResult::getResultStatus, STATUS_CONFIRMED));

    QuoteCostRunVersion patch = new QuoteCostRunVersion();
    patch.setId(version.getId());
    patch.setVersionNo(versionNo);
    patch.setStatus(STATUS_CONFIRMED);
    patch.setConfirmedBy(confirmedBy);
    patch.setConfirmedAt(now);
    patch.setConfirmMessage(trimToNull(request == null ? null : request.getConfirmMessage()));
    versionMapper.updateById(patch);

    CostRunResult resultPatch = new CostRunResult();
    resultPatch.setResultStatus(STATUS_CONFIRMED);
    resultMapper.update(
        resultPatch,
        Wrappers.lambdaUpdate(CostRunResult.class)
            .eq(CostRunResult::getCostRunVersionId, version.getId()));

    markConfirmedCostRun(scope, version.getId(), now);

    version.setVersionNo(versionNo);
    version.setStatus(STATUS_CONFIRMED);
    version.setConfirmedBy(confirmedBy);
    version.setConfirmedAt(now);
    version.setConfirmMessage(patch.getConfirmMessage());
    return summary(version, version.getTotalCost());
  }

  private void markConfirmedCostRun(Scope scope, Long versionId, LocalDateTime confirmedAt) {
    oaFormItemMapper.update(
        null,
        Wrappers.lambdaUpdate(OaFormItem.class)
            .set(OaFormItem::getCalcStatus, "已核算")
            .set(OaFormItem::getCalcAt, confirmedAt)
            .set(OaFormItem::getConfirmedCostVersionId, versionId)
            .set(OaFormItem::getUpdatedAt, confirmedAt)
            .eq(OaFormItem::getId, scope.oaFormItemId())
            .eq(OaFormItem::getOaFormId, scope.form().getId())
            .eq(OaFormItem::getDeleted, 0));

    long runnableCount = oaFormItemMapper.countRunnableItems(scope.form().getId());
    long calculatedCount = oaFormItemMapper.countCalculatedRunnableItems(scope.form().getId());
    if (runnableCount > 0 && calculatedCount >= runnableCount) {
      oaFormMapper.update(
          null,
          Wrappers.lambdaUpdate(OaForm.class)
              .set(OaForm::getCalcStatus, "已核算")
              .set(OaForm::getCalcAt, confirmedAt)
              .set(OaForm::getUpdatedAt, confirmedAt)
              .eq(OaForm::getId, scope.form().getId())
              .eq(OaForm::getDeleted, 0));
    }
  }

  @Override
  public int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException {
    Scope scope = requireScope(oaNo, oaFormItemId, null);
    QuoteCostRunVersion version = versionMapper.selectById(versionId);
    if (version == null
        || !scope.oaNo().equals(version.getOaNo())
        || !scope.oaFormItemId().equals(version.getOaFormItemId())
        || !scope.productCode().equals(version.getProductCode())) {
      throw new QuoteIngestException("成本版本不属于当前产品行");
    }
    List<CostRunPartItem> parts = partRows(versionId);
    List<CostRunCostItem> costs = costRows(versionId);
    StringBuilder csv = new StringBuilder();
    csv.append('\ufeff');
    csv.append("section,code,name,quantity,unitPrice,amount,remark\n");
    csv.append("version,")
        .append(csv(version.getCostRunNo()))
        .append(',')
        .append(csv(version.getVersionNo()))
        .append(",,,")
        .append(csv(decimal(version.getTotalCost())))
        .append(',')
        .append(csv(version.getStatus()))
        .append('\n');
    for (CostRunPartItem part : parts) {
      csv.append("part,")
          .append(csv(part.getPartCode()))
          .append(',')
          .append(csv(part.getPartName()))
          .append(',')
          .append(csv(decimal(part.getQty())))
          .append(',')
          .append(csv(decimal(part.getUnitPrice())))
          .append(',')
          .append(csv(decimal(part.getAmount())))
          .append(',')
          .append(csv(part.getRemark()))
          .append('\n');
    }
    for (CostRunCostItem cost : costs) {
      csv.append("cost,")
          .append(csv(cost.getCostCode()))
          .append(',')
          .append(csv(cost.getCostName()))
          .append(",,,")
          .append(csv(decimal(cost.getAmount())))
          .append(',')
          .append(csv(cost.getRemark()))
          .append('\n');
    }
    output.write(csv.toString().getBytes(StandardCharsets.UTF_8));
    return 1 + parts.size() + costs.size();
  }

  private QuoteCostRunWorkbenchResponse baseResponse(
      Scope scope,
      QuoteCostRunSummaryResponse latestTrial,
      QuoteCostRunSummaryResponse latestConfirmed) {
    QuoteCostRunWorkbenchResponse response = new QuoteCostRunWorkbenchResponse();
    response.setOaNo(scope.oaNo());
    response.setOaFormItemId(scope.oaFormItemId());
    response.setProductCode(scope.productCode());
    response.setPeriodMonth(scope.periodMonth());
    response.setLatestTrial(latestTrial);
    response.setLatestConfirmed(latestConfirmed);
    response.setVersions(versionItems(scope, latestTrial, latestConfirmed));
    List<String> blockingReasons = readinessBlockingReasons(scope);
    response.setBlockingReasons(blockingReasons);
    response.setCanStartTrial(blockingReasons.isEmpty());
    response.setCanConfirm(latestTrial != null && STATUS_TRIAL.equals(latestTrial.getStatus()));
    return response;
  }

  private void fillResultRows(QuoteCostRunWorkbenchResponse response, Long versionId) {
    CostRunResult result =
        resultMapper.selectOne(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getCostRunVersionId, versionId)
                .last("LIMIT 1"));
    if (result != null) {
      response.setResultHeader(toResultDto(result));
    }
    response.setPartItems(partRows(versionId).stream().map(this::toPartDto).toList());
    response.setCostItems(costRows(versionId).stream().map(this::toCostDto).toList());
  }

  private List<String> readinessBlockingReasons(Scope scope) {
    QuotePriceTypeConfirmationSummaryResponse priceTypeConfirmation =
        summaryMapper.selectLatestPriceTypeConfirmation(
            scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
    if (priceTypeConfirmation == null
        || !"CONFIRMED".equalsIgnoreCase(trimToNull(priceTypeConfirmation.getStatus()))) {
      return List.of("请先确认当前价格类型");
    }
    PricePrepareReadinessResult readiness =
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth(),
            priceTypeConfirmation == null ? null : priceTypeConfirmation.getConfirmNo());
    if (isReady(readiness) || (readiness != null && readiness.isAllowContinue() && !readiness.isBlocking())) {
      return List.of();
    }
    List<String> reasons = new ArrayList<>();
    if (readiness == null || !StringUtils.hasText(readiness.getMessage())) {
      reasons.add("价格准备未完成");
    } else {
      reasons.add(readiness.getMessage());
    }
    return reasons;
  }

  private PricePrepareGenerateResult refreshCostingInputs(Scope scope) {
    QuoteBomCostingBuildResponse build =
        costingBuildService.buildByOaFormItem(scope.oaFormItemId(), scope.periodMonth());
    String periodMonth = firstText(build == null ? null : build.periodMonth(), scope.periodMonth());

    QuotePriceTypeConfirmationSummaryResponse priceTypeConfirmation =
        summaryMapper.selectLatestPriceTypeConfirmation(
            scope.oaNo(), scope.oaFormItemId(), scope.productCode(), periodMonth);

    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo(scope.oaNo());
    request.setOaFormItemId(scope.oaFormItemId());
    request.setTopProductCode(scope.productCode());
    request.setPeriodMonth(periodMonth);
    request.setBusinessUnitType(firstText(scope.item().getBusinessUnitType(), scope.form().getBusinessUnitType()));
    request.setPriceTypeConfirmNo(
        priceTypeConfirmation == null ? null : priceTypeConfirmation.getConfirmNo());
    request.setSourceType("QUOTE");
    return pricePrepareService.generate(request);
  }

  private void requireReady(PricePrepareReadinessResult readiness) {
    if (isReady(readiness) || (readiness != null && readiness.isAllowContinue() && !readiness.isBlocking())) {
      return;
    }
    String message =
        readiness == null || !StringUtils.hasText(readiness.getMessage())
            ? "价格准备未完成，不能发起成本试算"
            : readiness.getMessage();
    throw new QuoteIngestException(message);
  }

  private boolean isReady(PricePrepareReadinessResult readiness) {
    return readiness != null
        && "READY".equals(readiness.getStatus())
        && "SUCCESS".equals(readiness.getBatchStatus())
        && readiness.getGapCount() == 0;
  }

  private Scope requireScope(String oaNo, Long oaFormItemId, String periodMonth) {
    String oaNoValue = required("oaNo", oaNo);
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNoValue).last("LIMIT 1"));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + oaNoValue);
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    String productCode = required("productCode", item.getMaterialNo());
    String period =
        StringUtils.hasText(periodMonth)
            ? CostPricingPeriodUtils.requireCurrentPricingMonth(periodMonth)
            : CostPricingPeriodUtils.currentPricingMonth();
    return new Scope(form, item, oaNoValue, oaFormItemId, productCode, period);
  }

  private QuoteCostRunSummaryResponse latestVersion(Scope scope, String status) {
    QuoteCostRunVersion version =
        versionMapper.selectOne(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
                .eq(QuoteCostRunVersion::getOaFormItemId, scope.oaFormItemId())
                .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
                .eq(QuoteCostRunVersion::getPricingMonth, scope.periodMonth())
                .eq(QuoteCostRunVersion::getStatus, status)
                .orderByDesc(QuoteCostRunVersion::getConfirmedAt)
                .orderByDesc(QuoteCostRunVersion::getTrialFinishedAt)
                .orderByDesc(QuoteCostRunVersion::getId)
                .last("LIMIT 1"));
    return version == null ? null : summary(version, version.getTotalCost());
  }

  private List<CostRunVersionItemResponse> versionItems(
      Scope scope,
      QuoteCostRunSummaryResponse latestTrial,
      QuoteCostRunSummaryResponse latestConfirmed) {
    List<QuoteCostRunVersion> versions =
        versionMapper.selectList(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
                .eq(QuoteCostRunVersion::getOaFormItemId, scope.oaFormItemId())
                .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
                .eq(QuoteCostRunVersion::getPricingMonth, scope.periodMonth()));
    if (versions == null || versions.isEmpty()) {
      return List.of();
    }
    Long confirmedVersionId =
        scope.item().getConfirmedCostVersionId() != null
            ? scope.item().getConfirmedCostVersionId()
            : latestConfirmed == null ? null : latestConfirmed.getId();
    Long latestTrialId = latestTrial == null ? null : latestTrial.getId();
    return versions.stream()
        .filter(version -> shouldDisplayVersion(version, latestTrialId))
        .map(version -> toVersionItem(version, confirmedVersionId))
        .sorted(this::compareVersionItem)
        .toList();
  }

  private boolean shouldDisplayVersion(QuoteCostRunVersion version, Long latestTrialId) {
    if (version == null) {
      return false;
    }
    if (!STATUS_TRIAL.equals(version.getStatus())) {
      return true;
    }
    return latestTrialId != null && latestTrialId.equals(version.getId());
  }

  private void cleanupTrialVersions(Scope scope, Long keepVersionId) {
    var query =
        Wrappers.lambdaQuery(QuoteCostRunVersion.class)
            .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
            .eq(QuoteCostRunVersion::getOaFormItemId, scope.oaFormItemId())
            .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
            .eq(QuoteCostRunVersion::getPricingMonth, scope.periodMonth())
            .eq(QuoteCostRunVersion::getStatus, STATUS_TRIAL);
    if (keepVersionId != null) {
      query.ne(QuoteCostRunVersion::getId, keepVersionId);
    }
    List<QuoteCostRunVersion> staleTrials = versionMapper.selectList(query);
    if (staleTrials == null || staleTrials.isEmpty()) {
      return;
    }
    List<Long> staleVersionIds =
        staleTrials.stream().map(QuoteCostRunVersion::getId).filter(id -> id != null).toList();
    List<String> staleCostRunNos =
        staleTrials.stream()
            .map(QuoteCostRunVersion::getCostRunNo)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    if (!staleVersionIds.isEmpty()) {
      resultMapper.delete(
          Wrappers.lambdaQuery(CostRunResult.class)
              .in(CostRunResult::getCostRunVersionId, staleVersionIds));
      partItemMapper.delete(
          Wrappers.lambdaQuery(CostRunPartItem.class)
              .in(CostRunPartItem::getCostRunVersionId, staleVersionIds));
      costItemMapper.delete(
          Wrappers.lambdaQuery(CostRunCostItem.class)
              .in(CostRunCostItem::getCostRunVersionId, staleVersionIds));
      taskMapper.delete(
          Wrappers.lambdaQuery(CostRunTask.class)
              .in(CostRunTask::getCostRunVersionId, staleVersionIds));
    }
    if (!staleCostRunNos.isEmpty()) {
      traceSnapshotMapper.delete(
          Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
              .in(CostRunTraceSnapshot::getCostRunNo, staleCostRunNos));
      taskMapper.delete(
          Wrappers.lambdaQuery(CostRunTask.class).in(CostRunTask::getCostRunNo, staleCostRunNos));
    }
    if (!staleVersionIds.isEmpty()) {
      versionMapper.delete(
          Wrappers.lambdaQuery(QuoteCostRunVersion.class)
              .in(QuoteCostRunVersion::getId, staleVersionIds)
              .eq(QuoteCostRunVersion::getStatus, STATUS_TRIAL));
    }
  }

  private CostRunVersionItemResponse toVersionItem(
      QuoteCostRunVersion version, Long confirmedVersionId) {
    CostRunVersionItemResponse item = new CostRunVersionItemResponse();
    item.setId(version.getId());
    item.setCostRunNo(version.getCostRunNo());
    item.setVersionNo(version.getVersionNo());
    item.setDisplayVersionNo(firstText(version.getVersionNo(), version.getCostRunNo()));
    item.setStatus(version.getStatus());
    item.setDisplayStatus(displayVersionStatus(version.getStatus(), version.getId(), confirmedVersionId));
    item.setTotalCost(version.getTotalCost());
    item.setPartItemCount(version.getPartItemCount());
    item.setCostItemCount(version.getCostItemCount());
    item.setTrialFinishedAt(version.getTrialFinishedAt());
    item.setConfirmedAt(version.getConfirmedAt());
    item.setConfirmedBy(version.getConfirmedBy());
    item.setCanConfirm(STATUS_TRIAL.equals(version.getStatus()));
    item.setCanViewSheet(version.getId() != null && StringUtils.hasText(version.getCostRunNo()));
    item.setCanViewTrace(!STATUS_TRIAL.equals(version.getStatus()) && StringUtils.hasText(version.getCostRunNo()));
    item.setCurrentConfirmed(version.getId() != null && version.getId().equals(confirmedVersionId));
    item.setStale(!STATUS_TRIAL.equals(version.getStatus()) && !item.isCurrentConfirmed());
    return item;
  }

  private String displayVersionStatus(String status, Long id, Long confirmedVersionId) {
    if (STATUS_TRIAL.equals(status)) {
      return "待确认";
    }
    if (id != null && id.equals(confirmedVersionId)) {
      return "当前已确认";
    }
    if (STATUS_CONFIRMED.equals(status) || STATUS_VOIDED.equals(status)) {
      return "历史版本";
    }
    return firstText(status, "-");
  }

  private int compareVersionItem(CostRunVersionItemResponse left, CostRunVersionItemResponse right) {
    int rank = Integer.compare(versionSortRank(left), versionSortRank(right));
    if (rank != 0) {
      return rank;
    }
    int time = compareDesc(versionSortTime(left), versionSortTime(right));
    if (time != 0) {
      return time;
    }
    return compareDesc(left.getId(), right.getId());
  }

  private int versionSortRank(CostRunVersionItemResponse item) {
    if (STATUS_TRIAL.equals(item.getStatus())) {
      return 0;
    }
    if (item.isCurrentConfirmed()) {
      return 1;
    }
    return 2;
  }

  private LocalDateTime versionSortTime(CostRunVersionItemResponse item) {
    return item.getConfirmedAt() != null ? item.getConfirmedAt() : item.getTrialFinishedAt();
  }

  private <T extends Comparable<T>> int compareDesc(T left, T right) {
    if (left == null && right == null) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    return right.compareTo(left);
  }

  private List<CostRunPartItem> partRows(Long versionId) {
    return partItemMapper.selectList(
        Wrappers.lambdaQuery(CostRunPartItem.class)
            .eq(CostRunPartItem::getCostRunVersionId, versionId)
            .orderByAsc(CostRunPartItem::getId));
  }

  private List<CostRunCostItem> costRows(Long versionId) {
    return costItemMapper.selectList(
        Wrappers.lambdaQuery(CostRunCostItem.class)
            .eq(CostRunCostItem::getCostRunVersionId, versionId)
            .orderByAsc(CostRunCostItem::getLineNo)
            .orderByAsc(CostRunCostItem::getId));
  }

  private QuoteCostRunSummaryResponse summary(QuoteCostRunVersion version, BigDecimal totalCost) {
    QuoteCostRunSummaryResponse response = new QuoteCostRunSummaryResponse();
    response.setId(version.getId());
    response.setCostRunNo(version.getCostRunNo());
    response.setVersionNo(version.getVersionNo());
    response.setOaNo(version.getOaNo());
    response.setOaFormItemId(version.getOaFormItemId());
    response.setProductCode(version.getProductCode());
    response.setPricingMonth(version.getPricingMonth());
    response.setResultPeriod(version.getResultPeriod());
    response.setBomConfirmNo(version.getBomConfirmNo());
    response.setPriceTypeConfirmNo(version.getPriceTypeConfirmNo());
    response.setPricePrepareNo(version.getPricePrepareNo());
    response.setStatus(version.getStatus());
    response.setTotalCost(totalCost);
    response.setPartItemCount(version.getPartItemCount());
    response.setCostItemCount(version.getCostItemCount());
    response.setTrialStartedAt(version.getTrialStartedAt());
    response.setTrialFinishedAt(version.getTrialFinishedAt());
    response.setConfirmedBy(version.getConfirmedBy());
    response.setConfirmedAt(version.getConfirmedAt());
    response.setConfirmMessage(version.getConfirmMessage());
    return response;
  }

  private CostRunResultDto toResultDto(CostRunResult result) {
    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo(result.getOaNo());
    dto.setProductCode(result.getProductCode());
    dto.setProductName(result.getProductName());
    dto.setProductModel(result.getProductModel());
    dto.setCustomerName(result.getCustomerName());
    dto.setBusinessUnit(result.getBusinessUnit());
    dto.setDepartment(result.getDepartment());
    dto.setPeriod(result.getPeriod());
    dto.setCurrency(result.getCurrency());
    dto.setUnit(result.getUnit());
    dto.setTotalCost(result.getTotalCost());
    dto.setCalcStatus(result.getCalcStatus());
    dto.setProductAttr(result.getProductAttr());
    return dto;
  }

  private CostRunPartItemDto toPartDto(CostRunPartItem item) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setId(item.getId());
    dto.setBomRowId(item.getBomRowId());
    dto.setPricePrepareItemId(item.getPricePrepareItemId());
    dto.setOaNo(item.getOaNo());
    dto.setProductCode(item.getProductCode());
    dto.setPartCode(item.getPartCode());
    dto.setPartName(item.getPartName());
    dto.setPartDrawingNo(item.getPartDrawingNo());
    dto.setPartQty(item.getQty());
    dto.setMaterial(item.getMaterial());
    dto.setShapeAttr(item.getShapeAttr());
    dto.setPriceSource(item.getPriceSource());
    dto.setUnitPrice(item.getUnitPrice());
    dto.setAmount(item.getAmount());
    dto.setRemark(item.getRemark());
    return dto;
  }

  private CostRunCostItemDto toCostDto(CostRunCostItem item) {
    CostRunCostItemDto dto = new CostRunCostItemDto();
    dto.setId(item.getId());
    dto.setCostCode(item.getCostCode());
    dto.setCostName(item.getCostName());
    dto.setBaseAmount(item.getBaseAmount());
    dto.setRate(item.getRate());
    dto.setAmount(item.getAmount());
    dto.setRemark(item.getRemark());
    dto.setCategory(item.getCategory());
    return dto;
  }

  private BigDecimal totalCost(CostRunObjectResult result) {
    if (result.getResult() != null && result.getResult().getTotalCost() != null) {
      return result.getResult().getTotalCost();
    }
    for (CostRunCostItemDto item : result.getCostItems()) {
      if (item != null && COST_CODE_TOTAL.equals(trim(item.getCostCode()))) {
        return item.getAmount();
      }
    }
    return null;
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new QuoteIngestException(field + " 不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String decimal(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  private String csv(String value) {
    String text = value == null ? "" : value;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth) {}
}
