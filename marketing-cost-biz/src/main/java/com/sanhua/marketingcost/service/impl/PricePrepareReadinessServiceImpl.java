package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
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
  private final PricePrepareQueryService queryService;

  @Value("${marketing-cost.price-prepare.block-on-not-ready:false}")
  private boolean blockOnNotReady;

  public PricePrepareReadinessServiceImpl(
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      PricePrepareQueryService queryService) {
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.queryService = queryService;
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
    int gapCount = 0;
    boolean hasFailed = false;
    List<String> notReadyTopSummaries = new ArrayList<>();
    for (PricePrepareTopProductSummaryResponse summary : topSummaries) {
      gapCount += summary.getGapCount();
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
      PricePrepareReadinessResult result = PricePrepareReadinessResult.ready(null, periodValue, STATUS_SUCCESS);
      result.setMessage("价格准备已完成");
      return result;
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

  void setBlockOnNotReady(boolean blockOnNotReady) {
    this.blockOnNotReady = blockOnNotReady;
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

  private List<String> loadGapSummaries(String oaNo, String periodMonth) {
    if (!StringUtils.hasText(oaNo)) {
      return List.of();
    }
    List<PricePrepareGap> gaps =
        gapMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareGap.class)
                .eq(PricePrepareGap::getOaNo, oaNo.trim())
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
}
