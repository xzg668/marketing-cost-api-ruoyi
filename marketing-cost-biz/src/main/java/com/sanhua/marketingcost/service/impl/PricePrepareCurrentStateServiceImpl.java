package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.PricePrepareCurrentStateService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PricePrepareCurrentStateServiceImpl implements PricePrepareCurrentStateService {

  private static final int ACTIVE = 1;
  private static final int HISTORY = 0;
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_RUNNING = "RUNNING";

  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;
  private final QuoteCostRunVersionMapper costVersionMapper;
  private final PackageComponentPriceMapper packagePriceMapper;
  private final PackageComponentPriceDetailMapper packageDetailMapper;
  private final MakePartPriceCalcRowMapper makePartRowMapper;
  private final MakePartPriceGapItemMapper makePartGapMapper;
  private final PriceLinkedCalcItemMapper linkedCalcItemMapper;
  private final QuoteCostingWorkspaceService workspaceService;
  private final QuoteCostRunVersionInvalidationService versionInvalidationService;

  public PricePrepareCurrentStateServiceImpl(
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      QuoteCostRunVersionMapper costVersionMapper,
      PackageComponentPriceMapper packagePriceMapper,
      PackageComponentPriceDetailMapper packageDetailMapper,
      MakePartPriceCalcRowMapper makePartRowMapper,
      MakePartPriceGapItemMapper makePartGapMapper,
      PriceLinkedCalcItemMapper linkedCalcItemMapper,
      QuoteCostingWorkspaceService workspaceService,
      QuoteCostRunVersionInvalidationService versionInvalidationService) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.costVersionMapper = costVersionMapper;
    this.packagePriceMapper = packagePriceMapper;
    this.packageDetailMapper = packageDetailMapper;
    this.makePartRowMapper = makePartRowMapper;
    this.makePartGapMapper = makePartGapMapper;
    this.linkedCalcItemMapper = linkedCalcItemMapper;
    this.workspaceService = workspaceService;
    this.versionInvalidationService = versionInvalidationService;
  }

  /**
   * 发布动作必须与调用方已存在的事务合并：工作台同时生成 OA/财务批次时，财务失败会让
   * OA 候选及指针一起回滚；直接生成时，本方法自身提供短事务边界。
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void finalizeBatch(PricePrepareBatch batch) {
    if (!isScopedOaBatch(batch)) {
      return;
    }
    QuoteCostingWorkspace workspace =
        workspaceService.lockOrCreate(
            batch.getOaNo(),
            batch.getOaFormItemId(),
            batch.getTopProductCode(),
            batch.getPeriodMonth(),
            batch.getBusinessUnitType());
    if (isSuccessful(batch)) {
      publishSuccessfulBatch(batch, workspace);
    } else {
      publishFailedAttempt(batch, workspace);
    }
    cleanupUnreferencedBatches(batch, workspace.getCurrentPrepareNo());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean discardPromotedFailedAttempt(String prepareNo) {
    if (!StringUtils.hasText(prepareNo)) {
      return false;
    }
    PricePrepareBatch batch = batchMapper.selectOne(
        Wrappers.<PricePrepareBatch>lambdaQuery()
            .eq(PricePrepareBatch::getPrepareNo, prepareNo.trim())
            .last("LIMIT 1"));
    if (batch == null || isSuccessful(batch) || STATUS_RUNNING.equalsIgnoreCase(batch.getStatus())) {
      return false;
    }
    QuoteCostingWorkspace workspace = workspaceService
        .find(batch.getOaFormItemId(), batch.getPeriodMonth()).orElse(null);
    if (workspace != null && prepareNo.trim().equals(workspace.getCurrentPrepareNo())) {
      return false;
    }
    Long costReferences = costVersionMapper.selectCount(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .and(query -> query
                .eq(QuoteCostRunVersion::getPricePrepareNo, prepareNo.trim())
                .or().eq(QuoteCostRunVersion::getOaPricePrepareNo, prepareNo.trim())
                .or().eq(QuoteCostRunVersion::getFinancePricePrepareNo, prepareNo.trim())));
    if (costReferences != null && costReferences > 0) {
      return false;
    }
    Long childBatches = batchMapper.selectCount(
        Wrappers.<PricePrepareBatch>lambdaQuery()
            .eq(PricePrepareBatch::getSourcePrepareNo, prepareNo.trim()));
    if (childBatches != null && childBatches > 0) {
      return false;
    }
    deletePrepareBatch(batch);
    return true;
  }

  private void publishSuccessfulBatch(
      PricePrepareBatch batch, QuoteCostingWorkspace workspace) {
    deactivateCurrentItems(batch, batch.getPrepareNo());
    PricePrepareItem activeItem = new PricePrepareItem();
    activeItem.setCurrentFlag(ACTIVE);
    itemMapper.update(
        activeItem,
        Wrappers.<PricePrepareItem>lambdaUpdate()
            .eq(PricePrepareItem::getPrepareNo, batch.getPrepareNo()));
    deactivateCurrentGaps(batch, null);

    int expectedLockVersion = valueOrZero(workspace.getLockVersion());
    workspace.setCurrentPrepareNo(batch.getPrepareNo());
    workspace.setWorkspaceStatus("PRICE_READY");
    workspace.setCurrentStep("COST_RUN");
    workspace.setGapCount(0);
    workspace.setCarriedForwardPriceCount(valueOrZero(batch.getWarningCount()));
    workspace.setStaleReasonCode(null);
    workspace.setLastErrorStep(null);
    workspace.setLastErrorCode(null);
    workspace.setLastErrorMessage(null);
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedLockVersion);

    // 只有完整最终价格正式发布后，旧试算才失效；失败候选不能清空旧试算。
    versionInvalidationService.invalidateProduct(
        batch.getOaNo(),
        batch.getOaFormItemId(),
        batch.getTopProductCode(),
        batch.getPeriodMonth());
  }

  private void publishFailedAttempt(
      PricePrepareBatch batch, QuoteCostingWorkspace workspace) {
    // 半套候选明细不成为业务结果，也不长期保留；当前缺口保留给页面和协作使用。
    cleanupGeneratedArtifacts(batch);
    itemMapper.delete(
        Wrappers.<PricePrepareItem>lambdaQuery()
            .eq(PricePrepareItem::getPrepareNo, batch.getPrepareNo()));
    deletePackageCandidate(batch.getPrepareNo());
    deactivateCurrentGaps(batch, batch.getPrepareNo());
    PricePrepareGap activeGap = new PricePrepareGap();
    activeGap.setCurrentFlag(ACTIVE);
    gapMapper.update(
        activeGap,
        Wrappers.<PricePrepareGap>lambdaUpdate()
            .eq(PricePrepareGap::getPrepareNo, batch.getPrepareNo()));

    int expectedLockVersion = valueOrZero(workspace.getLockVersion());
    workspace.setWorkspaceStatus("FAILED".equalsIgnoreCase(batch.getStatus())
        ? "PRICE_ERROR"
        : "PRICE_BLOCKED");
    workspace.setCurrentStep("PRICE_PREPARE");
    workspace.setGapCount(Math.max(1, valueOrZero(batch.getGapCount())));
    workspace.setStaleReasonCode("FAILED".equalsIgnoreCase(batch.getStatus())
        ? "PRICE_PREPARE_FAILED"
        : "PRICE_GAP");
    workspace.setLastErrorStep("PRICE_PREPARE");
    workspace.setLastErrorCode(workspace.getStaleReasonCode());
    workspace.setLastErrorMessage(batch.getMessage());
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedLockVersion);
  }

  private void deactivateCurrentItems(PricePrepareBatch batch, String exceptPrepareNo) {
    PricePrepareItem history = new PricePrepareItem();
    history.setCurrentFlag(HISTORY);
    var query = currentItemScope(batch);
    if (StringUtils.hasText(exceptPrepareNo)) {
      query.ne(PricePrepareItem::getPrepareNo, exceptPrepareNo);
    }
    itemMapper.update(history, query);
  }

  private void deactivateCurrentGaps(PricePrepareBatch batch, String exceptPrepareNo) {
    PricePrepareGap history = new PricePrepareGap();
    history.setCurrentFlag(HISTORY);
    var query = currentGapScope(batch);
    if (StringUtils.hasText(exceptPrepareNo)) {
      query.ne(PricePrepareGap::getPrepareNo, exceptPrepareNo);
    }
    gapMapper.update(history, query);
  }

  private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PricePrepareItem>
      currentItemScope(PricePrepareBatch batch) {
    return Wrappers.<PricePrepareItem>lambdaUpdate()
        .eq(PricePrepareItem::getOaNo, batch.getOaNo())
        .eq(PricePrepareItem::getOaFormItemId, batch.getOaFormItemId())
        .eq(PricePrepareItem::getTopProductCode, batch.getTopProductCode())
        .eq(PricePrepareItem::getPeriodMonth, batch.getPeriodMonth())
        .eq(PricePrepareItem::getCurrentFlag, ACTIVE);
  }

  private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PricePrepareGap>
      currentGapScope(PricePrepareBatch batch) {
    return Wrappers.<PricePrepareGap>lambdaUpdate()
        .eq(PricePrepareGap::getOaNo, batch.getOaNo())
        .eq(PricePrepareGap::getOaFormItemId, batch.getOaFormItemId())
        .eq(PricePrepareGap::getTopProductCode, batch.getTopProductCode())
        .eq(PricePrepareGap::getPeriodMonth, batch.getPeriodMonth())
        .eq(PricePrepareGap::getCurrentFlag, ACTIVE);
  }

  private void cleanupUnreferencedBatches(
      PricePrepareBatch completedBatch, String currentPrepareNo) {
    List<PricePrepareBatch> candidates =
        batchMapper.selectList(
            Wrappers.<PricePrepareBatch>lambdaQuery()
                .eq(PricePrepareBatch::getOaNo, completedBatch.getOaNo())
                .eq(PricePrepareBatch::getOaFormItemId, completedBatch.getOaFormItemId())
                .eq(PricePrepareBatch::getTopProductCode, completedBatch.getTopProductCode())
                .eq(PricePrepareBatch::getPeriodMonth, completedBatch.getPeriodMonth())
                .lt(completedBatch.getId() != null, PricePrepareBatch::getId, completedBatch.getId())
                .ne(PricePrepareBatch::getStatus, STATUS_RUNNING));
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    Set<String> protectedPrepareNos = referencedPrepareNos(completedBatch);
    protectedPrepareNos.add(completedBatch.getPrepareNo());
    if (StringUtils.hasText(currentPrepareNo)) {
      protectedPrepareNos.add(currentPrepareNo.trim());
    }
    // 财务对比批次依赖其 OA 来源；来源被保护时，财务批次也必须一起保护。
    for (PricePrepareBatch candidate : candidates) {
      if (candidate != null
          && StringUtils.hasText(candidate.getSourcePrepareNo())
          && protectedPrepareNos.contains(candidate.getSourcePrepareNo())) {
        protectedPrepareNos.add(candidate.getPrepareNo());
      }
    }
    for (PricePrepareBatch candidate : candidates) {
      if (candidate == null
          || !StringUtils.hasText(candidate.getPrepareNo())
          || protectedPrepareNos.contains(candidate.getPrepareNo())) {
        continue;
      }
      deletePrepareBatch(candidate);
    }
  }

  private Set<String> referencedPrepareNos(PricePrepareBatch scope) {
    List<QuoteCostRunVersion> versions =
        costVersionMapper.selectList(
            Wrappers.<QuoteCostRunVersion>lambdaQuery()
                .eq(QuoteCostRunVersion::getOaNo, scope.getOaNo())
                .eq(QuoteCostRunVersion::getOaFormItemId, scope.getOaFormItemId())
                .eq(QuoteCostRunVersion::getProductCode, scope.getTopProductCode())
                .eq(QuoteCostRunVersion::getPricingMonth, scope.getPeriodMonth()));
    Set<String> result = new HashSet<>();
    for (QuoteCostRunVersion version : versions == null ? List.<QuoteCostRunVersion>of() : versions) {
      addText(result, version.getPricePrepareNo());
      addText(result, version.getOaPricePrepareNo());
      addText(result, version.getFinancePricePrepareNo());
    }
    return result;
  }

  private void deletePrepareBatch(PricePrepareBatch batch) {
    String prepareNo = batch.getPrepareNo();
    cleanupGeneratedArtifacts(batch);
    gapMapper.delete(
        Wrappers.<PricePrepareGap>lambdaQuery()
            .eq(PricePrepareGap::getPrepareNo, prepareNo));
    itemMapper.delete(
        Wrappers.<PricePrepareItem>lambdaQuery()
            .eq(PricePrepareItem::getPrepareNo, prepareNo));
    deletePackageCandidate(prepareNo);
    batchMapper.delete(
        Wrappers.<PricePrepareBatch>lambdaQuery()
            .eq(PricePrepareBatch::getPrepareNo, prepareNo));
  }

  /**
   * 删除未发布候选自己生成的中间结果。正式价格主数据不在这里，历史成本版本引用的批次也
   * 会在调用本方法前被保护；因此这里只按 prepare_no 的精确引用和批次取价时点清理。
   */
  private void cleanupGeneratedArtifacts(PricePrepareBatch batch) {
    if (batch == null || !StringUtils.hasText(batch.getPrepareNo())) {
      return;
    }
    List<PricePrepareItem> prepareItems = itemMapper.selectList(
        Wrappers.<PricePrepareItem>lambdaQuery()
            .eq(PricePrepareItem::getPrepareNo, batch.getPrepareNo()));
    List<PricePrepareItem> items =
        prepareItems == null ? List.of() : prepareItems;

    Set<Long> directLinkedIds = new LinkedHashSet<>();
    Set<String> makePartCodes = new LinkedHashSet<>();
    for (PricePrepareItem item : items) {
      if (item == null) {
        continue;
      }
      if ("LINKED_PRICE".equals(item.getResultRefType()) && item.getResultRefId() != null) {
        directLinkedIds.add(item.getResultRefId());
      }
      if ("MAKE_PART".equals(item.getItemType()) && StringUtils.hasText(item.getMaterialCode())) {
        makePartCodes.add(item.getMaterialCode().trim());
      }
    }
    if (!directLinkedIds.isEmpty()) {
      linkedCalcItemMapper.delete(
          Wrappers.<PriceLinkedCalcItem>lambdaQuery()
              .in(PriceLinkedCalcItem::getId, directLinkedIds));
    }
    if (makePartCodes.isEmpty() || batch.getPriceAsOfTime() == null) {
      return;
    }

    List<MakePartPriceCalcRow> makeRows = makePartRowMapper.selectList(
        Wrappers.<MakePartPriceCalcRow>lambdaQuery()
            .eq(MakePartPriceCalcRow::getOaNo, batch.getOaNo())
            .eq(MakePartPriceCalcRow::getBusinessUnitType, batch.getBusinessUnitType())
            .eq(MakePartPriceCalcRow::getPricingMonth, batch.getPeriodMonth())
            .eq(MakePartPriceCalcRow::getPriceAsOfTime, batch.getPriceAsOfTime())
            .eq(MakePartPriceCalcRow::getPriceScenarioType, scenarioType(batch))
            .in(MakePartPriceCalcRow::getParentMaterialNo, makePartCodes));
    List<MakePartPriceCalcRow> rows = makeRows == null ? List.of() : makeRows;
    Set<String> calcBatchIds = rows.stream()
        .map(MakePartPriceCalcRow::getCalcBatchId)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (!calcBatchIds.isEmpty()) {
      makePartGapMapper.delete(
          Wrappers.<MakePartPriceGapItem>lambdaQuery()
              .in(MakePartPriceGapItem::getCalcBatchId, calcBatchIds));
      makePartRowMapper.delete(
          Wrappers.<MakePartPriceCalcRow>lambdaQuery()
              .in(MakePartPriceCalcRow::getCalcBatchId, calcBatchIds));
    }
  }

  private String scenarioType(PricePrepareBatch batch) {
    return StringUtils.hasText(batch.getScenarioType())
        ? batch.getScenarioType().trim()
        : QuotePriceScenarioType.OA_LOCKED.name();
  }

  private void deletePackageCandidate(String prepareNo) {
    List<PackageComponentPrice> prices =
        packagePriceMapper.selectList(
            Wrappers.<PackageComponentPrice>lambdaQuery()
                .eq(PackageComponentPrice::getCalcBatchId, prepareNo));
    List<Long> priceIds = (prices == null ? List.<PackageComponentPrice>of() : prices).stream()
        .map(PackageComponentPrice::getId)
        .filter(java.util.Objects::nonNull)
        .toList();
    if (!priceIds.isEmpty()) {
      packageDetailMapper.delete(
          Wrappers.<PackageComponentPriceDetail>lambdaQuery()
              .in(PackageComponentPriceDetail::getPriceId, priceIds));
    }
    packagePriceMapper.delete(
        Wrappers.<PackageComponentPrice>lambdaQuery()
            .eq(PackageComponentPrice::getCalcBatchId, prepareNo));
  }

  private boolean isScopedOaBatch(PricePrepareBatch batch) {
    return batch != null
        && batch.getOaFormItemId() != null
        && StringUtils.hasText(batch.getOaNo())
        && StringUtils.hasText(batch.getTopProductCode())
        && StringUtils.hasText(batch.getPeriodMonth())
        && (!StringUtils.hasText(batch.getScenarioType())
            || QuotePriceScenarioType.OA_LOCKED.name().equals(batch.getScenarioType()));
  }

  private boolean isSuccessful(PricePrepareBatch batch) {
    return STATUS_SUCCESS.equalsIgnoreCase(batch.getStatus())
        && valueOrZero(batch.getGapCount()) == 0;
  }

  private void addText(Set<String> values, String value) {
    if (StringUtils.hasText(value)) {
      values.add(value.trim());
    }
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }
}
