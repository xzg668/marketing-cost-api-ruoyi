package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.SupplierPriceConfirmationResponse;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通用确认流程，不读取任何一种价格源表。每种价格通过价格准备返回统一候选快照。 */
@Service
public class SupplierPriceReviewWorkflow {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SupplierPriceReviewWorkflow.class);
  private static final String REPRICE_PENDING = "REPRICE_PENDING";
  private static final int MAX_CONFIRMATIONS = 200;
  private final QuotePricingPeriod pricingPeriods;

  public record Confirmation(String periodMonth, String scopeKey, String fingerprint,
      Boolean hasNewApproval, java.time.LocalDate pricingDate) {}

  public record SavedReview(Long decisionId, String scopeKey, String fingerprint, String decision) {}

  private final QuotePricePrepareWorkbenchService prices;
  private final SupplierPriceDecisionMapper decisions;
  private final OaFormItemMapper items;

  public SupplierPriceReviewWorkflow(QuotePricePrepareWorkbenchService prices,
      SupplierPriceDecisionMapper decisions, OaFormItemMapper items,
      QuotePricingPeriod pricingPeriods) {
    this.pricingPeriods = pricingPeriods;
    this.prices = prices;
    this.decisions = decisions;
    this.items = items;
  }

  @Transactional(rollbackFor = Exception.class)
  public SupplierPriceDecision confirm(String oaNo, Long itemId, Confirmation request, String actor) {
    validate(request);
    String month = pricingPeriods.requireActiveMonth(request.periodMonth());
    requireItemLock(itemId);
    var current = prices.checkPriceSources(oaNo, itemId, candidateQuery(request, month));
    return save(findReview(oaNo, itemId, request, month, current.getSupplierPriceReviews()), request, actor, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public List<SavedReview> saveReviews(String oaNo, Long itemId, List<Confirmation> requests, String actor) {
    if (requests == null || requests.isEmpty() || requests.size() > MAX_CONFIRMATIONS)
      throw new IllegalArgumentException("请选择要确认的供货物料");
    requests.forEach(this::validate);
    var first = requests.getFirst();
    String month = pricingPeriods.requireActiveMonth(first.periodMonth());
    var scopeKeys = new LinkedHashSet<String>();
    for (var request : requests) {
      if (!Objects.equals(first.periodMonth(), request.periodMonth())
          || !first.pricingDate().equals(request.pricingDate()) || !scopeKeys.add(request.scopeKey()))
        throw new IllegalArgumentException("请确认同一取价日期下互不重复的物料");
    }
    requireItemLock(itemId);
    var current = prices.checkSupplierReviewCandidates(oaNo, itemId, candidateQuery(first, month));
    var verified = requests.stream().map(request -> findReview(oaNo, itemId, request, month, current)).toList();
    var saved = new java.util.ArrayList<SavedReview>(requests.size());
    for (int i = 0; i < requests.size(); i++) {
      var decision = save(verified.get(i), requests.get(i), actor, true);
      saved.add(new SavedReview(decision.getId(), decision.getScopeKey(), decision.getFingerprint(), decision.getDecision()));
    }
    return List.copyOf(saved);
  }

  private void validate(Confirmation request) {
    if (request == null || request.scopeKey() == null || request.fingerprint() == null
        || request.hasNewApproval() == null || request.pricingDate() == null) {
      throw new IllegalArgumentException("缺少待确认的取价记录，请刷新后重试");
    }
  }

  private void requireItemLock(Long itemId) {
    OaFormItem item = items.selectOne(Wrappers.lambdaQuery(OaFormItem.class)
        .eq(OaFormItem::getId, itemId).last("FOR UPDATE"));
    if (item == null) throw new IllegalArgumentException("报价产品不存在");
  }

  private QuotePricePrepareGenerateRequest candidateQuery(Confirmation request, String month) {
    var query = new QuotePricePrepareGenerateRequest();
    query.setPeriodMonth(month);
    query.setRefreshCandidates(true);
    query.setPriceAsOfTime(request.pricingDate().atStartOfDay());
    return query;
  }

  private SupplierPriceReview findReview(String oaNo, Long itemId, Confirmation request,
      String month, List<SupplierPriceReview> current) {
    var review = current.stream()
        .filter(r -> request.scopeKey().equals(r.scopeKey())).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("当前物料已不需要供货比率确认，请刷新工作台"));
    if (!request.fingerprint().equals(review.fingerprint())) {
      throw new IllegalArgumentException("候选价格或供货比率已变化，请查看最新数据后确认");
    }
    SupplierPriceScope scope = review.scope();
    if (!Objects.equals(scope.oaNo(), oaNo) || !Objects.equals(scope.oaFormItemId(), itemId)
        || !Objects.equals(scope.periodMonth(), month)) throw new IllegalArgumentException("取价确认范围不一致");
    requireUnit(scope.businessUnitType());
    return review;
  }

  private void requireUnit(String unit) {
    if (!BusinessUnitContext.isAdmin()
        && !Objects.equals(BusinessUnitContext.getCurrentBusinessUnitType(), unit)) {
      throw new IllegalArgumentException("不能确认其他业务单元的价格");
    }
  }

  private SupplierPriceDecision save(SupplierPriceReview review, Confirmation request, String actor, boolean deferred) {
    var scope = review.scope();
    String choice = request.hasNewApproval() ? SupplierPriceSelectionService.WAIT_IMPORT
        : SupplierPriceSelectionService.FALLBACK_HIGH;
    var latest = decisions.latest(review.scopeKey());
    if (latest != null && choice.equals(latest.getDecision())
        && review.fingerprint().equals(latest.getFingerprint())) {
      if (deferred && !REPRICE_PENDING.equals(latest.getRetryStatus())
          && !"RESOLVED".equals(latest.getRetryStatus())) {
        latest.setRetryStatus(REPRICE_PENDING);
        decisions.updateById(latest);
      }
      return latest;
    }
    SupplierPriceDecision decision = new SupplierPriceDecision();
    decision.setScopeKey(review.scopeKey());
    decision.setFingerprint(review.fingerprint());
    decision.setBusinessUnitType(scope.businessUnitType());
    decision.setOaNo(scope.oaNo());
    decision.setOaFormItemId(scope.oaFormItemId());
    decision.setPeriodMonth(scope.periodMonth());
    decision.setPricingDate(scope.pricingDate());
    decision.setOrgCode(scope.orgCode());
    decision.setMaterialCode(scope.materialCode());
    decision.setPriceType(scope.priceType());
    decision.setDecision(choice);
    decision.setConfirmedBy(actor);
    decision.setConfirmedAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    decision.setRetryStatus(deferred ? REPRICE_PENDING : "WAITING");
    decisions.insert(decision);
    return decision;
  }

  public List<SavedReview> pendingReprice(String oaNo, Long itemId, String periodMonth) {
    String month = pricingPeriods.requireActiveMonth(periodMonth);
    var pending = decisions.pendingReprice(oaNo, itemId, month);
    if (pending.isEmpty()) return List.of();
    requireUnit(pending.getFirst().getBusinessUnitType());
    return pending.stream()
        .filter(d -> d.getPricingDate().equals(pending.getFirst().getPricingDate()))
        .limit(MAX_CONFIRMATIONS)
        .map(d -> new SavedReview(d.getId(), d.getScopeKey(), d.getFingerprint(), d.getDecision()))
        .toList();
  }

  /** Run the follow-up after the reviewer has received the saved decision IDs. */
  @com.sanhua.marketingcost.service.reprice.QuotationWrite
  @com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency
  @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public SupplierPriceConfirmationResponse repriceSaved(String oaNo, Long itemId, List<Long> ids) {
    if (ids == null || ids.isEmpty() || ids.size() > MAX_CONFIRMATIONS || ids.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("缺少已保存的供货确认记录");
    requireItemLock(itemId);
    var keys = new LinkedHashSet<>(ids);
    var saved = decisions.selectBatchIds(keys);
    if (saved.size() != keys.size()) throw new IllegalArgumentException("供货确认记录已变化，请刷新后重试");
    var first = saved.getFirst();
    requireUnit(first.getBusinessUnitType());
    String month = pricingPeriods.requireActiveMonth(first.getPeriodMonth());
    for (var decision : saved) {
      if (!Objects.equals(oaNo, decision.getOaNo()) || !Objects.equals(itemId, decision.getOaFormItemId())
          || !Objects.equals(month, decision.getPeriodMonth())
          || !Objects.equals(first.getBusinessUnitType(), decision.getBusinessUnitType())
          || !Objects.equals(first.getPricingDate(), decision.getPricingDate()))
        throw new IllegalArgumentException("已保存的确认记录不属于本次取价范围");
      var latest = decisions.latest(decision.getScopeKey());
      if (latest == null || !Objects.equals(decision.getId(), latest.getId()))
        throw new IllegalArgumentException("供货确认已更新，请查看最新结果");
    }
    var pending = decisions.pendingReprice(oaNo, itemId, month).stream()
        .filter(d -> Objects.equals(first.getPricingDate(), d.getPricingDate()))
        .toList();
    if (pending.isEmpty()) {
      return new SupplierPriceConfirmationResponse(first.getId(), first.getDecision(), "CURRENT",
          "已读取当前取价结果", prices.getPricePrepare(oaNo, itemId, month));
    }
    var result = reprice(first);
    for (var decision : pending) {
      if (!Objects.equals(first.getId(), decision.getId())) updateRetryStatus(decision, result);
    }
    return new SupplierPriceConfirmationResponse(first.getId(), first.getDecision(), "COMPLETED",
        "取价结果已更新", result);
  }

  /** 仅在 confirm 的独立事务提交后调用；后续失败仍须返回已经保存的选择。 */
  public SupplierPriceConfirmationResponse repriceConfirmed(SupplierPriceDecision decision) {
    String saved = SupplierPriceSelectionService.FALLBACK_HIGH.equals(decision.getDecision())
        ? "最高价选择已保存" : "新增审核选择已保存";
    try {
      var result = reprice(decision);
      return new SupplierPriceConfirmationResponse(decision.getId(), decision.getDecision(),
          "COMPLETED", saved + "，取价结果已更新", result);
    } catch (RuntimeException failure) {
      boolean blocked = failure instanceof EffectiveTechnicalDataException
          || failure instanceof QuoteIngestException || failure instanceof IllegalArgumentException;
      String reason = blocked && failure.getMessage() != null && !failure.getMessage().isBlank()
          ? failure.getMessage() : "暂时无法完成，请稍后在核算工作台重试";
      String message = saved + "；重新取价未完成：" + reason;
      log.warn("Confirmed supplier decision {} could not complete repricing", decision.getId(), failure);
      try {
        // 只补充仍待处理记录的原因，不能覆盖并发完成的状态或改写已确认的选择。
        decisions.update(null, Wrappers.<SupplierPriceDecision>lambdaUpdate()
            .eq(SupplierPriceDecision::getId, decision.getId())
            .eq(SupplierPriceDecision::getRetryStatus, "WAITING")
            .set(SupplierPriceDecision::getRetryMessage, message.length() > 500 ? message.substring(0, 500) : message));
      } catch (RuntimeException recordingFailure) {
        log.warn("Could not record repricing failure for confirmed supplier decision {}", decision.getId(), recordingFailure);
      }
      QuotePricePrepareWorkbenchResponse current = null;
      try {
        var query = new QuotePricePrepareGenerateRequest();
        query.setPeriodMonth(decision.getPeriodMonth());
        query.setPriceAsOfTime(decision.getPricingDate().atStartOfDay());
        query.setRefreshCandidates(true);
        current = prices.checkPriceSources(decision.getOaNo(), decision.getOaFormItemId(), query);
      } catch (RuntimeException readFailure) {
        log.warn("Could not refresh price view for confirmed supplier decision {}", decision.getId(), readFailure);
      }
      return new SupplierPriceConfirmationResponse(decision.getId(), decision.getDecision(),
          blocked ? "BLOCKED" : "FAILED", message, current);
    }
  }

  /** 确认事务提交后重新生成；导入重试仍沿用原异常与重试处理。 */
  public QuotePricePrepareWorkbenchResponse reprice(SupplierPriceDecision decision) {
    var request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(decision.getPeriodMonth());
    request.setPriceAsOfTime(decision.getPricingDate().atStartOfDay());
    var result = prices.generate(decision.getOaNo(), decision.getOaFormItemId(), request);
    updateRetryStatus(decision, result);
    // 一次产品取价同时处理多个物料，逐项关闭已解决事项，避免后续导入重复刷新。
    for (var waiting : decisions.waiting(decision.getBusinessUnitType(), decision.getPeriodMonth())) {
      if (Objects.equals(waiting.getOaFormItemId(), decision.getOaFormItemId())
          && Objects.equals(waiting.getPricingDate(), decision.getPricingDate())
          && !Objects.equals(waiting.getId(), decision.getId())) updateRetryStatus(waiting, result);
    }
    return result;
  }

  private void updateRetryStatus(SupplierPriceDecision decision, QuotePricePrepareWorkbenchResponse result) {
    boolean stillWaiting = result.getSupplierPriceReviews().stream()
        .anyMatch(r -> decision.getScopeKey().equals(r.scopeKey())
            && !SupplierPriceSelectionService.FALLBACK_HIGH.equals(r.status()));
    // An explicit withdrawal can remove every candidate and therefore every review.
    // Keep that material retryable until its actual price gap has also been resolved.
    boolean hasPriceGap = result.getGaps() != null && result.getGaps().getRecords() != null
        && result.getGaps().getRecords().stream().anyMatch(gap ->
            (Objects.equals(decision.getMaterialCode(), gap.getMaterialCode())
                || Objects.equals(decision.getMaterialCode(), gap.getGapMaterialCode()))
            && (gap.getPriceType() == null || Objects.equals(decision.getPriceType(), gap.getPriceType())));
    decision.setRetryStatus(stillWaiting || hasPriceGap ? "WAITING" : "RESOLVED");
    decision.setRetryMessage(stillWaiting ? "供货比率仍不足，请补充后重新导入"
        : hasPriceGap ? "当前物料仍有价格缺口，请补充有效供货价格" : "已自动重新取价");
    decisions.updateById(decision);
  }
}
