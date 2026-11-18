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
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通用确认流程，不读取任何一种价格源表。每种价格通过价格准备返回统一候选快照。 */
@Service
public class SupplierPriceReviewWorkflow {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SupplierPriceReviewWorkflow.class);
  private final QuotePricingPeriod pricingPeriods;

  public record Confirmation(String periodMonth, String scopeKey, String fingerprint,
      Boolean hasNewApproval, java.time.LocalDate pricingDate) {}

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
    if (request == null || request.scopeKey() == null || request.fingerprint() == null
        || request.hasNewApproval() == null || request.pricingDate() == null) {
      throw new IllegalArgumentException("缺少待确认的取价记录，请刷新后重试");
    }
    String month = pricingPeriods.requireActiveMonth(request.periodMonth());
    OaFormItem item = items.selectOne(Wrappers.lambdaQuery(OaFormItem.class)
        .eq(OaFormItem::getId, itemId).last("FOR UPDATE"));
    if (item == null) throw new IllegalArgumentException("报价产品不存在");
    var query = new QuotePricePrepareGenerateRequest();
    query.setPeriodMonth(month);
    query.setRefreshCandidates(true);
    query.setPriceAsOfTime(request.pricingDate().atStartOfDay());
    var current = prices.checkPriceSources(oaNo, itemId, query);
    var review = current.getSupplierPriceReviews().stream()
        .filter(r -> request.scopeKey().equals(r.scopeKey())).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("当前物料已不需要供货比率确认，请刷新工作台"));
    if (!request.fingerprint().equals(review.fingerprint())) {
      throw new IllegalArgumentException("候选价格或供货比率已变化，请查看最新数据后确认");
    }
    SupplierPriceScope scope = review.scope();
    if (!Objects.equals(scope.oaNo(), oaNo) || !Objects.equals(scope.oaFormItemId(), itemId)
        || !Objects.equals(scope.periodMonth(), month)) throw new IllegalArgumentException("取价确认范围不一致");
    if (!BusinessUnitContext.isAdmin()
        && !Objects.equals(BusinessUnitContext.getCurrentBusinessUnitType(), scope.businessUnitType())) {
      throw new IllegalArgumentException("不能确认其他业务单元的价格");
    }
    String choice = request.hasNewApproval() ? SupplierPriceSelectionService.WAIT_IMPORT
        : SupplierPriceSelectionService.FALLBACK_HIGH;
    var latest = decisions.latest(review.scopeKey());
    if (latest != null && choice.equals(latest.getDecision())
        && review.fingerprint().equals(latest.getFingerprint())) return latest;
    SupplierPriceDecision decision = new SupplierPriceDecision();
    decision.setScopeKey(review.scopeKey());
    decision.setFingerprint(review.fingerprint());
    decision.setBusinessUnitType(scope.businessUnitType());
    decision.setOaNo(oaNo);
    decision.setOaFormItemId(itemId);
    decision.setPeriodMonth(month);
    decision.setPricingDate(scope.pricingDate());
    decision.setOrgCode(scope.orgCode());
    decision.setMaterialCode(scope.materialCode());
    decision.setPriceType(scope.priceType());
    decision.setDecision(choice);
    decision.setConfirmedBy(actor);
    decision.setConfirmedAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    decision.setRetryStatus("WAITING");
    decisions.insert(decision);
    return decision;
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
