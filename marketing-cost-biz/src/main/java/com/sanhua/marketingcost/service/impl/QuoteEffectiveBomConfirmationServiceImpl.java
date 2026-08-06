package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeCommand;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomActorProvider;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomConfirmationCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 第2步确认前只暂存可覆盖版本，确认时才冻结最终树并锁定报价物料。 */
@Service
public class QuoteEffectiveBomConfirmationServiceImpl
    implements QuoteEffectiveBomConfirmationService {

  private final QuoteEffectiveBomApplicationService effectiveBomService;
  private final QuoteBomMonthlyFreezeService monthlyFreezeService;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final QuoteBomConfirmationService confirmationService;
  private final QuoteEffectiveBomActorProvider actorProvider;
  private final QuoteBomAlternativeMonthlyInheritanceService monthlyInheritanceService;

  @Autowired
  public QuoteEffectiveBomConfirmationServiceImpl(
      QuoteEffectiveBomApplicationService effectiveBomService,
      QuoteBomMonthlyFreezeService monthlyFreezeService,
      QuoteProductBomCostingBuildService costingBuildService,
      QuoteBomConfirmationService confirmationService,
      QuoteEffectiveBomActorProvider actorProvider,
      QuoteBomAlternativeMonthlyInheritanceService monthlyInheritanceService) {
    this.effectiveBomService = effectiveBomService;
    this.monthlyFreezeService = monthlyFreezeService;
    this.costingBuildService = costingBuildService;
    this.confirmationService = confirmationService;
    this.actorProvider = actorProvider;
    this.monthlyInheritanceService = monthlyInheritanceService;
  }

  QuoteEffectiveBomConfirmationServiceImpl(
      QuoteEffectiveBomApplicationService effectiveBomService,
      QuoteBomMonthlyFreezeService monthlyFreezeService,
      QuoteProductBomCostingBuildService costingBuildService,
      QuoteBomConfirmationService confirmationService,
      QuoteEffectiveBomActorProvider actorProvider) {
    this(
        effectiveBomService,
        monthlyFreezeService,
        costingBuildService,
        confirmationService,
        actorProvider,
        null);
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse prepareCostingBom(
      String oaNo, Long oaFormItemId) {
    QuoteEffectiveBomConfirmationCandidate candidate =
        effectiveBomService.prepareConfirmation(oaNo, oaFormItemId);
    if (confirmationService.hasActiveConfirmation(
        candidate.response().oaNo(),
        candidate.response().oaFormItemId(),
        candidate.response().topProductCode(),
        candidate.response().costPeriodMonth())) {
      throw failure(
          "COSTING_BOM_ALREADY_CONFIRMED",
          "报价物料明细已经确认，不能自动覆盖");
    }

    QuoteBomMonthlyFreezeResult staged = stageOrReuse(candidate, oaFormItemId);
    String buildBatchId = required(staged.buildBatchId(), "本次计价 BOM 未生成结果编号");
    QuoteBomCostingBuildResponse costing =
        costingBuildService.buildFromEffectiveBom(oaFormItemId, buildBatchId);
    requireSameBuild(buildBatchId, costing.buildBatchId(), "报价物料明细");
    return costing;
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteEffectiveBomConfirmResponse confirm(
      String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request) {
    QuoteEffectiveBomConfirmationCandidate candidate =
        effectiveBomService.prepareConfirmation(oaNo, oaFormItemId);
    boolean alreadyConfirmed =
        confirmationService.hasActiveConfirmation(
            candidate.response().oaNo(),
            candidate.response().oaFormItemId(),
            candidate.response().topProductCode(),
            candidate.response().costPeriodMonth());
    if (alreadyConfirmed && !candidate.alreadyFrozen()) {
      throw failure(
          "EFFECTIVE_BOM_CONFIRM_CONFLICT",
          "当前产品已有有效确认，但月度最终BOM尚未冻结，拒绝覆盖历史结果");
    }

    if (!candidate.alreadyFrozen()) {
      releaseLegacyProvisional(candidate);
    }

    QuoteBomMonthlyFreezeResult frozen =
        monthlyFreezeService.freeze(
            freezeCommand(candidate, oaFormItemId));
    String buildBatchId = required(frozen.buildBatchId(), "月度冻结未返回最终构建编号");
    int replaceCount = replaceCount(candidate);

    if (alreadyConfirmed) {
      QuoteBomConfirmResponse confirmation =
          confirmationService.confirmEffective(
              oaNo, oaFormItemId, buildBatchId, replaceCount, request);
      return result(candidate, frozen, true, 0, confirmation);
    }

    QuoteBomCostingBuildResponse costing =
        costingBuildService.buildFromEffectiveBom(oaFormItemId, buildBatchId);
    requireSameBuild(buildBatchId, costing.buildBatchId(), "第2步结算行");
    QuoteBomConfirmResponse confirmation =
        confirmationService.confirmEffective(
            oaNo, oaFormItemId, buildBatchId, replaceCount, request);
    requireSameBuild(
        buildBatchId,
        confirmation.getCostingBuildBatchId(),
        "BOM确认记录");
    return result(
        candidate,
        frozen,
        false,
        costing.costingRowsWritten(),
        confirmation);
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse rebuildCostingFromEffective(
      String oaNo, Long oaFormItemId) {
    QuoteEffectiveBomConfirmationCandidate candidate =
        effectiveBomService.prepareConfirmation(oaNo, oaFormItemId);
    if (confirmationService.hasActiveConfirmation(
        candidate.response().oaNo(),
        candidate.response().oaFormItemId(),
        candidate.response().topProductCode(),
        candidate.response().costPeriodMonth())) {
      throw failure(
          "EFFECTIVE_BOM_ALREADY_CONFIRMED",
          "当前产品已经确认，不能重建覆盖结算行；如确需修改请先按既有流程撤销确认");
    }
    QuoteBomMonthlyFreezeResult staged = stageOrReuse(candidate, oaFormItemId);
    String buildBatchId = required(staged.buildBatchId(), "本次计价 BOM 未生成结果编号");
    QuoteBomCostingBuildResponse costing =
        costingBuildService.buildFromEffectiveBom(oaFormItemId, buildBatchId);
    requireSameBuild(
        buildBatchId, costing.buildBatchId(), "第2步结算行");
    return costing;
  }

  private QuoteBomMonthlyFreezeResult stageOrReuse(
      QuoteEffectiveBomConfirmationCandidate candidate, Long oaFormItemId) {
    if (candidate.alreadyFrozen()) {
      return monthlyFreezeService.freeze(freezeCommand(candidate, oaFormItemId));
    }
    releaseLegacyProvisional(candidate);
    return monthlyFreezeService.stage(freezeCommand(candidate, oaFormItemId));
  }

  private void releaseLegacyProvisional(
      QuoteEffectiveBomConfirmationCandidate candidate) {
    if (monthlyInheritanceService == null) {
      return;
    }
    monthlyInheritanceService.releaseProvisional(
        candidate.monthlyKey(),
        new QuoteBomAlternativeSelectionScope(
            candidate.response().oaNo(),
            candidate.response().oaFormItemId(),
            candidate.response().topProductCode(),
            candidate.response().costPeriodMonth(),
            candidate.response().priceOrgCode(),
            candidate.response().materialOrganizationCode()));
  }

  private QuoteBomMonthlyFreezeCommand freezeCommand(
      QuoteEffectiveBomConfirmationCandidate candidate, Long oaFormItemId) {
    return new QuoteBomMonthlyFreezeCommand(
        candidate.monthlyKey(),
        oaFormItemId,
        actorProvider.currentUserId(),
        candidate.alternativeSelectionIdByGroupKey(),
        candidate.candidateVariant());
  }

  private static QuoteEffectiveBomConfirmResponse result(
      QuoteEffectiveBomConfirmationCandidate candidate,
      QuoteBomMonthlyFreezeResult frozen,
      boolean reusedConfirmation,
      int costingRowCount,
      QuoteBomConfirmResponse confirmation) {
    return new QuoteEffectiveBomConfirmResponse(
        frozen.monthlySnapshotId(),
        frozen.buildBatchId(),
        frozen.reusedFrozenSnapshot(),
        reusedConfirmation,
        candidate.response().nodes().size(),
        reusedConfirmation && confirmation.getRowCount() != null
            ? confirmation.getRowCount()
            : costingRowCount,
        confirmation);
  }

  private static int replaceCount(
      QuoteEffectiveBomConfirmationCandidate candidate) {
    int count = 0;
    for (QuoteEffectiveBomAlternativeResponse selection
        : candidate.response().alternativeSelections()) {
      if ("ALTERNATIVE".equalsIgnoreCase(selection.selectedChildType())) {
        count++;
      }
    }
    return count;
  }

  private static void requireSameBuild(
      String expected, String actual, String target) {
    if (!required(expected, target + "期望构建编号")
        .equals(required(actual, target + "缺少构建编号"))) {
      throw failure(
          "EFFECTIVE_BOM_BUILD_MISMATCH",
          target + "没有引用本次最终有效BOM构建编号");
    }
  }

  private static String required(String value, String message) {
    if (!StringUtils.hasText(value)) {
      throw failure("EFFECTIVE_BOM_BUILD_MISSING", message);
    }
    return value.trim();
  }

  private static QuoteEffectiveBomQueryException failure(
      String code, String message) {
    return new QuoteEffectiveBomQueryException(code, message);
  }
}
