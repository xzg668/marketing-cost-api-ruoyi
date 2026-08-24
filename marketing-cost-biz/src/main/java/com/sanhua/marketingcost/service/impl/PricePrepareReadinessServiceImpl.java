package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PricePrepareReadinessServiceImpl implements PricePrepareReadinessService {

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_PARTIAL = "PARTIAL";
  private static final String STATUS_FAILED = "FAILED";
  private static final String SUMMARY_READY = "READY";
  private static final String SUMMARY_FAILED = "FAILED";

  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;
  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareQueryService queryService;
  private final QuoteCostingWorkspaceService workspaceService;

  @Value("${marketing-cost.price-prepare.block-on-not-ready:false}")
  private boolean blockOnNotReady;

  public PricePrepareReadinessServiceImpl(
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      PricePrepareBatchMapper batchMapper,
      PricePrepareQueryService queryService,
      QuoteCostingWorkspaceService workspaceService) {
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.batchMapper = batchMapper;
    this.queryService = queryService;
    this.workspaceService = workspaceService;
  }

  @Override
  public PricePrepareReadinessResult check(String oaNo, String periodMonth) {
    String oaNoValue = StringUtils.hasText(oaNo) ? oaNo.trim() : "";
    String periodValue = StringUtils.hasText(periodMonth) ? periodMonth.trim() : "";
    if (!StringUtils.hasText(oaNoValue)) {
      return warning("NOT_PREPARED", "缺少 OA 单号，无法检查价格准备状态", null, periodValue, null, 0, List.of());
    }

    List<PricePrepareTopProductSummaryResponse> topSummaries =
        loadTopSummaries(oaNoValue, periodValue);
    if (topSummaries.isEmpty()) {
      String message =
          StringUtils.hasText(periodValue)
              ? "当前期间 " + periodValue + " 尚未执行价格准备，实时成本将继续，结果可能缺价"
              : "当前 OA 尚未执行价格准备，实时成本将继续，结果可能缺价";
      return warning("NOT_PREPARED", message, null, periodValue, null, 0, List.of());
    }

    int topProductCount = topSummaries.size();
    int readyTopProductCount = 0;
    int warningCount = 0;
    int gapCount = 0;
    boolean hasFailed = false;
    List<String> notReadyTopSummaries = new ArrayList<>();
    for (PricePrepareTopProductSummaryResponse summary : topSummaries) {
      gapCount += summary.getGapCount();
      warningCount += summary.getWarningCount();
      if (SUMMARY_READY.equals(summary.getStatus())) {
        readyTopProductCount++;
      } else {
        hasFailed = hasFailed || SUMMARY_FAILED.equals(summary.getStatus());
        notReadyTopSummaries.add(
            firstText(summary.getTopProductCode(), "-", "-")
                + " 缺口 "
                + summary.getGapCount()
                + " 项，已准备 "
                + summary.getReadyCount()
                + "/"
                + summary.getTotalCount());
      }
    }
    if (readyTopProductCount == topProductCount) {
      return readyResult(null, periodValue, warningCount);
    }

    List<String> gapSummaries = loadGapSummaries(oaNoValue, periodValue);
    if (gapSummaries.isEmpty()) {
      gapSummaries = notReadyTopSummaries.stream().limit(5).toList();
    }
    int notReadyTopProductCount = topProductCount - readyTopProductCount;
    return warning(
        hasFailed ? STATUS_FAILED : STATUS_PARTIAL,
        "OA "
            + oaNoValue
            + " 价格准备未完成：共 "
            + topProductCount
            + " 个顶级产品，"
            + readyTopProductCount
            + " 个已完成，"
            + notReadyTopProductCount
            + " 个未完成；实时成本将继续，结果可能缺价"
            + suffix(gapSummaries),
        null,
        periodValue,
        hasFailed ? STATUS_FAILED : STATUS_PARTIAL,
        gapCount,
        gapSummaries);
  }

  @Override
  public PricePrepareReadinessResult check(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    String oaNoValue = StringUtils.hasText(oaNo) ? oaNo.trim() : "";
    String topProductCodeValue = StringUtils.hasText(topProductCode) ? topProductCode.trim() : "";
    String periodValue = StringUtils.hasText(periodMonth) ? periodMonth.trim() : "";
    if (!StringUtils.hasText(oaNoValue)) {
      return warning("NOT_PREPARED", "缺少 OA 单号，无法检查价格准备状态", null, periodValue, null, 0, List.of());
    }
    if (oaFormItemId == null || !StringUtils.hasText(topProductCodeValue)) {
      return check(oaNoValue, periodValue);
    }

    java.util.Optional<QuoteCostingWorkspace> workspace =
        workspaceService.find(oaFormItemId, periodValue);
    if (workspace.isPresent()) {
      return checkCurrentWorkspace(
          workspace.get(), oaNoValue, oaFormItemId, topProductCodeValue, periodValue);
    }

    String completedPrepareNo =
        latestCompletedPrepareNo(oaNoValue, oaFormItemId, topProductCodeValue, periodValue);
    List<PricePrepareItem> items =
        itemMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareItem.class)
                .eq(PricePrepareItem::getOaNo, oaNoValue)
                .eq(PricePrepareItem::getOaFormItemId, oaFormItemId)
                .eq(PricePrepareItem::getTopProductCode, topProductCodeValue)
                .eq(
                    StringUtils.hasText(completedPrepareNo),
                    PricePrepareItem::getPrepareNo,
                    completedPrepareNo)
                .eq(
                    !StringUtils.hasText(completedPrepareNo),
                    PricePrepareItem::getCurrentFlag,
                    1)
                .eq(StringUtils.hasText(periodValue), PricePrepareItem::getPeriodMonth, periodValue)
                .orderByDesc(PricePrepareItem::getId));
    List<PricePrepareGap> gaps =
        StringUtils.hasText(completedPrepareNo)
            ? List.of()
            : loadScopedGaps(
                oaNoValue,
                oaFormItemId,
                topProductCodeValue,
                periodValue);
    if (items == null || items.isEmpty()) {
      String message =
          StringUtils.hasText(periodValue)
              ? "当前产品行 " + topProductCodeValue + " 在期间 " + periodValue + " 尚未执行价格准备，实时成本将继续，结果可能缺价"
              : "当前产品行 " + topProductCodeValue + " 尚未执行价格准备，实时成本将继续，结果可能缺价";
      return warning("NOT_PREPARED", message, null, periodValue, null, gaps.size(), scopedGapSummaries(gaps));
    }

    int readyCount = 0;
    int warningCount = 0;
    boolean hasFailed = false;
    String prepareNo = null;
    String batchStatus = STATUS_SUCCESS;
    for (PricePrepareItem item : items) {
      if (item == null) {
        continue;
      }
      prepareNo = firstText(prepareNo, item.getPrepareNo(), prepareNo);
      if (SUMMARY_READY.equals(item.getStatus())) {
        readyCount++;
      } else {
        hasFailed = hasFailed || SUMMARY_FAILED.equals(item.getStatus());
      }
      if (Integer.valueOf(1).equals(item.getCarriedForward())) {
        warningCount++;
      }
    }
    if (readyCount == items.size() && gaps.isEmpty()) {
      return readyResult(prepareNo, periodValue, warningCount);
    }
    batchStatus = hasFailed ? STATUS_FAILED : STATUS_PARTIAL;
    List<String> gapSummaries = scopedGapSummaries(gaps);
    return warning(
        batchStatus,
        "产品行 "
            + topProductCodeValue
            + " 价格准备未完成：已准备 "
            + readyCount
            + "/"
            + items.size()
            + "，缺口 "
            + gaps.size()
            + " 项；实时成本将继续，结果可能缺价"
            + suffix(gapSummaries),
        prepareNo,
        periodValue,
        batchStatus,
        gaps.size(),
        gapSummaries);
  }

  void setBlockOnNotReady(boolean blockOnNotReady) {
    this.blockOnNotReady = blockOnNotReady;
  }

  private PricePrepareReadinessResult checkCurrentWorkspace(
      QuoteCostingWorkspace workspace,
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth) {
    List<PricePrepareGap> currentGaps =
        loadScopedGaps(oaNo, oaFormItemId, topProductCode, periodMonth);
    int workspaceGapCount = workspace.getGapCount() == null ? 0 : workspace.getGapCount();
    if (workspaceGapCount > 0
        || "PRICE_BLOCKED".equals(workspace.getWorkspaceStatus())
        || "PRICE_ERROR".equals(workspace.getWorkspaceStatus())) {
      List<String> summaries = scopedGapSummaries(currentGaps);
      return blocked(
          "PRICE_ERROR".equals(workspace.getWorkspaceStatus()) ? STATUS_FAILED : STATUS_PARTIAL,
          "产品行 "
              + topProductCode
              + " 最终价格未生成：当前有 "
              + Math.max(workspaceGapCount, currentGaps.size())
              + " 项缺口"
              + suffix(summaries),
          workspace.getCurrentPrepareNo(),
          periodMonth,
          Math.max(workspaceGapCount, currentGaps.size()),
          summaries);
    }

    String prepareNo = trimToNull(workspace.getCurrentPrepareNo());
    if (prepareNo == null) {
      return blocked(
          "NOT_PREPARED",
          "产品行 " + topProductCode + " 尚未生成最终价格",
          null,
          periodMonth,
          0,
          List.of());
    }
    PricePrepareBatch oaBatch =
        batchMapper.selectOne(
            Wrappers.<PricePrepareBatch>lambdaQuery()
                .eq(PricePrepareBatch::getPrepareNo, prepareNo)
                .eq(PricePrepareBatch::getOaNo, oaNo)
                .eq(PricePrepareBatch::getOaFormItemId, oaFormItemId)
                .eq(PricePrepareBatch::getTopProductCode, topProductCode)
                .eq(PricePrepareBatch::getPeriodMonth, periodMonth)
                .last("LIMIT 1"));
    if (!isSuccessfulOaBatch(oaBatch)) {
      return blocked(
          STATUS_FAILED,
          "当前最终价格指针对应的批次不存在或未成功，请重新生成最终价格",
          prepareNo,
          periodMonth,
          0,
          List.of());
    }
    List<PricePrepareItem> items =
        itemMapper.selectList(
            Wrappers.<PricePrepareItem>lambdaQuery()
                .eq(PricePrepareItem::getPrepareNo, prepareNo)
                .eq(PricePrepareItem::getCurrentFlag, 1)
                .orderByAsc(PricePrepareItem::getId));
    int readyCount = (int) (items == null ? List.<PricePrepareItem>of() : items).stream()
        .filter(item -> item != null && SUMMARY_READY.equals(item.getStatus()))
        .count();
    if (items == null || items.isEmpty() || readyCount != items.size()) {
      return blocked(
          STATUS_FAILED,
          "当前最终价格明细不完整，请重新生成最终价格",
          prepareNo,
          periodMonth,
          0,
          List.of());
    }
    List<PricePrepareBatch> financeBatches =
        batchMapper.selectList(
            Wrappers.<PricePrepareBatch>lambdaQuery()
                .eq(PricePrepareBatch::getSourcePrepareNo, prepareNo)
                .eq(
                    PricePrepareBatch::getScenarioType,
                    QuotePriceScenarioType.FINANCE_QUOTE_BASE.name())
                .orderByDesc(PricePrepareBatch::getId));
    boolean financeReady = financeBatches != null
        && financeBatches.stream().anyMatch(this::isSuccessfulBatch);
    if (!financeReady) {
      return blocked(
          STATUS_PARTIAL,
          "最终价格已生成，但财务基准对比价格未完成，请重新生成最终价格",
          prepareNo,
          periodMonth,
          0,
          List.of());
    }
    int warningCount = workspace.getCarriedForwardPriceCount() == null
        ? 0
        : workspace.getCarriedForwardPriceCount();
    return readyResult(prepareNo, periodMonth, warningCount);
  }

  private PricePrepareReadinessResult readyResult(
      String prepareNo, String periodMonth, int warningCount) {
    if (warningCount <= 0) {
      PricePrepareReadinessResult result =
          PricePrepareReadinessResult.ready(prepareNo, periodMonth, STATUS_SUCCESS);
      result.setMessage("价格准备已完成");
      return result;
    }
    return PricePrepareReadinessResult.readyWithWarnings(
        prepareNo,
        periodMonth,
        STATUS_SUCCESS,
        warningCount,
        "价格准备已完成，其中 " + warningCount + " 项沿用历史价");
  }

  private String latestCompletedPrepareNo(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth) {
    PricePrepareBatchQueryRequest query = new PricePrepareBatchQueryRequest();
    query.setOaNo(oaNo);
    query.setOaFormItemId(oaFormItemId);
    query.setTopProductCode(topProductCode);
    query.setPeriodMonth(periodMonth);
    query.setPage(1);
    query.setPageSize(500);
    PricePrepareBatchPageResponse page = queryService.pageBatches(query);
    List<PricePrepareBatch> batches =
        page == null || page.getRecords() == null ? List.of() : page.getRecords();
    for (PricePrepareBatch oaBatch : batches) {
      if (!isSuccessfulOaBatch(oaBatch)) {
        continue;
      }
      boolean financeReady =
          batches.stream().anyMatch(batch -> isSuccessfulFinanceBatchFor(batch, oaBatch));
      if (financeReady) {
        return oaBatch.getPrepareNo();
      }
    }
    return null;
  }

  private boolean isSuccessfulOaBatch(PricePrepareBatch batch) {
    return isSuccessfulBatch(batch)
        && (!StringUtils.hasText(batch.getScenarioType())
            || QuotePriceScenarioType.OA_LOCKED.name().equals(batch.getScenarioType()))
        && StringUtils.hasText(batch.getPrepareNo());
  }

  private boolean isSuccessfulFinanceBatchFor(
      PricePrepareBatch batch, PricePrepareBatch oaBatch) {
    return isSuccessfulBatch(batch)
        && QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(batch.getScenarioType())
        && oaBatch.getPrepareNo().equals(batch.getSourcePrepareNo())
        && (!StringUtils.hasText(oaBatch.getScenarioGroupNo())
            || oaBatch.getScenarioGroupNo().equals(batch.getScenarioGroupNo()));
  }

  private boolean isSuccessfulBatch(PricePrepareBatch batch) {
    return batch != null
        && STATUS_SUCCESS.equals(batch.getStatus())
        && (batch.getGapCount() == null || batch.getGapCount() == 0);
  }

  private PricePrepareReadinessResult warning(
      String status,
      String message,
      String prepareNo,
      String periodMonth,
      String batchStatus,
      int gapCount,
      List<String> gapSummaries) {
    boolean allowContinue = !blockOnNotReady;
    String finalMessage = blockOnNotReady ? message.replace("实时成本将继续，", "已阻断实时成本，") : message;
    return PricePrepareReadinessResult.notReady(
        status,
        allowContinue,
        blockOnNotReady,
        finalMessage,
        prepareNo,
        periodMonth,
        batchStatus,
        gapCount,
        gapSummaries);
  }

  private PricePrepareReadinessResult blocked(
      String status,
      String message,
      String prepareNo,
      String periodMonth,
      int gapCount,
      List<String> gapSummaries) {
    return PricePrepareReadinessResult.notReady(
        status,
        false,
        true,
        message,
        prepareNo,
        periodMonth,
        status,
        gapCount,
        gapSummaries);
  }

  private List<String> loadGapSummaries(String oaNo, String periodMonth) {
    if (!StringUtils.hasText(oaNo)) {
      return List.of();
    }
    List<PricePrepareGap> gaps =
        gapMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareGap.class)
                .eq(PricePrepareGap::getOaNo, oaNo.trim())
                .eq(PricePrepareGap::getCurrentFlag, 1)
                .eq(StringUtils.hasText(periodMonth), PricePrepareGap::getPeriodMonth,
                    periodMonth == null ? null : periodMonth.trim())
                .orderByDesc(PricePrepareGap::getCreatedAt)
                .orderByDesc(PricePrepareGap::getId)
                .last("LIMIT 5"));
    if (gaps == null || gaps.isEmpty()) {
      return List.of();
    }
    List<String> summaries = new ArrayList<>();
    for (PricePrepareGap gap : gaps) {
      if (gap == null) {
        continue;
      }
      String code = firstText(gap.getTopProductCode(), gap.getGapMaterialCode(), gap.getMaterialCode());
      String message = StringUtils.hasText(gap.getMessage()) ? gap.getMessage().trim() : "未说明";
      summaries.add(code + ": " + message);
    }
    return summaries;
  }

  private List<PricePrepareGap> loadScopedGaps(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    List<PricePrepareGap> gaps =
        gapMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareGap.class)
                .eq(PricePrepareGap::getOaNo, oaNo)
                .eq(PricePrepareGap::getOaFormItemId, oaFormItemId)
                .eq(PricePrepareGap::getTopProductCode, topProductCode)
                .eq(PricePrepareGap::getCurrentFlag, 1)
                .eq(StringUtils.hasText(periodMonth), PricePrepareGap::getPeriodMonth, periodMonth)
                .orderByDesc(PricePrepareGap::getCreatedAt)
                .orderByDesc(PricePrepareGap::getId));
    return gaps == null ? List.of() : gaps;
  }

  private List<String> scopedGapSummaries(List<PricePrepareGap> gaps) {
    if (gaps == null || gaps.isEmpty()) {
      return List.of();
    }
    List<String> summaries = new ArrayList<>();
    for (PricePrepareGap gap : gaps.stream().limit(5).toList()) {
      if (gap == null) {
        continue;
      }
      String code = firstText(gap.getGapMaterialCode(), gap.getMaterialCode(), gap.getTopProductCode());
      String message = StringUtils.hasText(gap.getMessage()) ? gap.getMessage().trim() : "未说明";
      summaries.add(code + ": " + message);
    }
    return summaries;
  }

  private List<PricePrepareTopProductSummaryResponse> loadTopSummaries(String oaNo, String periodMonth) {
    PricePrepareTopProductSummaryQueryRequest request = new PricePrepareTopProductSummaryQueryRequest();
    request.setOaNo(oaNo);
    request.setPeriodMonth(periodMonth);
    request.setPage(1);
    request.setPageSize(500);
    PricePrepareTopProductSummaryPageResponse page = queryService.pageTopProductSummaries(request);
    if (page == null || page.getRecords() == null) {
      return List.of();
    }
    return page.getRecords();
  }

  private String suffix(List<String> gapSummaries) {
    if (gapSummaries == null || gapSummaries.isEmpty()) {
      return "";
    }
    return "；缺口摘要：" + String.join("；", gapSummaries);
  }

  private String firstText(String first, String second, String fallback) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    if (StringUtils.hasText(second)) {
      return second.trim();
    }
    return fallback;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
