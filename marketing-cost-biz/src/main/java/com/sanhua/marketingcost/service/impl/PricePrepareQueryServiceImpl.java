package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PricePrepareQueryServiceImpl implements PricePrepareQueryService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 500;
  private static final String ITEM_STATUS_READY = "READY";
  private static final String ITEM_STATUS_FAILED = "FAILED";
  private static final String SUMMARY_READY = "READY";
  private static final String SUMMARY_PARTIAL = "PARTIAL";
  private static final String SUMMARY_FAILED = "FAILED";
  private static final String SUMMARY_NOT_PREPARED = "NOT_PREPARED";
  private static final String OWNER_SCOPE_ALL = "ALL";
  private static final Set<String> PENDING_SUMMARY_STATUSES =
      Set.of(SUMMARY_NOT_PREPARED, SUMMARY_PARTIAL, SUMMARY_FAILED);

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;

  public PricePrepareQueryServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
  }

  @Override
  public PricePrepareBatchPageResponse pageBatches(PricePrepareBatchQueryRequest request) {
    PricePrepareBatchQueryRequest safe =
        request == null ? new PricePrepareBatchQueryRequest() : request;
    Page<PricePrepareBatch> page =
        batchMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildBatchQuery(safe)
                .orderByDesc(PricePrepareBatch::getStartedAt)
                .orderByDesc(PricePrepareBatch::getId));
    return new PricePrepareBatchPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public PricePrepareOaSummaryPageResponse pageOaSummaries(
      PricePrepareOaSummaryQueryRequest request) {
    PricePrepareOaSummaryQueryRequest safe =
        request == null ? new PricePrepareOaSummaryQueryRequest() : request;
    List<PricePrepareTopProductSummaryResponse> topSummaries =
        loadTopProductSummaries(safe.getOaNo(), null);
    Map<String, PricePrepareOaSummaryResponse> byOa = new LinkedHashMap<>();
    for (PricePrepareTopProductSummaryResponse topSummary : topSummaries) {
      PricePrepareOaSummaryResponse oaSummary =
          byOa.computeIfAbsent(topSummary.getOaNo(), this::emptyOaSummary);
      oaSummary.setTopProductCount(oaSummary.getTopProductCount() + 1);
      oaSummary.setTotalCount(oaSummary.getTotalCount() + topSummary.getTotalCount());
      oaSummary.setReadyCount(oaSummary.getReadyCount() + topSummary.getReadyCount());
      oaSummary.setGapCount(oaSummary.getGapCount() + topSummary.getGapCount());
      if (SUMMARY_READY.equals(topSummary.getStatus())) {
        oaSummary.setReadyTopProductCount(oaSummary.getReadyTopProductCount() + 1);
      }
      oaSummary.setUpdatedAt(maxTime(oaSummary.getUpdatedAt(), topSummary.getUpdatedAt()));
    }
    List<PricePrepareOaSummaryResponse> summaries = new ArrayList<>(byOa.values());
    for (PricePrepareOaSummaryResponse summary : summaries) {
      summary.setStatus(oaStatus(summary));
    }
    if (summaries.isEmpty() && StringUtils.hasText(safe.getOaNo())) {
      PricePrepareOaSummaryResponse notPrepared = emptyOaSummary(safe.getOaNo().trim());
      notPrepared.setStatus(SUMMARY_NOT_PREPARED);
      summaries.add(notPrepared);
    }
    summaries = filterStatus(summaries, safe.getStatus(), PricePrepareOaSummaryResponse::getStatus);
    summaries.sort(
        Comparator.comparing(PricePrepareOaSummaryResponse::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PricePrepareOaSummaryResponse::getOaNo,
                Comparator.nullsLast(String::compareTo)));
    return new PricePrepareOaSummaryPageResponse(summaries.size(), pageList(summaries, safe.getPage(), safe.getPageSize()));
  }

  @Override
  public PricePrepareTopProductSummaryPageResponse pageTopProductSummaries(
      PricePrepareTopProductSummaryQueryRequest request) {
    PricePrepareTopProductSummaryQueryRequest safe =
        request == null ? new PricePrepareTopProductSummaryQueryRequest() : request;
    List<PricePrepareTopProductSummaryResponse> summaries =
        loadTopProductSummaries(safe.getOaNo(), safe.getTopProductCode());
    if (summaries.isEmpty()
        && StringUtils.hasText(safe.getOaNo())
        && StringUtils.hasText(safe.getTopProductCode())) {
      PricePrepareTopProductSummaryResponse notPrepared = new PricePrepareTopProductSummaryResponse();
      notPrepared.setOaNo(safe.getOaNo().trim());
      notPrepared.setTopProductCode(safe.getTopProductCode().trim());
      notPrepared.setStatus(SUMMARY_NOT_PREPARED);
      summaries.add(notPrepared);
    }
    summaries = filterStatus(summaries, safe.getStatus(), PricePrepareTopProductSummaryResponse::getStatus);
    summaries.sort(
        Comparator.comparing(PricePrepareTopProductSummaryResponse::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PricePrepareTopProductSummaryResponse::getOaNo,
                Comparator.nullsLast(String::compareTo))
            .thenComparing(PricePrepareTopProductSummaryResponse::getTopProductCode,
                Comparator.nullsLast(String::compareTo)));
    return new PricePrepareTopProductSummaryPageResponse(
        summaries.size(), pageList(summaries, safe.getPage(), safe.getPageSize()));
  }

  @Override
  public PricePrepareCandidatePageResponse pageCandidates(PricePrepareCandidateQueryRequest request) {
    PricePrepareCandidateQueryRequest safe =
        request == null ? new PricePrepareCandidateQueryRequest() : request;
    String ownerScope = StringUtils.hasText(safe.getOwnerScope())
        ? safe.getOwnerScope().trim()
        : "MINE";
    String keyword = trimToNull(safe.getKeyword());
    if (!OWNER_SCOPE_ALL.equalsIgnoreCase(ownerScope) && keyword == null) {
      return new PricePrepareCandidatePageResponse(0, List.of());
    }

    List<OaForm> forms = loadCandidateForms(safe, keyword);
    if (forms.isEmpty()) {
      return new PricePrepareCandidatePageResponse(0, List.of());
    }
    Map<Long, OaForm> formById = new LinkedHashMap<>();
    for (OaForm form : forms) {
      if (form != null && form.getId() != null) {
        formById.put(form.getId(), form);
      }
    }
    if (formById.isEmpty()) {
      return new PricePrepareCandidatePageResponse(0, List.of());
    }

    List<OaFormItem> items =
        oaFormItemMapper.selectList(
            Wrappers.<OaFormItem>lambdaQuery()
                .in(OaFormItem::getOaFormId, formById.keySet())
                .orderByAsc(OaFormItem::getOaFormId)
                .orderByAsc(OaFormItem::getSeq)
                .orderByAsc(OaFormItem::getId));
    if (items == null || items.isEmpty()) {
      return new PricePrepareCandidatePageResponse(0, List.of());
    }

    List<CandidateSeed> seeds = new ArrayList<>();
    Set<String> oaNos = new LinkedHashSet<>();
    Set<String> topProductCodes = new LinkedHashSet<>();
    Set<String> seen = new LinkedHashSet<>();
    for (OaFormItem item : items) {
      if (item == null || !StringUtils.hasText(item.getMaterialNo())) {
        continue;
      }
      OaForm form = formById.get(item.getOaFormId());
      if (form == null || !matchesKeyword(form, item, keyword)) {
        continue;
      }
      String oaNo = trimToNull(form.getOaNo());
      String topProductCode = trimToNull(item.getMaterialNo());
      if (oaNo == null || topProductCode == null) {
        continue;
      }
      String key = summaryKey(oaNo, topProductCode);
      if (!seen.add(key)) {
        continue;
      }
      seeds.add(new CandidateSeed(form, item, oaNo, topProductCode));
      oaNos.add(oaNo);
      topProductCodes.add(topProductCode);
    }
    if (seeds.isEmpty()) {
      return new PricePrepareCandidatePageResponse(0, List.of());
    }

    Map<String, PricePrepareTopProductSummaryResponse> summaries =
        loadTopProductSummaryMap(oaNos, topProductCodes);
    List<PricePrepareCandidateResponse> candidates = new ArrayList<>();
    for (CandidateSeed seed : seeds) {
      PricePrepareCandidateResponse candidate = toCandidate(seed, summaries.get(seed.key()));
      if (matchesPrepareStatus(candidate, safe.getPrepareStatus())
          && matchesPending(candidate, safe.getOnlyPending())) {
        candidates.add(candidate);
      }
    }
    candidates.sort(
        Comparator.comparing(PricePrepareCandidateResponse::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PricePrepareCandidateResponse::getApplyDate,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PricePrepareCandidateResponse::getOaNo,
                Comparator.nullsLast(String::compareTo))
            .thenComparing(PricePrepareCandidateResponse::getTopProductCode,
                Comparator.nullsLast(String::compareTo)));
    return new PricePrepareCandidatePageResponse(
        candidates.size(), pageList(candidates, safe.getPage(), safe.getPageSize()));
  }

  @Override
  public PricePrepareItemPageResponse pageItems(PricePrepareItemQueryRequest request) {
    PricePrepareItemQueryRequest safe =
        request == null ? new PricePrepareItemQueryRequest() : request;
    Page<PricePrepareItem> page =
        itemMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildItemQuery(safe)
                .orderByAsc(PricePrepareItem::getBomRowId)
                .orderByAsc(PricePrepareItem::getId));
    return new PricePrepareItemPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public PricePrepareGapPageResponse pageGaps(PricePrepareGapQueryRequest request) {
    PricePrepareGapQueryRequest safe =
        request == null ? new PricePrepareGapQueryRequest() : request;
    Page<PricePrepareGap> page =
        gapMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildGapQuery(safe)
                .orderByDesc(PricePrepareGap::getCreatedAt)
                .orderByDesc(PricePrepareGap::getId));
    return new PricePrepareGapPageResponse(page.getTotal(), page.getRecords());
  }

  List<PricePrepareTopProductSummaryResponse> loadTopProductSummaries(
      String oaNo, String topProductCode) {
    QueryWrapper<PricePrepareItem> itemQuery =
        Wrappers.<PricePrepareItem>query()
            .select(
                "oa_no",
                "top_product_code",
                "COUNT(*) AS total_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_READY + "' THEN 1 ELSE 0 END) AS ready_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_FAILED + "' THEN 1 ELSE 0 END) AS failed_count",
                "MAX(updated_at) AS updated_at");
    eqIfText(itemQuery, "oa_no", oaNo);
    eqIfText(itemQuery, "top_product_code", topProductCode);
    itemQuery.groupBy("oa_no", "top_product_code");

    Map<String, PricePrepareTopProductSummaryResponse> summaries = new LinkedHashMap<>();
    List<Map<String, Object>> itemMaps = itemMapper.selectMaps(itemQuery);
    if (itemMaps != null) {
      for (Map<String, Object> row : itemMaps) {
        String rowOaNo = text(row, "oa_no");
        String rowTopProductCode = text(row, "top_product_code");
        PricePrepareTopProductSummaryResponse summary = new PricePrepareTopProductSummaryResponse();
        summary.setOaNo(rowOaNo);
        summary.setTopProductCode(rowTopProductCode);
        summary.setTotalCount(number(row, "total_count"));
        summary.setReadyCount(number(row, "ready_count"));
        summary.setUpdatedAt(time(row, "updated_at"));
        int failedCount = number(row, "failed_count");
        summary.setStatus(topStatus(summary, failedCount));
        summaries.put(summaryKey(rowOaNo, rowTopProductCode), summary);
      }
    }

    Map<String, Integer> gapCounts = loadGapCounts(oaNo, topProductCode);
    for (Map.Entry<String, Integer> entry : gapCounts.entrySet()) {
      PricePrepareTopProductSummaryResponse summary = summaries.get(entry.getKey());
      if (summary != null) {
        summary.setGapCount(entry.getValue());
        if (!SUMMARY_FAILED.equals(summary.getStatus())) {
          summary.setStatus(topStatus(summary, 0));
        }
      }
    }
    return new ArrayList<>(summaries.values());
  }

  private Map<String, Integer> loadGapCounts(String oaNo, String topProductCode) {
    QueryWrapper<PricePrepareGap> gapQuery =
        Wrappers.<PricePrepareGap>query()
            .select("oa_no", "top_product_code", "COUNT(*) AS gap_count");
    eqIfText(gapQuery, "oa_no", oaNo);
    eqIfText(gapQuery, "top_product_code", topProductCode);
    gapQuery.groupBy("oa_no", "top_product_code");
    Map<String, Integer> gapCounts = new HashMap<>();
    List<Map<String, Object>> gapMaps = gapMapper.selectMaps(gapQuery);
    if (gapMaps == null) {
      return gapCounts;
    }
    for (Map<String, Object> row : gapMaps) {
      gapCounts.put(summaryKey(text(row, "oa_no"), text(row, "top_product_code")), number(row, "gap_count"));
    }
    return gapCounts;
  }

  private List<OaForm> loadCandidateForms(PricePrepareCandidateQueryRequest request, String keyword) {
    LambdaQueryWrapper<OaForm> query = Wrappers.lambdaQuery();
    if (StringUtils.hasText(request.getCalcStatus())) {
      query.eq(OaForm::getCalcStatus, request.getCalcStatus().trim());
    }
    if (keyword != null) {
      query.and(q -> q.like(OaForm::getOaNo, keyword).or().like(OaForm::getCustomer, keyword));
    }
    query.orderByDesc(OaForm::getApplyDate).orderByDesc(OaForm::getId);
    List<OaForm> forms = oaFormMapper.selectList(query);
    return forms == null ? List.of() : forms;
  }

  private Map<String, PricePrepareTopProductSummaryResponse> loadTopProductSummaryMap(
      Set<String> oaNos, Set<String> topProductCodes) {
    if (oaNos == null || oaNos.isEmpty() || topProductCodes == null || topProductCodes.isEmpty()) {
      return Map.of();
    }
    QueryWrapper<PricePrepareItem> itemQuery =
        Wrappers.<PricePrepareItem>query()
            .select(
                "oa_no",
                "top_product_code",
                "COUNT(*) AS total_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_READY + "' THEN 1 ELSE 0 END) AS ready_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_FAILED + "' THEN 1 ELSE 0 END) AS failed_count",
                "MAX(updated_at) AS updated_at")
            .in("oa_no", oaNos)
            .in("top_product_code", topProductCodes)
            .groupBy("oa_no", "top_product_code");

    Map<String, PricePrepareTopProductSummaryResponse> summaries = new LinkedHashMap<>();
    List<Map<String, Object>> itemMaps = itemMapper.selectMaps(itemQuery);
    if (itemMaps != null) {
      for (Map<String, Object> row : itemMaps) {
        String rowOaNo = text(row, "oa_no");
        String rowTopProductCode = text(row, "top_product_code");
        PricePrepareTopProductSummaryResponse summary = new PricePrepareTopProductSummaryResponse();
        summary.setOaNo(rowOaNo);
        summary.setTopProductCode(rowTopProductCode);
        summary.setTotalCount(number(row, "total_count"));
        summary.setReadyCount(number(row, "ready_count"));
        summary.setUpdatedAt(time(row, "updated_at"));
        summary.setStatus(topStatus(summary, number(row, "failed_count")));
        summaries.put(summaryKey(rowOaNo, rowTopProductCode), summary);
      }
    }

    Map<String, Integer> gapCounts = loadGapCounts(oaNos, topProductCodes);
    for (Map.Entry<String, Integer> entry : gapCounts.entrySet()) {
      PricePrepareTopProductSummaryResponse summary = summaries.get(entry.getKey());
      if (summary == null) {
        continue;
      }
      summary.setGapCount(entry.getValue());
      if (!SUMMARY_FAILED.equals(summary.getStatus())) {
        summary.setStatus(topStatus(summary, 0));
      }
    }
    return summaries;
  }

  private Map<String, Integer> loadGapCounts(Set<String> oaNos, Set<String> topProductCodes) {
    QueryWrapper<PricePrepareGap> gapQuery =
        Wrappers.<PricePrepareGap>query()
            .select("oa_no", "top_product_code", "COUNT(*) AS gap_count")
            .in("oa_no", oaNos)
            .in("top_product_code", topProductCodes)
            .groupBy("oa_no", "top_product_code");
    Map<String, Integer> gapCounts = new HashMap<>();
    List<Map<String, Object>> gapMaps = gapMapper.selectMaps(gapQuery);
    if (gapMaps == null) {
      return gapCounts;
    }
    for (Map<String, Object> row : gapMaps) {
      gapCounts.put(summaryKey(text(row, "oa_no"), text(row, "top_product_code")), number(row, "gap_count"));
    }
    return gapCounts;
  }

  private boolean matchesKeyword(OaForm form, OaFormItem item, String keyword) {
    if (keyword == null) {
      return true;
    }
    return contains(form.getOaNo(), keyword)
        || contains(form.getCustomer(), keyword)
        || contains(item.getMaterialNo(), keyword)
        || contains(item.getProductName(), keyword)
        || contains(item.getSunlModel(), keyword)
        || contains(item.getSpec(), keyword);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.contains(keyword);
  }

  private PricePrepareCandidateResponse toCandidate(
      CandidateSeed seed, PricePrepareTopProductSummaryResponse summary) {
    PricePrepareCandidateResponse candidate = new PricePrepareCandidateResponse();
    candidate.setOaNo(seed.oaNo());
    candidate.setTopProductCode(seed.topProductCode());
    candidate.setProductName(seed.item().getProductName());
    candidate.setProductModel(firstText(seed.item().getSunlModel(), seed.item().getSpec()));
    candidate.setCustomer(seed.form().getCustomer());
    candidate.setApplyDate(seed.form().getApplyDate());
    candidate.setCalcStatus(seed.form().getCalcStatus());
    candidate.setOwnerName(firstText(seed.form().getSaleLink(), seed.form().getApplicantName()));
    if (summary == null) {
      candidate.setPrepareStatus(SUMMARY_NOT_PREPARED);
      candidate.setUpdatedAt(maxTime(seed.form().getUpdatedAt(), seed.item().getUpdatedAt()));
      return candidate;
    }
    candidate.setPrepareStatus(summary.getStatus());
    candidate.setTotalCount(summary.getTotalCount());
    candidate.setReadyCount(summary.getReadyCount());
    candidate.setGapCount(summary.getGapCount());
    candidate.setUpdatedAt(summary.getUpdatedAt());
    return candidate;
  }

  private boolean matchesPrepareStatus(PricePrepareCandidateResponse candidate, String prepareStatus) {
    return !StringUtils.hasText(prepareStatus)
        || prepareStatus.trim().equals(candidate.getPrepareStatus());
  }

  private boolean matchesPending(PricePrepareCandidateResponse candidate, Boolean onlyPending) {
    if (Boolean.FALSE.equals(onlyPending)) {
      return true;
    }
    return PENDING_SUMMARY_STATUSES.contains(candidate.getPrepareStatus());
  }

  private String firstText(String left, String right) {
    if (StringUtils.hasText(left)) {
      return left.trim();
    }
    return StringUtils.hasText(right) ? right.trim() : null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record CandidateSeed(OaForm form, OaFormItem item, String oaNo, String topProductCode) {
    private String key() {
      return String.valueOf(oaNo) + "|" + String.valueOf(topProductCode);
    }
  }

  private LambdaQueryWrapper<PricePrepareBatch> buildBatchQuery(
      PricePrepareBatchQueryRequest request) {
    LambdaQueryWrapper<PricePrepareBatch> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareBatch::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareBatch::getOaNo, request.getOaNo());
    eqIfText(query, PricePrepareBatch::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PricePrepareBatch::getStatus, request.getStatus());
    return query;
  }

  private LambdaQueryWrapper<PricePrepareItem> buildItemQuery(
      PricePrepareItemQueryRequest request) {
    LambdaQueryWrapper<PricePrepareItem> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareItem::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareItem::getOaNo, request.getOaNo());
    eqIfText(query, PricePrepareItem::getTopProductCode, request.getTopProductCode());
    eqIfText(query, PricePrepareItem::getMaterialCode, request.getMaterialCode());
    eqIfText(query, PricePrepareItem::getItemType, request.getItemType());
    eqIfText(query, PricePrepareItem::getStatus, request.getStatus());
    return query;
  }

  private LambdaQueryWrapper<PricePrepareGap> buildGapQuery(PricePrepareGapQueryRequest request) {
    LambdaQueryWrapper<PricePrepareGap> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareGap::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareGap::getOaNo, request.getOaNo());
    eqIfText(query, PricePrepareGap::getTopProductCode, request.getTopProductCode());
    eqIfText(query, PricePrepareGap::getMaterialCode, request.getMaterialCode());
    eqIfText(query, PricePrepareGap::getGapMaterialCode, request.getGapMaterialCode());
    eqIfText(query, PricePrepareGap::getGapType, request.getGapType());
    eqIfText(query, PricePrepareGap::getItemType, request.getItemType());
    eqIfText(query, PricePrepareGap::getOaPushStatus, request.getOaPushStatus());
    return query;
  }

  private <T> void eqIfText(
      LambdaQueryWrapper<T> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private <T> void eqIfText(QueryWrapper<T> query, String column, String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private PricePrepareOaSummaryResponse emptyOaSummary(String oaNo) {
    PricePrepareOaSummaryResponse summary = new PricePrepareOaSummaryResponse();
    summary.setOaNo(oaNo);
    return summary;
  }

  private String topStatus(PricePrepareTopProductSummaryResponse summary, int failedCount) {
    if (summary == null || summary.getTotalCount() == 0) {
      return SUMMARY_NOT_PREPARED;
    }
    if (failedCount > 0) {
      return SUMMARY_FAILED;
    }
    if (summary.getGapCount() == 0 && summary.getReadyCount() == summary.getTotalCount()) {
      return SUMMARY_READY;
    }
    return SUMMARY_PARTIAL;
  }

  private String oaStatus(PricePrepareOaSummaryResponse summary) {
    if (summary == null || summary.getTotalCount() == 0) {
      return SUMMARY_NOT_PREPARED;
    }
    if (summary.getTopProductCount() == summary.getReadyTopProductCount()
        && summary.getGapCount() == 0
        && summary.getReadyCount() == summary.getTotalCount()) {
      return SUMMARY_READY;
    }
    return SUMMARY_PARTIAL;
  }

  private <T> List<T> filterStatus(
      List<T> records,
      String status,
      java.util.function.Function<T, String> statusGetter) {
    if (!StringUtils.hasText(status)) {
      return records;
    }
    String statusValue = status.trim();
    return new ArrayList<>(
        records.stream()
            .filter(record -> statusValue.equals(statusGetter.apply(record)))
            .toList());
  }

  private <T> List<T> pageList(List<T> records, Integer page, Integer pageSize) {
    int pageNo = (int) pageNo(page);
    int size = (int) pageSize(pageSize);
    int fromIndex = Math.min((pageNo - 1) * size, records.size());
    int toIndex = Math.min(fromIndex + size, records.size());
    return records.subList(fromIndex, toIndex);
  }

  private LocalDateTime maxTime(LocalDateTime left, LocalDateTime right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isAfter(right) ? left : right;
  }

  private String summaryKey(String oaNo, String topProductCode) {
    return String.valueOf(oaNo) + "|" + String.valueOf(topProductCode);
  }

  private String text(Map<String, Object> row, String key) {
    Object value = value(row, key);
    return value == null ? "" : String.valueOf(value);
  }

  private int number(Map<String, Object> row, String key) {
    Object value = value(row, key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return 0;
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private LocalDateTime time(Map<String, Object> row, String key) {
    Object value = value(row, key);
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value == null) {
      return null;
    }
    return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
  }

  private Object value(Map<String, Object> row, String key) {
    if (row == null) {
      return null;
    }
    if (row.containsKey(key)) {
      return row.get(key);
    }
    String camel = snakeToCamel(key);
    if (row.containsKey(camel)) {
      return row.get(camel);
    }
    return row.get(key.toUpperCase());
  }

  private String snakeToCamel(String value) {
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '_') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(ch));
        upperNext = false;
      } else {
        builder.append(ch);
      }
    }
    return builder.toString();
  }

  private long pageNo(Integer page) {
    return page == null || page < 1 ? DEFAULT_PAGE : page;
  }

  private long pageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }
}
