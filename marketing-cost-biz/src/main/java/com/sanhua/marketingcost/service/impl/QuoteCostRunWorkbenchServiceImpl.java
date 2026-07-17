package com.sanhua.marketingcost.service.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse.CostRunVersionItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCuMaterialDifferenceResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.mapper.QuoteCuMaterialDiffItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunWorkbenchServiceImpl implements QuoteCostRunWorkbenchService {

  private static final String STATUS_TRIAL = "TRIAL";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_VOIDED = "VOIDED";
  private static final BigDecimal KG_PER_TON = new BigDecimal("1000");
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final CostRunResultMapper resultMapper;
  private final CostRunPartItemMapper partItemMapper;
  private final CostRunCostItemMapper costItemMapper;
  private final QuoteCuMaterialDiffItemMapper diffItemMapper;
  private final QuoteCostingWorkbenchSummaryMapper summaryMapper;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final QuoteCostRunVersionNoGenerator versionNoGenerator;
  private final QuoteCuAdjustmentCalcService cuAdjustmentCalcService;

  public QuoteCostRunWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteCostRunVersionMapper versionMapper,
      CostRunResultMapper resultMapper,
      CostRunPartItemMapper partItemMapper,
      CostRunCostItemMapper costItemMapper,
      QuoteCuMaterialDiffItemMapper diffItemMapper,
      QuoteCostingWorkbenchSummaryMapper summaryMapper,
      PricePrepareReadinessService pricePrepareReadinessService,
      QuoteCostRunVersionNoGenerator versionNoGenerator,
      QuoteCuAdjustmentCalcService cuAdjustmentCalcService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.versionMapper = versionMapper;
    this.resultMapper = resultMapper;
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
    this.diffItemMapper = diffItemMapper;
    this.summaryMapper = summaryMapper;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
    this.versionNoGenerator = versionNoGenerator;
    this.cuAdjustmentCalcService = cuAdjustmentCalcService;
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
    QuoteCostRunSummaryResponse latestTrial = latestVersion(scope, STATUS_TRIAL);
    QuoteCostRunSummaryResponse latestConfirmed = latestVersion(scope, STATUS_CONFIRMED);
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
    QuoteCostRunSummaryResponse latestTrial = latestVersion(scope, STATUS_TRIAL);
    QuoteCostRunSummaryResponse latestConfirmed = latestVersion(scope, STATUS_CONFIRMED);
    QuoteCostRunWorkbenchResponse response = baseResponse(scope, latestTrial, latestConfirmed);
    response.setCurrentDisplayVersion(summary(selected, selected.getTotalCost()));
    fillResultRows(response, selected.getId());
    response.setCanConfirm(STATUS_TRIAL.equals(selected.getStatus()));
    return response;
  }

  @Override
  @Transactional(readOnly = true)
  public PageResult<QuoteCuMaterialDifferenceResponse> pageCuMaterialDifferences(
      String oaNo,
      Long oaFormItemId,
      String costRunNo,
      Integer pageNo,
      Integer pageSize,
      String parentMaterialCode,
      String materialCode,
      Boolean onlyDifferent,
      String differenceSign) {
    Scope scope = requireScope(oaNo, oaFormItemId, null);
    QuoteCostRunVersion version = requireOwnedVersion(scope, null, required("costRunNo", costRunNo));
    var query =
        Wrappers.lambdaQuery(QuoteCuMaterialDiffItem.class)
            .eq(QuoteCuMaterialDiffItem::getCostRunVersionId, version.getId())
            .eq(QuoteCuMaterialDiffItem::getBusinessUnitType, scope.businessUnitType());
    if (StringUtils.hasText(parentMaterialCode)) {
      query.eq(QuoteCuMaterialDiffItem::getParentMaterialCode, parentMaterialCode.trim());
    }
    if (StringUtils.hasText(materialCode)) {
      query.eq(QuoteCuMaterialDiffItem::getMaterialCode, materialCode.trim());
    }
    if (Boolean.TRUE.equals(onlyDifferent)) {
      query.ne(QuoteCuMaterialDiffItem::getDiffAmount, BigDecimal.ZERO);
    }
    applyDifferenceSign(query, differenceSign);
    query.orderByAsc(QuoteCuMaterialDiffItem::getLineNo)
        .orderByAsc(QuoteCuMaterialDiffItem::getId);
    Page<QuoteCuMaterialDiffItem> page =
        diffItemMapper.selectPage(
            new Page<>(pageNo(pageNo), pageSize(pageSize)), query);
    List<QuoteCuMaterialDifferenceResponse> rows =
        page.getRecords().stream().map(this::toDifferenceDto).toList();
    return new PageResult<>(rows, page.getTotal());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
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
    PricePrepareReadinessResult readiness =
        pricePrepareReadinessService.check(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.productCode(),
            scope.periodMonth(),
            priceTypeConfirmation == null ? null : priceTypeConfirmation.getConfirmNo());
    requireReady(readiness);
    String pricePrepareNo = requireCurrentPricePrepareNo(readiness, request);

    QuoteCuAdjustmentCalcResult calculation =
        cuAdjustmentCalcService.calculate(
            new QuoteCuAdjustmentCalcRequest(
                scope.form(),
                scope.item(),
                scope.periodMonth(),
                pricePrepareNo,
                "QUOTE:" + scope.oaFormItemId(),
                null));
    QuoteCostRunVersion version = calculation.version();
    var result = calculation.costResult();
    cleanupTrialVersions(scope, version.getId());

    QuoteCostRunSummaryResponse trialSummary = summary(version, calculation.totalCost());
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
    int confirmed =
        versionMapper.update(
            patch,
            Wrappers.lambdaUpdate(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getId, version.getId())
                .eq(QuoteCostRunVersion::getStatus, STATUS_TRIAL));
    if (confirmed != 1) {
      throw new QuoteIngestException("成本试算版本已失效或状态已变化，请重新试算");
    }

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
    String productCode = required("productCode", item.getMaterialNo());
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
    if (!staleVersionIds.isEmpty()) {
      QuoteCostRunVersion versionPatch = new QuoteCostRunVersion();
      versionPatch.setStatus(STATUS_VOIDED);
      versionMapper.update(
          versionPatch,
          Wrappers.lambdaUpdate(QuoteCostRunVersion.class)
              .in(QuoteCostRunVersion::getId, staleVersionIds)
              .eq(QuoteCostRunVersion::getStatus, STATUS_TRIAL));
      CostRunResult resultPatch = new CostRunResult();
      resultPatch.setResultStatus(STATUS_VOIDED);
      resultMapper.update(
          resultPatch,
          Wrappers.lambdaUpdate(CostRunResult.class)
              .in(CostRunResult::getCostRunVersionId, staleVersionIds)
              .eq(CostRunResult::getResultStatus, STATUS_TRIAL));
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

  private void applyDifferenceSign(
      LambdaQueryWrapper<QuoteCuMaterialDiffItem> query, String differenceSign) {
    if (!StringUtils.hasText(differenceSign)) {
      return;
    }
    switch (differenceSign.trim().toUpperCase(Locale.ROOT)) {
      case "POSITIVE" -> query.gt(QuoteCuMaterialDiffItem::getDiffAmount, BigDecimal.ZERO);
      case "NEGATIVE" -> query.lt(QuoteCuMaterialDiffItem::getDiffAmount, BigDecimal.ZERO);
      case "ZERO" -> query.eq(QuoteCuMaterialDiffItem::getDiffAmount, BigDecimal.ZERO);
      default -> throw new IllegalArgumentException(
          "differenceSign 只支持 POSITIVE、NEGATIVE 或 ZERO");
    }
  }

  private long pageNo(Integer value) {
    if (value == null) {
      return 1L;
    }
    if (value < 1) {
      throw new IllegalArgumentException("pageNo 必须大于等于1");
    }
    return value.longValue();
  }

  private long pageSize(Integer value) {
    if (value == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (value < 1 || value > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("pageSize 必须在1到" + MAX_PAGE_SIZE + "之间");
    }
    return value.longValue();
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
    response.setOaPricePrepareNo(version.getOaPricePrepareNo());
    response.setFinancePricePrepareNo(version.getFinancePricePrepareNo());
    response.setFinanceCuPrice(version.getFinanceCuPrice());
    response.setOaCuPrice(version.getOaCuPrice());
    response.setFinanceCuPricePerTon(toPerTon(version.getFinanceCuPrice()));
    response.setOaCuPricePerTon(toPerTon(version.getOaCuPrice()));
    response.setFinanceBasePriceId(version.getFinanceBasePriceId());
    response.setStatus(version.getStatus());
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

  private QuoteCuMaterialDifferenceResponse toDifferenceDto(QuoteCuMaterialDiffItem item) {
    QuoteCuMaterialDifferenceResponse dto = new QuoteCuMaterialDifferenceResponse();
    dto.setId(item.getId());
    dto.setCostRunVersionId(item.getCostRunVersionId());
    dto.setCostRunNo(item.getCostRunNo());
    dto.setLineNo(item.getLineNo());
    dto.setSettlementKey(item.getSettlementKey());
    dto.setParentSettlementKey(item.getParentSettlementKey());
    dto.setDetailLevel(item.getDetailLevel());
    dto.setContributesToAdjustment(Integer.valueOf(1).equals(item.getContributesToAdjustment()));
    dto.setBomRowId(item.getBomRowId());
    dto.setTopProductCode(item.getTopProductCode());
    dto.setParentMaterialCode(item.getParentMaterialCode());
    dto.setMaterialCode(item.getMaterialCode());
    dto.setMaterialName(item.getMaterialName());
    dto.setItemType(item.getItemType());
    dto.setQuantity(item.getQuantity());
    dto.setFinanceUnitPrice(item.getFinanceUnitPrice());
    dto.setOaUnitPrice(item.getOaUnitPrice());
    dto.setFinanceAmount(item.getFinanceAmount());
    dto.setOaAmount(item.getOaAmount());
    dto.setDiffAmount(item.getDiffAmount());
    dto.setCuAffected(Integer.valueOf(1).equals(item.getCuAffected()));
    dto.setPriceFormulaRefType(item.getPriceFormulaRefType());
    dto.setPriceFormulaRefId(item.getPriceFormulaRefId());
    dto.setTraceJson(item.getTraceJson());
    return dto;
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
    dto.setFinanceMaterialCost(result.getFinanceMaterialCost());
    dto.setOaMaterialCost(result.getOaMaterialCost());
    dto.setCuMaterialAdjustment(result.getCuMaterialAdjustment());
    dto.setFinalQuoteAmount(result.getFinalQuoteAmount());
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
