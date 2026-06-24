package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
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
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmItemMapper;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
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
  private static final String SOURCE_MATERIAL_SCRAP_REF = "lp_material_scrap_ref";
  private static final String ACTION_SUPPLEMENT_SCRAP_MAPPING = "SUPPLEMENT_SCRAP_MAPPING";
  private static final String ACTION_CONFIRM_NO_SCRAP = "CONFIRM_NO_SCRAP";
  private static final String NO_SCRAP_STATUS_ACTIVE = "ACTIVE";
  private static final Set<String> PENDING_SUMMARY_STATUSES =
      Set.of(SUMMARY_NOT_PREPARED, SUMMARY_PARTIAL, SUMMARY_FAILED);

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final PricePrepareGapMapper gapMapper;
  private final QuotePriceTypeConfirmItemMapper priceTypeConfirmItemMapper;
  private final MakePartNoScrapConfirmationService noScrapConfirmationService;

  public PricePrepareQueryServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      PricePrepareGapMapper gapMapper,
      QuotePriceTypeConfirmItemMapper priceTypeConfirmItemMapper,
      MakePartNoScrapConfirmationService noScrapConfirmationService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.gapMapper = gapMapper;
    this.priceTypeConfirmItemMapper = priceTypeConfirmItemMapper;
    this.noScrapConfirmationService = noScrapConfirmationService;
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
    String periodMonth = CostPricingPeriodUtils.normalizePricingMonth(safe.getPeriodMonth());
    List<PricePrepareTopProductSummaryResponse> topSummaries =
        loadTopProductSummaries(safe.getOaNo(), null, periodMonth);
    Map<String, PricePrepareOaSummaryResponse> byOa = new LinkedHashMap<>();
    for (PricePrepareTopProductSummaryResponse topSummary : topSummaries) {
      PricePrepareOaSummaryResponse oaSummary =
          byOa.computeIfAbsent(topSummary.getOaNo(), this::emptyOaSummary);
      oaSummary.setPeriodMonth(periodMonth);
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
      notPrepared.setPeriodMonth(periodMonth);
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
    String periodMonth = CostPricingPeriodUtils.normalizePricingMonth(safe.getPeriodMonth());
    List<PricePrepareTopProductSummaryResponse> summaries =
        loadTopProductSummaries(
            safe.getOaNo(), safe.getOaFormItemId(), safe.getTopProductCode(), periodMonth);
    if (summaries.isEmpty()
        && StringUtils.hasText(safe.getOaNo())
        && StringUtils.hasText(safe.getTopProductCode())) {
      PricePrepareTopProductSummaryResponse notPrepared = new PricePrepareTopProductSummaryResponse();
      notPrepared.setOaNo(safe.getOaNo().trim());
      notPrepared.setTopProductCode(safe.getTopProductCode().trim());
      notPrepared.setPeriodMonth(periodMonth);
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
    String periodMonth = CostPricingPeriodUtils.normalizePricingMonth(safe.getPeriodMonth());
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
        loadTopProductSummaryMap(oaNos, topProductCodes, periodMonth);
    List<PricePrepareCandidateResponse> candidates = new ArrayList<>();
    for (CandidateSeed seed : seeds) {
      PricePrepareCandidateResponse candidate = toCandidate(seed, summaries.get(seed.key()), periodMonth);
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
    if (!StringUtils.hasText(safe.getPrepareNo())) {
      safe.setPeriodMonth(CostPricingPeriodUtils.normalizePricingMonth(safe.getPeriodMonth()));
    }
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
    if (!StringUtils.hasText(safe.getPrepareNo())) {
      safe.setPeriodMonth(CostPricingPeriodUtils.normalizePricingMonth(safe.getPeriodMonth()));
    }
    Page<PricePrepareGap> page =
        gapMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildGapQuery(safe)
                .orderByDesc(PricePrepareGap::getCreatedAt)
                .orderByDesc(PricePrepareGap::getId));
    enrichPriceTypes(page.getRecords());
    enrichNoScrapConfirmations(page.getRecords());
    return new PricePrepareGapPageResponse(page.getTotal(), page.getRecords());
  }

  private void enrichPriceTypes(List<PricePrepareGap> gaps) {
    if (gaps == null || gaps.isEmpty()) {
      return;
    }
    Map<Long, QuotePriceTypeConfirmItem> confirmItemById = loadConfirmItemsById(gaps);
    for (PricePrepareGap gap : gaps) {
      Long itemId = gap == null ? null : gap.getPriceTypeConfirmItemId();
      QuotePriceTypeConfirmItem confirmItem = itemId == null ? null : confirmItemById.get(itemId);
      String targetMaterialCode =
          firstText(gap == null ? null : gap.getGapMaterialCode(), gap == null ? null : gap.getMaterialCode());
      if (confirmItem != null
          && sameText(confirmItem.getMaterialCode(), targetMaterialCode)
          && StringUtils.hasText(confirmItem.getPriceType())) {
        gap.setPriceType(normalizePriceTypeText(confirmItem.getPriceType()));
      }
    }
    for (PricePrepareGap gap : gaps) {
      if (gap == null || StringUtils.hasText(gap.getPriceType())) {
        continue;
      }
      QuotePriceTypeConfirmItem confirmItem =
          findPriceTypeConfirmItem(gap, firstText(gap.getGapMaterialCode(), gap.getMaterialCode()));
      if (confirmItem == null) {
        confirmItem = findPriceTypeConfirmItem(gap, gap.getMaterialCode());
      }
      if (confirmItem != null) {
        gap.setPriceTypeConfirmItemId(confirmItem.getId());
        if (StringUtils.hasText(confirmItem.getPriceType())) {
          gap.setPriceType(normalizePriceTypeText(confirmItem.getPriceType()));
        }
      }
    }
  }

  private Map<Long, QuotePriceTypeConfirmItem> loadConfirmItemsById(List<PricePrepareGap> gaps) {
    Set<Long> ids = new LinkedHashSet<>();
    for (PricePrepareGap gap : gaps) {
      if (gap != null && gap.getPriceTypeConfirmItemId() != null) {
        ids.add(gap.getPriceTypeConfirmItemId());
      }
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<QuotePriceTypeConfirmItem> items =
        priceTypeConfirmItemMapper.selectList(
            Wrappers.<QuotePriceTypeConfirmItem>lambdaQuery()
                .in(QuotePriceTypeConfirmItem::getId, ids));
    Map<Long, QuotePriceTypeConfirmItem> result = new HashMap<>();
    if (items == null) {
      return result;
    }
    for (QuotePriceTypeConfirmItem item : items) {
      if (item != null && item.getId() != null) {
        result.put(item.getId(), item);
      }
    }
    return result;
  }

  private QuotePriceTypeConfirmItem findPriceTypeConfirmItem(
      PricePrepareGap gap, String materialCode) {
    String confirmNo = trimToNull(gap == null ? null : gap.getPriceTypeConfirmNo());
    String targetMaterialCode = trimToNull(materialCode);
    if (confirmNo == null || targetMaterialCode == null) {
      return null;
    }
    LambdaQueryWrapper<QuotePriceTypeConfirmItem> query =
        Wrappers.<QuotePriceTypeConfirmItem>lambdaQuery()
            .eq(QuotePriceTypeConfirmItem::getConfirmNo, confirmNo)
            .eq(QuotePriceTypeConfirmItem::getMaterialCode, targetMaterialCode);
    String productCode = trimToNull(gap.getTopProductCode());
    if (productCode != null) {
      query.eq(QuotePriceTypeConfirmItem::getProductCode, productCode);
    }
    if (gap.getOaFormItemId() != null) {
      query.eq(QuotePriceTypeConfirmItem::getOaFormItemId, gap.getOaFormItemId());
    }
    List<QuotePriceTypeConfirmItem> items =
        priceTypeConfirmItemMapper.selectList(
            query
                .orderByDesc(QuotePriceTypeConfirmItem::getTypeEffectiveFrom)
                .orderByDesc(QuotePriceTypeConfirmItem::getId)
                .last("LIMIT 1"));
    return items == null || items.isEmpty() ? null : items.get(0);
  }

  private void enrichNoScrapConfirmations(List<PricePrepareGap> gaps) {
    if (gaps == null || gaps.isEmpty()) {
      return;
    }
    Map<String, String> periodByPrepareNo = loadPeriodByPrepareNo(gaps);
    for (PricePrepareGap gap : gaps) {
      if (!isScrapMappingGap(gap)) {
        continue;
      }
      String materialNo = trimToNull(gap.getGapMaterialCode());
      String businessUnitType = trimToNull(gap.getBusinessUnitType());
      String periodMonth = firstText(gap.getPeriodMonth(), periodByPrepareNo.get(trimToNull(gap.getPrepareNo())));
      gap.setActionType(ACTION_CONFIRM_NO_SCRAP);
      gap.setActionMaterialNo(materialNo);
      gap.setCanConfirmNoScrap(true);
      if (materialNo == null || businessUnitType == null || periodMonth == null) {
        continue;
      }
      // 确认记录针对“料号在生效期间无废料”，不是某次缺口行；重新生成后 gap_id 会变化，
      // 因此缺口清单必须按 业务单元 + 缺口料号 + 价格月份 回填有效确认。
      NoScrapConfirmResponse confirmation =
          noScrapConfirmationService.findEffective(materialNo, periodMonth, businessUnitType);
      if (confirmation != null) {
        gap.setNoScrapConfirmationId(confirmation.getId());
        gap.setNoScrapConfirmationStatus(
            StringUtils.hasText(confirmation.getStatus())
                ? confirmation.getStatus()
                : NO_SCRAP_STATUS_ACTIVE);
        gap.setNoScrapConfirmation(confirmation);
        gap.setConfirmedBy(confirmation.getConfirmedBy());
        gap.setConfirmedAt(confirmation.getConfirmedAt());
        gap.setConfirmReason(confirmation.getConfirmReason());
        gap.setCanConfirmNoScrap(false);
      }
    }
  }

  private Map<String, String> loadPeriodByPrepareNo(List<PricePrepareGap> gaps) {
    Set<String> prepareNos = new LinkedHashSet<>();
    for (PricePrepareGap gap : gaps) {
      String prepareNo = trimToNull(gap.getPrepareNo());
      if (prepareNo != null) {
        prepareNos.add(prepareNo);
      }
    }
    if (prepareNos.isEmpty()) {
      return Map.of();
    }
    List<PricePrepareBatch> batches =
        batchMapper.selectList(
            Wrappers.<PricePrepareBatch>lambdaQuery()
                .in(PricePrepareBatch::getPrepareNo, prepareNos));
    Map<String, String> result = new HashMap<>();
    if (batches == null) {
      return result;
    }
    for (PricePrepareBatch batch : batches) {
      String prepareNo = trimToNull(batch.getPrepareNo());
      String periodMonth = trimToNull(batch.getPeriodMonth());
      if (prepareNo != null && periodMonth != null) {
        result.put(prepareNo, periodMonth);
      }
    }
    return result;
  }

  private boolean isScrapMappingGap(PricePrepareGap gap) {
    if (gap == null) {
      return false;
    }
    return SOURCE_MATERIAL_SCRAP_REF.equals(gap.getSourceTable())
        || containsText(gap.getMessage(), "缺废料映射")
        || containsText(gap.getMessage(), "MISSING_SCRAP_MAPPING");
  }

  List<PricePrepareTopProductSummaryResponse> loadTopProductSummaries(
      String oaNo, String topProductCode, String periodMonth) {
    return loadTopProductSummaries(oaNo, null, topProductCode, periodMonth);
  }

  List<PricePrepareTopProductSummaryResponse> loadTopProductSummaries(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    QueryWrapper<PricePrepareItem> itemQuery =
        Wrappers.<PricePrepareItem>query()
            .select(
                "oa_no",
                "oa_form_item_id",
                "period_month",
                "top_product_code",
                "COUNT(*) AS total_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_READY + "' THEN 1 ELSE 0 END) AS ready_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_FAILED + "' THEN 1 ELSE 0 END) AS failed_count",
                "MAX(updated_at) AS updated_at");
    eqIfText(itemQuery, "oa_no", oaNo);
    if (oaFormItemId != null) {
      itemQuery.eq("oa_form_item_id", oaFormItemId);
    }
    eqIfText(itemQuery, "top_product_code", topProductCode);
    eqIfText(itemQuery, "period_month", periodMonth);
    itemQuery.groupBy("oa_no", "oa_form_item_id", "period_month", "top_product_code");

    Map<String, PricePrepareTopProductSummaryResponse> summaries = new LinkedHashMap<>();
    List<Map<String, Object>> itemMaps = itemMapper.selectMaps(itemQuery);
    if (itemMaps != null) {
      for (Map<String, Object> row : itemMaps) {
        String rowOaNo = text(row, "oa_no");
        Long rowOaFormItemId = longValue(row, "oa_form_item_id");
        String rowPeriodMonth = text(row, "period_month");
        String rowTopProductCode = text(row, "top_product_code");
        PricePrepareTopProductSummaryResponse summary = new PricePrepareTopProductSummaryResponse();
        summary.setOaNo(rowOaNo);
        summary.setOaFormItemId(rowOaFormItemId);
        summary.setPeriodMonth(rowPeriodMonth);
        summary.setTopProductCode(rowTopProductCode);
        summary.setTotalCount(number(row, "total_count"));
        summary.setReadyCount(number(row, "ready_count"));
        summary.setUpdatedAt(time(row, "updated_at"));
        int failedCount = number(row, "failed_count");
        summary.setStatus(topStatus(summary, failedCount));
        summaries.put(summaryKey(rowOaNo, rowOaFormItemId, rowTopProductCode), summary);
      }
    }

    Map<String, Integer> gapCounts = loadGapCounts(oaNo, oaFormItemId, topProductCode, periodMonth);
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

  private Map<String, Integer> loadGapCounts(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    QueryWrapper<PricePrepareGap> gapQuery =
        Wrappers.<PricePrepareGap>query()
            .select("oa_no", "oa_form_item_id", "top_product_code", "COUNT(*) AS gap_count");
    eqIfText(gapQuery, "oa_no", oaNo);
    if (oaFormItemId != null) {
      gapQuery.eq("oa_form_item_id", oaFormItemId);
    }
    eqIfText(gapQuery, "top_product_code", topProductCode);
    eqIfText(gapQuery, "period_month", periodMonth);
    gapQuery.groupBy("oa_no", "oa_form_item_id", "top_product_code");
    Map<String, Integer> gapCounts = new HashMap<>();
    List<Map<String, Object>> gapMaps = gapMapper.selectMaps(gapQuery);
    if (gapMaps == null) {
      return gapCounts;
    }
    for (Map<String, Object> row : gapMaps) {
      gapCounts.put(
          summaryKey(text(row, "oa_no"), longValue(row, "oa_form_item_id"), text(row, "top_product_code")),
          number(row, "gap_count"));
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
      Set<String> oaNos, Set<String> topProductCodes, String periodMonth) {
    if (oaNos == null || oaNos.isEmpty() || topProductCodes == null || topProductCodes.isEmpty()) {
      return Map.of();
    }
    QueryWrapper<PricePrepareItem> itemQuery =
        Wrappers.<PricePrepareItem>query()
            .select(
                "oa_no",
                "period_month",
                "top_product_code",
                "COUNT(*) AS total_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_READY + "' THEN 1 ELSE 0 END) AS ready_count",
                "SUM(CASE WHEN status = '" + ITEM_STATUS_FAILED + "' THEN 1 ELSE 0 END) AS failed_count",
                "MAX(updated_at) AS updated_at")
            .in("oa_no", oaNos)
            .in("top_product_code", topProductCodes)
            .eq("period_month", periodMonth)
            .groupBy("oa_no", "period_month", "top_product_code");

    Map<String, PricePrepareTopProductSummaryResponse> summaries = new LinkedHashMap<>();
    List<Map<String, Object>> itemMaps = itemMapper.selectMaps(itemQuery);
    if (itemMaps != null) {
      for (Map<String, Object> row : itemMaps) {
        String rowOaNo = text(row, "oa_no");
        String rowPeriodMonth = text(row, "period_month");
        String rowTopProductCode = text(row, "top_product_code");
        PricePrepareTopProductSummaryResponse summary = new PricePrepareTopProductSummaryResponse();
        summary.setOaNo(rowOaNo);
        summary.setPeriodMonth(rowPeriodMonth);
        summary.setTopProductCode(rowTopProductCode);
        summary.setTotalCount(number(row, "total_count"));
        summary.setReadyCount(number(row, "ready_count"));
        summary.setUpdatedAt(time(row, "updated_at"));
        summary.setStatus(topStatus(summary, number(row, "failed_count")));
        summaries.put(summaryKey(rowOaNo, rowTopProductCode), summary);
      }
    }

    Map<String, Integer> gapCounts = loadGapCounts(oaNos, topProductCodes, periodMonth);
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

  private Map<String, Integer> loadGapCounts(
      Set<String> oaNos, Set<String> topProductCodes, String periodMonth) {
    QueryWrapper<PricePrepareGap> gapQuery =
        Wrappers.<PricePrepareGap>query()
            .select("oa_no", "top_product_code", "COUNT(*) AS gap_count")
            .in("oa_no", oaNos)
            .in("top_product_code", topProductCodes)
            .eq("period_month", periodMonth)
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
      CandidateSeed seed, PricePrepareTopProductSummaryResponse summary, String periodMonth) {
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
      candidate.setPeriodMonth(periodMonth);
      candidate.setPrepareStatus(SUMMARY_NOT_PREPARED);
      candidate.setUpdatedAt(maxTime(seed.form().getUpdatedAt(), seed.item().getUpdatedAt()));
      return candidate;
    }
    candidate.setPeriodMonth(summary.getPeriodMonth());
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

  private boolean sameText(String left, String right) {
    String a = trimToNull(left);
    String b = trimToNull(right);
    return a != null && a.equals(b);
  }

  private String normalizePriceTypeText(String value) {
    String text = trimToNull(value);
    if (text == null) {
      return null;
    }
    return switch (text.toUpperCase()) {
      case "FIXED" -> "固定价";
      case "SETTLE_FIXED" -> "结算固定价";
      case "LINKED" -> "联动价";
      case "RANGE" -> "区间价";
      case "MAKE", "MAKE_PART" -> "自制件";
      default -> switch (text) {
        case "固定采购价", "采购固定价" -> "固定价";
        case "结算价", "结算固定价", "家用结算价" -> "结算固定价";
        default -> text;
      };
    };
  }

  private boolean containsText(String value, String expected) {
    return StringUtils.hasText(value) && value.contains(expected);
  }

  private record CandidateSeed(OaForm form, OaFormItem item, String oaNo, String topProductCode) {
    private String key() {
      return String.valueOf(oaNo) + "||" + String.valueOf(topProductCode);
    }
  }

  private LambdaQueryWrapper<PricePrepareBatch> buildBatchQuery(
      PricePrepareBatchQueryRequest request) {
    LambdaQueryWrapper<PricePrepareBatch> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareBatch::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareBatch::getOaNo, request.getOaNo());
    eqIfValue(query, PricePrepareBatch::getOaFormItemId, request.getOaFormItemId());
    eqIfText(query, PricePrepareBatch::getTopProductCode, request.getTopProductCode());
    eqIfText(query, PricePrepareBatch::getPriceTypeConfirmNo, request.getPriceTypeConfirmNo());
    eqIfText(query, PricePrepareBatch::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PricePrepareBatch::getStatus, request.getStatus());
    return query;
  }

  private LambdaQueryWrapper<PricePrepareItem> buildItemQuery(
      PricePrepareItemQueryRequest request) {
    LambdaQueryWrapper<PricePrepareItem> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareItem::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareItem::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PricePrepareItem::getPriceTypeConfirmNo, request.getPriceTypeConfirmNo());
    eqIfText(query, PricePrepareItem::getOaNo, request.getOaNo());
    eqIfValue(query, PricePrepareItem::getOaFormItemId, request.getOaFormItemId());
    eqIfText(query, PricePrepareItem::getTopProductCode, request.getTopProductCode());
    eqIfText(query, PricePrepareItem::getMaterialCode, request.getMaterialCode());
    eqIfText(query, PricePrepareItem::getItemType, request.getItemType());
    eqIfText(query, PricePrepareItem::getStatus, request.getStatus());
    return query;
  }

  private LambdaQueryWrapper<PricePrepareGap> buildGapQuery(PricePrepareGapQueryRequest request) {
    LambdaQueryWrapper<PricePrepareGap> query = Wrappers.lambdaQuery();
    eqIfText(query, PricePrepareGap::getPrepareNo, request.getPrepareNo());
    eqIfText(query, PricePrepareGap::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PricePrepareGap::getPriceTypeConfirmNo, request.getPriceTypeConfirmNo());
    eqIfText(query, PricePrepareGap::getOaNo, request.getOaNo());
    eqIfValue(query, PricePrepareGap::getOaFormItemId, request.getOaFormItemId());
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

  private <T> void eqIfValue(
      LambdaQueryWrapper<T> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
      Object value) {
    if (value != null) {
      query.eq(column, value);
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
    return summaryKey(oaNo, null, topProductCode);
  }

  private String summaryKey(String oaNo, Long oaFormItemId, String topProductCode) {
    return String.valueOf(oaNo)
        + "|"
        + (oaFormItemId == null ? "" : oaFormItemId)
        + "|"
        + String.valueOf(topProductCode);
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

  private Long longValue(Map<String, Object> row, String key) {
    Object value = value(row, key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    return Long.parseLong(String.valueOf(value));
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
