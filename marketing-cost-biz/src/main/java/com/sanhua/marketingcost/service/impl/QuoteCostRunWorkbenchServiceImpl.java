package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse.CostRunVersionItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.collaboration.CollaborationCostingGate;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunWorkbenchServiceImpl implements QuoteCostRunWorkbenchService {

  private static final String STATUS_TRIAL = "TRIAL";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_HISTORY = "HISTORY";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final BigDecimal KG_PER_TON = new BigDecimal("1000");
  private static final Pattern NEGATED_HISTORICAL_PRICE_PATTERN =
      Pattern.compile("沿用历史(?:月份|因素)?价\\s*[=:：]\\s*否");

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final CostRunResultService costRunResultService;
  private final CostRunPartItemMapper partItemMapper;
  private final CostRunCostItemMapper costItemMapper;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final QuoteCostRunVersionNoGenerator versionNoGenerator;
  private final QuoteCuAdjustmentCalcService cuAdjustmentCalcService;
  private final CollaborationCostingGate collaborationCostingGate;
  private final QuoteCostingWorkspaceService workspaceService;
  private final CostInputRevisionService inputRevisionService;

  @Autowired
  public QuoteCostRunWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteCostRunVersionMapper versionMapper,
      CostRunResultService costRunResultService,
      CostRunPartItemMapper partItemMapper,
      CostRunCostItemMapper costItemMapper,
      PricePrepareReadinessService pricePrepareReadinessService,
      QuoteCostRunVersionNoGenerator versionNoGenerator,
      QuoteCuAdjustmentCalcService cuAdjustmentCalcService,
      CollaborationCostingGate collaborationCostingGate,
      QuoteCostingWorkspaceService workspaceService,
      CostInputRevisionService inputRevisionService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.versionMapper = versionMapper;
    this.costRunResultService = costRunResultService;
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
    this.versionNoGenerator = versionNoGenerator;
    this.cuAdjustmentCalcService = cuAdjustmentCalcService;
    this.collaborationCostingGate = collaborationCostingGate;
    this.workspaceService = workspaceService;
    this.inputRevisionService = inputRevisionService;
  }

  QuoteCostRunWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteCostRunVersionMapper versionMapper,
      CostRunResultService costRunResultService,
      CostRunPartItemMapper partItemMapper,
      CostRunCostItemMapper costItemMapper,
      PricePrepareReadinessService pricePrepareReadinessService,
      QuoteCostRunVersionNoGenerator versionNoGenerator,
      QuoteCuAdjustmentCalcService cuAdjustmentCalcService,
      CollaborationCostingGate collaborationCostingGate,
      QuoteCostingWorkspaceService workspaceService) {
    this(
        oaFormMapper,
        oaFormItemMapper,
        versionMapper,
        costRunResultService,
        partItemMapper,
        costItemMapper,
        pricePrepareReadinessService,
        versionNoGenerator,
        cuAdjustmentCalcService,
        collaborationCostingGate,
        workspaceService,
        null);
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCostRunWorkbenchResponse getCostRun(String oaNo, Long oaFormItemId, String periodMonth) {
    return getCostRun(oaNo, oaFormItemId, periodMonth, null);
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCostRunWorkbenchResponse getCostRun(
      String oaNo, Long oaFormItemId, String periodMonth, Long versionId) {
    if (versionId != null) {
      return getHistoricalCostRun(oaNo, oaFormItemId, versionId);
    }
    Scope scope = requireScope(oaNo, oaFormItemId, periodMonth);
    QuoteCostRunSummaryResponse latestTrial = latestInProgressVersion(scope);
    QuoteCostRunSummaryResponse latestConfirmed = latestSuccessfulVersion(scope);
    QuoteCostRunWorkbenchResponse response = baseResponse(scope, latestTrial, latestConfirmed);
    QuoteCostRunSummaryResponse displayVersion = latestTrial != null ? latestTrial : latestConfirmed;
    response.setCurrentDisplayVersion(displayVersion);
    if (displayVersion != null) {
      fillResultRows(response, displayVersion.getId());
    }
    return response;
  }

  private QuoteCostRunWorkbenchResponse getHistoricalCostRun(
      String oaNo, Long oaFormItemId, Long versionId) {
    Scope accessScope = requireScope(oaNo, oaFormItemId, null);
    QuoteCostRunVersion selected = requireOwnedVersion(accessScope, versionId, null);
    String pricingMonth =
        CostPricingPeriodUtils.normalizePricingMonth(selected.getPricingMonth());
    Scope scope =
        new Scope(
            accessScope.form(),
            accessScope.item(),
            accessScope.oaNo(),
            accessScope.oaFormItemId(),
            accessScope.productCode(),
            pricingMonth,
            accessScope.businessUnitType());
    QuoteCostRunSummaryResponse latestTrial = latestInProgressVersion(scope);
    QuoteCostRunSummaryResponse latestConfirmed = latestSuccessfulVersion(scope);
    QuoteCostRunWorkbenchResponse response = baseResponse(scope, latestTrial, latestConfirmed);
    response.setCurrentDisplayVersion(summary(selected, selected.getTotalCost()));
    fillResultRows(response, selected.getId());
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteCostRunWorkbenchResponse runToSuccess(
      String oaNo,
      Long oaFormItemId,
      QuoteCostRunTrialRequest request,
      String completedBy) {
    Scope scope = requireScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    QuoteCuAdjustmentCalcResult calculation = calculate(scope, request, true);
    QuoteCostRunVersion version = calculation.version();
    completeVersion(
        scope,
        version,
        STATUS_RUNNING,
        firstText(completedBy, "system"),
        "统一产品核算流水线自动完成",
        request == null ? null : request.getSourceRevision());
    version.setStatus(STATUS_SUCCESS);
    return calculationResponse(scope, calculation);
  }

  private QuoteCuAdjustmentCalcResult calculate(
      Scope scope, QuoteCostRunTrialRequest request, boolean automaticCompletion) {
    PricePrepareReadinessResult readiness =
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth());
    requireReady(readiness);
    String pricePrepareNo = requireCurrentPricePrepareNo(readiness, request);
    return cuAdjustmentCalcService.calculate(
        new QuoteCuAdjustmentCalcRequest(
            scope.form(),
            scope.item(),
            scope.periodMonth(),
            pricePrepareNo,
            "QUOTE:" + scope.oaFormItemId(),
            null,
            automaticCompletion));
  }

  private QuoteCostRunWorkbenchResponse calculationResponse(
      Scope scope, QuoteCuAdjustmentCalcResult calculation) {
    QuoteCostRunVersion version = calculation.version();
    var result = calculation.costResult();

    QuoteCostRunSummaryResponse trialSummary = summary(version, calculation.totalCost());
    trialSummary.setPartItemCount(result.getPartItems().size());
    trialSummary.setCostItemCount(result.getCostItems().size());
    QuoteCostRunWorkbenchResponse response = baseResponse(scope, null, trialSummary);
    response.setCurrentDisplayVersion(trialSummary);
    response.setResultHeader(result.getResult());
    response.setPartItems(new ArrayList<>(result.getPartItems()));
    response.setCostItems(new ArrayList<>(result.getCostItems()));
    return response;
  }

  private void completeVersion(
      Scope scope,
      QuoteCostRunVersion version,
      String expectedStatus,
      String completedBy,
      String message,
      String expectedSourceRevision) {
    OaFormItem lockedItem =
        oaFormItemMapper.selectForCostCompletion(
            scope.oaFormItemId(),
            scope.form().getId(),
            required("businessUnitType", scope.form().getBusinessUnitType()));
    if (lockedItem == null) {
      throw new QuoteIngestException("报价产品行已不存在，成本版本不能完成");
    }
    QuoteCostingWorkspace workspace =
        workspaceService
            .find(scope.oaFormItemId(), scope.periodMonth())
            .orElseThrow(() -> new QuoteIngestException("当前核算工作区不存在，成本版本不能完成"));
    if (!Objects.equals(
        trimToNull(version.getOaPricePrepareNo()),
        trimToNull(workspace.getCurrentPrepareNo()))) {
      throw new QuoteIngestException("最终价格版本已更新，本次成本结果已回滚，请重新核算");
    }
    String sourceRevision = resolveAndVerifySourceRevision(scope, expectedSourceRevision);
    DataQuality dataQuality = requireCompleteVersionResult(version);

    LocalDateTime now = LocalDateTime.now();
    String versionNo = versionNoGenerator.nextVersionNo(scope.oaFormItemId(), scope.productCode());
    String inputFingerprint = trimToNull(workspace.getInputFingerprint());
    QuoteCostRunVersion patch = new QuoteCostRunVersion();
    patch.setId(version.getId());
    patch.setVersionNo(versionNo);
    patch.setStatus(STATUS_SUCCESS);
    patch.setInputFingerprint(inputFingerprint);
    patch.setSourceRevision(sourceRevision);
    patch.setDataQualityStatus(dataQuality.status());
    patch.setDataQualityWarningCount(dataQuality.warningCount());
    patch.setDataQualitySummary(dataQuality.summary());
    patch.setConfirmedBy(completedBy);
    patch.setConfirmedAt(now);
    patch.setConfirmMessage(message);
    int completed =
        versionMapper.update(
            patch,
            Wrappers.lambdaUpdate(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getId, version.getId())
                .eq(QuoteCostRunVersion::getStatus, expectedStatus));
    if (completed != 1) {
      throw new QuoteIngestException("成本版本已失效或状态已变化，请重新核算");
    }

    movePreviousCurrentVersionToHistory(scope, lockedItem.getConfirmedCostVersionId(), version.getId());
    markConfirmedCostRun(scope, version.getId(), now);
    markWorkspaceSuccess(
        workspace, version.getId(), inputFingerprint, sourceRevision, dataQuality, now);
    collaborationCostingGate.complete(scope.oaFormItemId(), scope.form().getBusinessUnitType());

    version.setVersionNo(versionNo);
    version.setStatus(STATUS_SUCCESS);
    version.setInputFingerprint(inputFingerprint);
    version.setSourceRevision(sourceRevision);
    version.setDataQualityStatus(dataQuality.status());
    version.setDataQualityWarningCount(dataQuality.warningCount());
    version.setDataQualitySummary(dataQuality.summary());
    version.setConfirmedBy(completedBy);
    version.setConfirmedAt(now);
    version.setConfirmMessage(message);
    // 同一事务内组装响应时沿用当前 scope；同步内存指针，避免刚完成的新版本被误标成历史。
    scope.item().setConfirmedCostVersionId(version.getId());
    scope.item().setCalcStatus("已核算");
    scope.item().setCalcAt(now);
  }

  private void movePreviousCurrentVersionToHistory(
      Scope scope, Long previousVersionId, Long newVersionId) {
    if (previousVersionId == null || previousVersionId.equals(newVersionId)) {
      return;
    }
    QuoteCostRunVersion previous = versionMapper.selectById(previousVersionId);
    if (previous == null
        || !scope.oaNo().equals(previous.getOaNo())
        || !scope.oaFormItemId().equals(previous.getOaFormItemId())
        || !QuoteCostRunStatus.isCurrentSuccess(previous.getStatus())) {
      throw new QuoteIngestException("原当前成功版本状态异常，本次核算未切换");
    }
    QuoteCostRunVersion historyPatch = new QuoteCostRunVersion();
    historyPatch.setStatus(STATUS_HISTORY);
    int historyCount =
        versionMapper.update(
            historyPatch,
            Wrappers.lambdaUpdate(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getId, previousVersionId)
                .in(
                    QuoteCostRunVersion::getStatus,
                    List.of(STATUS_SUCCESS, STATUS_CONFIRMED)));
    if (historyCount != 1) {
      throw new QuoteIngestException("原当前成功版本已变化，本次核算未切换");
    }
  }

  private DataQuality requireCompleteVersionResult(QuoteCostRunVersion version) {
    if (version == null || version.getId() == null || version.getTotalCost() == null) {
      throw new QuoteIngestException("成本版本汇总不完整，本次核算已回滚");
    }
    List<CostRunPartItem> parts = partRows(version.getId());
    List<CostRunCostItem> costs = costRows(version.getId());
    long actualPartCount = parts.size();
    long actualCostCount = costs.size();
    if (actualPartCount != intValue(version.getPartItemCount())
        || actualCostCount != intValue(version.getCostItemCount())
        || actualCostCount == 0) {
      throw new QuoteIngestException("成本版本明细不完整，本次核算已回滚");
    }
    List<CostRunCostItem> totals =
        costs.stream().filter(item -> "TOTAL".equals(item.getCostCode())).toList();
    if (totals.size() != 1
        || totals.get(0).getAmount() == null
        || totals.get(0).getAmount().compareTo(version.getTotalCost()) != 0) {
      throw new QuoteIngestException("成本版本总计行与汇总金额不一致，本次核算已回滚");
    }
    Set<String> warnings = new LinkedHashSet<>();
    for (CostRunCostItem cost : costs) {
      if (cost.getAmount() == null) {
        warnings.add(firstText(cost.getCostCode(), cost.getCostName(), "未知费用项") + " 金额为空");
      }
      if (isQualityWarningRemark(cost.getRemark())) {
        warnings.add(firstText(cost.getCostCode(), cost.getCostName(), "未知费用项")
            + "：" + cost.getRemark().trim());
      }
    }
    for (CostRunPartItem part : parts) {
      if (part.getAmount() == null) {
        warnings.add(firstText(part.getPartCode(), part.getPartName(), "未知部品") + " 金额为空");
      }
      if (isQualityWarningRemark(part.getRemark())) {
        warnings.add(firstText(part.getPartCode(), part.getPartName(), "未知部品")
            + "：" + part.getRemark().trim());
      }
    }
    String summary = warnings.isEmpty()
        ? "成本汇总、部品明细和费用明细完整"
        : truncateQualitySummary(String.join("；", warnings));
    return new DataQuality(
        warnings.isEmpty() ? "COMPLETE" : "WARNING", warnings.size(), summary);
  }

  private boolean isQualityWarningRemark(String remark) {
    if (!StringUtils.hasText(remark)) {
      return false;
    }
    String text = remark.trim();
    String riskText = NEGATED_HISTORICAL_PRICE_PATTERN.matcher(text).replaceAll("");
    return List.of(
            "缺失",
            "缺少",
            "失败",
            "异常",
            "为空",
            "未找到",
            "未配置",
            "沿用历史",
            "已过期",
            "不完整",
            "请财务关注",
            "警告")
        .stream()
        .anyMatch(riskText::contains);
  }

  private String resolveAndVerifySourceRevision(Scope scope, String expectedSourceRevision) {
    if (inputRevisionService == null) {
      return trimToNull(expectedSourceRevision);
    }
    String current = inputRevisionService.currentRevision(scope.form(), scope.item());
    if (StringUtils.hasText(expectedSourceRevision)
        && !Objects.equals(expectedSourceRevision.trim(), current)) {
      throw new QuoteIngestException("核算期间上游业务输入已变化，本次结果已回滚，请重新发起核算");
    }
    return current;
  }

  private String truncateQualitySummary(String value) {
    return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private int intValue(Integer value) {
    return value == null ? 0 : value;
  }

  private void markWorkspaceSuccess(
      QuoteCostingWorkspace workspace,
      Long versionId,
      String inputFingerprint,
      String sourceRevision,
      DataQuality dataQuality,
      LocalDateTime completedAt) {
    QuoteCostingWorkspace locked =
        workspaceService.lockOrCreate(
            workspace.getOaNo(),
            workspace.getOaFormItemId(),
            workspace.getProductCode(),
            workspace.getPeriodMonth(),
            workspace.getBusinessUnitType());
    QuoteCostRunVersion completedVersion = versionMapper.selectById(versionId);
    if (completedVersion == null
        || !Objects.equals(
            trimToNull(completedVersion.getOaPricePrepareNo()),
            trimToNull(locked.getCurrentPrepareNo()))) {
      throw new QuoteIngestException("最终价格版本在成本完成前已变化，本次核算已回滚");
    }
    int expectedVersion = locked.getLockVersion() == null ? 0 : locked.getLockVersion();
    locked.setWorkspaceStatus(STATUS_SUCCESS);
    locked.setCurrentStep("COST_RUN");
    locked.setCurrentCostVersionId(versionId);
    locked.setLastSuccessInputFingerprint(inputFingerprint);
    locked.setSourceRevision(sourceRevision);
    locked.setLastSuccessSourceRevision(sourceRevision);
    locked.setDataQualityStatus(dataQuality.status());
    locked.setDataQualityWarningCount(dataQuality.warningCount());
    locked.setDataQualitySummary(dataQuality.summary());
    locked.setGapCount(0);
    locked.setStaleReasonCode(null);
    locked.setLastErrorStep(null);
    locked.setLastErrorCode(null);
    locked.setLastErrorMessage(null);
    locked.setLastCheckedAt(completedAt);
    workspaceService.update(locked, expectedVersion);
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
  @Transactional(readOnly = true)
  public int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException {
    Scope scope = requireScope(oaNo, oaFormItemId, null);
    QuoteCostRunVersion version = requireOwnedVersion(scope, versionId, null);
    List<CostRunPartItem> parts = partRows(versionId);
    List<CostRunCostItem> costs = costRows(versionId);
    try (Workbook workbook = new XSSFWorkbook()) {
      CellStyle headerStyle = headerStyle(workbook);
      writeSummarySheet(workbook, headerStyle, version);
      writePartSheet(workbook, headerStyle, parts);
      writeCostSheet(workbook, headerStyle, costs);
      workbook.write(output);
    }
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
    return response;
  }

  private void fillResultRows(QuoteCostRunWorkbenchResponse response, Long versionId) {
    CostRunResultDto result = costRunResultService.getResult(versionId);
    if (result != null) {
      response.setResultHeader(result);
    }
    response.setPartItems(partRows(versionId).stream().map(this::toPartDto).toList());
    response.setCostItems(costRows(versionId).stream().map(this::toCostDto).toList());
  }

  private List<String> readinessBlockingReasons(Scope scope) {
    PricePrepareReadinessResult readiness =
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth());
    if (isReady(readiness)) {
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

  private void requireReady(PricePrepareReadinessResult readiness) {
    if (isReady(readiness)) {
      return;
    }
    String message =
        readiness == null || !StringUtils.hasText(readiness.getMessage())
            ? "价格准备未完成，不能发起成本试算"
            : readiness.getMessage();
    throw new QuoteIngestException(message);
  }

  private String requireCurrentPricePrepareNo(
      PricePrepareReadinessResult readiness, QuoteCostRunTrialRequest request) {
    String currentPrepareNo = trimToNull(readiness == null ? null : readiness.getPrepareNo());
    if (currentPrepareNo == null) {
      throw new QuoteIngestException("最终价格快照不存在，请先生成最终价格");
    }
    String requestedPrepareNo =
        trimToNull(request == null ? null : request.getPricePrepareNo());
    if (requestedPrepareNo != null && !currentPrepareNo.equals(requestedPrepareNo)) {
      throw new QuoteIngestException("最终价格版本已更新，请刷新页面后重新开始核算");
    }
    return currentPrepareNo;
  }

  private boolean isReady(PricePrepareReadinessResult readiness) {
    return readiness != null
        && "READY".equals(readiness.getStatus())
        && "SUCCESS".equals(readiness.getBatchStatus())
        && readiness.getGapCount() == 0;
  }

  private Scope requireScope(String oaNo, Long oaFormItemId, String periodMonth) {
    String oaNoValue = required("oaNo", oaNo);
    String currentBusinessUnit = currentBusinessUnit();
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
    String formBusinessUnit = trimToNull(form.getBusinessUnitType());
    String itemBusinessUnit = trimToNull(item.getBusinessUnitType());
    if ((formBusinessUnit != null && !currentBusinessUnit.equals(formBusinessUnit))
        || (itemBusinessUnit != null && !currentBusinessUnit.equals(itemBusinessUnit))
        || (formBusinessUnit == null && itemBusinessUnit == null)) {
      throw new QuoteIngestException("当前业务单元无权访问该报价产品行");
    }
    String productCode =
        required("productCode", QuoteProductIdentityUtils.resolveCostingCode(item));
    String period =
        StringUtils.hasText(periodMonth)
            ? CostPricingPeriodUtils.requireCurrentPricingMonth(periodMonth)
            : CostPricingPeriodUtils.currentPricingMonth();
    return new Scope(
        form, item, oaNoValue, oaFormItemId, productCode, period, currentBusinessUnit);
  }

  private String currentBusinessUnit() {
    String value = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!StringUtils.hasText(value)) {
      throw new QuoteIngestException("当前业务单元不能为空");
    }
    return value.trim();
  }

  private QuoteCostRunVersion requireOwnedVersion(
      Scope scope, Long versionId, String costRunNo) {
    QuoteCostRunVersion version;
    if (versionId != null) {
      if (versionId <= 0) {
        throw new QuoteIngestException("成本版本 ID 必须大于0");
      }
      version = versionMapper.selectById(versionId);
    } else {
      version =
          versionMapper.selectOne(
              Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                  .eq(QuoteCostRunVersion::getCostRunNo, required("costRunNo", costRunNo))
                  .last("LIMIT 1"));
    }
    if (version == null
        || !scope.oaNo().equals(version.getOaNo())
        || !scope.oaFormItemId().equals(version.getOaFormItemId())
        || !scope.productCode().equals(version.getProductCode())
        || (StringUtils.hasText(version.getBusinessUnitType())
            && !scope.businessUnitType().equals(version.getBusinessUnitType().trim()))) {
      throw new QuoteIngestException("成本版本不属于当前产品行或当前业务单元");
    }
    return version;
  }

  private QuoteCostRunSummaryResponse latestInProgressVersion(Scope scope) {
    return latestVersion(scope, List.of(STATUS_TRIAL, STATUS_RUNNING));
  }

  private QuoteCostRunSummaryResponse latestSuccessfulVersion(Scope scope) {
    return latestVersion(scope, List.of(STATUS_SUCCESS, STATUS_CONFIRMED));
  }

  private QuoteCostRunSummaryResponse latestVersion(Scope scope, List<String> statuses) {
    QuoteCostRunVersion version =
        versionMapper.selectOne(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
                .eq(QuoteCostRunVersion::getOaFormItemId, scope.oaFormItemId())
                .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
                .eq(QuoteCostRunVersion::getPricingMonth, scope.periodMonth())
                .in(QuoteCostRunVersion::getStatus, statuses)
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
    if (!QuoteCostRunStatus.isInProgress(version.getStatus())) {
      return true;
    }
    return latestTrialId != null && latestTrialId.equals(version.getId());
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
    item.setCanViewSheet(version.getId() != null && StringUtils.hasText(version.getCostRunNo()));
    item.setCanViewTrace(
        !QuoteCostRunStatus.isInProgress(version.getStatus())
            && StringUtils.hasText(version.getCostRunNo()));
    item.setCurrentConfirmed(version.getId() != null && version.getId().equals(confirmedVersionId));
    item.setStale(!QuoteCostRunStatus.isInProgress(version.getStatus()) && !item.isCurrentConfirmed());
    return item;
  }

  private String displayVersionStatus(String status, Long id, Long confirmedVersionId) {
    if (STATUS_TRIAL.equals(status)) {
      return "历史试算";
    }
    if (STATUS_RUNNING.equals(status)) {
      return "核算中";
    }
    if (id != null && id.equals(confirmedVersionId)) {
      return "当前成功";
    }
    if (QuoteCostRunStatus.isCurrentSuccess(status) || QuoteCostRunStatus.isHistorical(status)) {
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
    if (QuoteCostRunStatus.isInProgress(item.getStatus())) {
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
    response.setPricePrepareNo(version.getPricePrepareNo());
    response.setOaPricePrepareNo(version.getOaPricePrepareNo());
    response.setFinancePricePrepareNo(version.getFinancePricePrepareNo());
    response.setFinanceCuPrice(version.getFinanceCuPrice());
    response.setOaCuPrice(version.getOaCuPrice());
    response.setFinanceCuPricePerTon(toPerTon(version.getFinanceCuPrice()));
    response.setOaCuPricePerTon(toPerTon(version.getOaCuPrice()));
    response.setFinanceBasePriceId(version.getFinanceBasePriceId());
    response.setStatus(version.getStatus());
    response.setSourceRevision(version.getSourceRevision());
    response.setDataQualityStatus(version.getDataQualityStatus());
    response.setDataQualityWarningCount(version.getDataQualityWarningCount());
    response.setDataQualitySummary(version.getDataQualitySummary());
    response.setTotalCost(totalCost);
    response.setFinanceBaseTotalCost(totalCost);
    response.setFinanceMaterialCost(version.getFinanceMaterialCost());
    response.setOaMaterialCost(version.getOaMaterialCost());
    response.setCuMaterialAdjustment(version.getCuMaterialAdjustment());
    response.setFinalQuoteAmount(version.getFinalQuoteAmount());
    response.setPartItemCount(version.getPartItemCount());
    response.setCostItemCount(version.getCostItemCount());
    response.setTrialStartedAt(version.getTrialStartedAt());
    response.setTrialFinishedAt(version.getTrialFinishedAt());
    response.setConfirmedBy(version.getConfirmedBy());
    response.setConfirmedAt(version.getConfirmedAt());
    response.setConfirmMessage(version.getConfirmMessage());
    return response;
  }

  private BigDecimal toPerTon(BigDecimal pricePerKg) {
    return pricePerKg == null ? null : pricePerKg.multiply(KG_PER_TON);
  }

  private record DataQuality(String status, int warningCount, String summary) {}

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
    dto.setPriceOrgCode(item.getPriceOrgCode());
    dto.setMaterialOrganizationCode(item.getMaterialOrganizationCode());
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

  private CellStyle headerStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setBold(true);
    style.setFont(font);
    style.setWrapText(true);
    return style;
  }

  private void writeSummarySheet(
      Workbook workbook, CellStyle headerStyle, QuoteCostRunVersion version) {
    Sheet sheet = workbook.createSheet("汇总");
    Row header = sheet.createRow(0);
    writeHeader(header, headerStyle, "指标", "快照值");
    int row = 1;
    row = writeSummaryRow(sheet, row, "OA单号", version.getOaNo());
    row = writeSummaryRow(sheet, row, "OA产品行ID", version.getOaFormItemId());
    row = writeSummaryRow(sheet, row, "产品料号", version.getProductCode());
    row = writeSummaryRow(sheet, row, "成本核算批次", version.getCostRunNo());
    row = writeSummaryRow(sheet, row, "成本版本", version.getVersionNo());
    row = writeSummaryRow(sheet, row, "状态", version.getStatus());
    row = writeSummaryRow(sheet, row, "财务Cu基准（元/吨）", toPerTon(version.getFinanceCuPrice()));
    row = writeSummaryRow(sheet, row, "OA锁定Cu（元/吨）", toPerTon(version.getOaCuPrice()));
    row = writeSummaryRow(sheet, row, "财务基准材料费", version.getFinanceMaterialCost());
    row = writeSummaryRow(sheet, row, "OA锁价材料费", version.getOaMaterialCost());
    row = writeSummaryRow(sheet, row, "Cu材料费差额", version.getCuMaterialAdjustment());
    row = writeSummaryRow(sheet, row, "财务基准不含税总成本", version.getTotalCost());
    row = writeSummaryRow(sheet, row, "最终报价", version.getFinalQuoteAmount());
    row = writeSummaryRow(sheet, row, "OA价格准备批次", version.getOaPricePrepareNo());
    writeSummaryRow(sheet, row, "财务价格准备批次", version.getFinancePricePrepareNo());
    autoSize(sheet, 2);
  }

  private int writeSummaryRow(Sheet sheet, int rowIndex, String label, Object value) {
    Row row = sheet.createRow(rowIndex);
    writeCell(row, 0, label);
    writeCell(row, 1, value);
    return rowIndex + 1;
  }

  private void writePartSheet(
      Workbook workbook, CellStyle headerStyle, List<CostRunPartItem> parts) {
    Sheet sheet = workbook.createSheet("成本部品");
    writeHeader(
        sheet.createRow(0),
        headerStyle,
        "部品料号",
        "部品名称",
        "数量",
        "单价",
        "金额",
        "价格来源",
        "备注");
    int rowIndex = 1;
    for (CostRunPartItem part : parts) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, part.getPartCode());
      writeCell(row, 1, part.getPartName());
      writeCell(row, 2, part.getQty());
      writeCell(row, 3, part.getUnitPrice());
      writeCell(row, 4, part.getAmount());
      writeCell(row, 5, part.getPriceSource());
      writeCell(row, 6, part.getRemark());
    }
    autoSize(sheet, 7);
  }

  private void writeCostSheet(
      Workbook workbook, CellStyle headerStyle, List<CostRunCostItem> costs) {
    Sheet sheet = workbook.createSheet("成本项目");
    writeHeader(
        sheet.createRow(0),
        headerStyle,
        "成本编码",
        "成本名称",
        "基数",
        "费率",
        "金额",
        "分类",
        "备注");
    int rowIndex = 1;
    for (CostRunCostItem cost : costs) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, cost.getCostCode());
      writeCell(row, 1, cost.getCostName());
      writeCell(row, 2, cost.getBaseAmount());
      writeCell(row, 3, cost.getRate());
      writeCell(row, 4, cost.getAmount());
      writeCell(row, 5, cost.getCategory());
      writeCell(row, 6, cost.getRemark());
    }
    autoSize(sheet, 7);
  }

  private void writeHeader(Row row, CellStyle headerStyle, String... labels) {
    for (int i = 0; i < labels.length; i++) {
      var cell = row.createCell(i);
      cell.setCellValue(labels[i]);
      cell.setCellStyle(headerStyle);
    }
  }

  private void writeCell(Row row, int column, Object value) {
    var cell = row.createCell(column);
    if (value == null) {
      cell.setBlank();
    } else if (value instanceof BigDecimal decimal) {
      cell.setCellValue(decimal.toPlainString());
    } else if (value instanceof Boolean bool) {
      cell.setCellValue(bool ? "是" : "否");
    } else {
      cell.setCellValue(String.valueOf(value));
    }
  }

  private void autoSize(Sheet sheet, int columnCount) {
    for (int column = 0; column < columnCount; column++) {
      sheet.autoSizeColumn(column);
      sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 80 * 256));
    }
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

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String businessUnitType) {}
}
