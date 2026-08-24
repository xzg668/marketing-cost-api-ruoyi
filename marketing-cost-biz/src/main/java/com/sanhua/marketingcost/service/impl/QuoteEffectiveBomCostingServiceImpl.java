package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativeResponse;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.QuoteBomRuleFingerprintService;
import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomActorProvider;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomCostingCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceRequest;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteEffectiveBomCostingServiceImpl implements QuoteEffectiveBomCostingService {

  private final QuoteEffectiveBomApplicationService effectiveBomService;
  private final QuoteEffectiveBomPersistenceService persistenceService;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final QuoteEffectiveBomActorProvider actorProvider;
  private final QuoteBomRuleFingerprintService ruleFingerprintService;
  private final QuoteCostingInputFingerprintService inputFingerprintService;
  private final QuoteCostingWorkspaceService workspaceService;
  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;

  public QuoteEffectiveBomCostingServiceImpl(
      QuoteEffectiveBomApplicationService effectiveBomService,
      QuoteEffectiveBomPersistenceService persistenceService,
      QuoteProductBomCostingBuildService costingBuildService,
      QuoteEffectiveBomActorProvider actorProvider,
      QuoteBomRuleFingerprintService ruleFingerprintService,
      QuoteCostingInputFingerprintService inputFingerprintService,
      QuoteCostingWorkspaceService workspaceService,
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper) {
    this.effectiveBomService = effectiveBomService;
    this.persistenceService = persistenceService;
    this.costingBuildService = costingBuildService;
    this.actorProvider = actorProvider;
    this.ruleFingerprintService = ruleFingerprintService;
    this.inputFingerprintService = inputFingerprintService;
    this.workspaceService = workspaceService;
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse prepareCurrent(String oaNo, Long oaFormItemId) {
    OaFormItem item = requireItem(oaFormItemId);
    QuoteEffectiveBomCostingCandidate candidate =
        effectiveBomService.prepareCostingCandidate(oaNo, oaFormItemId);
    if (candidate == null
        || candidate.response() == null
        || candidate.candidateVariant() == null) {
      throw new QuoteIngestException("当前计价 BOM 尚未就绪");
    }
    String periodMonth = required(candidate.response().costPeriodMonth(), "核算月份");
    String productCode = required(candidate.response().topProductCode(), "产品料号");
    String businessUnit = resolveBusinessUnit(item);
    QuoteCostingWorkspace workspace =
        workspaceService.lockOrCreate(
            required(oaNo, "OA单号"),
            oaFormItemId,
            productCode,
            periodMonth,
            businessUnit);

    QuoteEffectiveBomPersistenceResult persisted =
        persistenceService.persistCurrentVariant(
            new QuoteEffectiveBomPersistenceRequest(
                candidate.response().monthlySnapshotId(),
                actorProvider.currentUserId(),
                candidate.alternativeSelectionIdByGroupKey(),
                candidate.candidateVariant()));
    String buildBatchId = required(persisted.buildBatchId(), "当前计价BOM构建编号");
    QuoteBomCostingBuildResponse build =
        costingBuildService.buildFromEffectiveBom(oaFormItemId, buildBatchId);
    if (build == null || !buildBatchId.equals(required(build.buildBatchId(), "报价物料构建编号"))) {
      throw new QuoteIngestException("报价物料明细未引用本次计价 BOM，事务已回滚");
    }

    String ruleFingerprint = ruleFingerprintService.currentFingerprint();
    String inputFingerprint =
        inputFingerprintService.calculate(
            new QuoteCostingInputFingerprintService.Input(
                oaFormItemId,
                productCode,
                periodMonth,
                item.getPackageMethod(),
                item.getPackageComponentCode(),
                item.getPackageQty() == null ? null : item.getPackageQty().toPlainString(),
                item.getProductAttr(),
                item.getBusinessType(),
                persisted.variantHash(),
                ruleFingerprint,
                alternativeFingerprints(candidate.response().alternativeSelections()),
                List.of(),
                List.of()));
    int expectedVersion = workspace.getLockVersion() == null ? 0 : workspace.getLockVersion();
    workspace.setWorkspaceStatus("BOM_READY");
    workspace.setCurrentStep("PRICE_TYPE_CONFIRMATION");
    workspace.setInputFingerprint(inputFingerprint);
    workspace.setBomSourceFingerprint(persisted.variantHash());
    workspace.setBomRuleFingerprint(ruleFingerprint);
    workspace.setCurrentBomBuildBatchId(buildBatchId);
    workspace.setCurrentPrepareNo(null);
    workspace.setGapCount(0);
    workspace.setCarriedForwardPriceCount(0);
    workspace.setStaleReasonCode(null);
    workspace.setLastErrorStep(null);
    workspace.setLastErrorCode(null);
    workspace.setLastErrorMessage(null);
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedVersion);
    return build;
  }

  private OaFormItem requireItem(Long oaFormItemId) {
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = itemMapper.selectById(oaFormItemId);
    if (item == null) {
      throw new QuoteIngestException("报价产品行不存在: " + oaFormItemId);
    }
    return item;
  }

  private String resolveBusinessUnit(OaFormItem item) {
    if (StringUtils.hasText(item.getBusinessUnitType())) {
      return item.getBusinessUnitType().trim();
    }
    OaForm form = item.getOaFormId() == null ? null : formMapper.selectById(item.getOaFormId());
    return required(form == null ? null : form.getBusinessUnitType(), "业务单元");
  }

  private List<String> alternativeFingerprints(
      List<QuoteEffectiveBomAlternativeResponse> selections) {
    return (selections == null ? List.<QuoteEffectiveBomAlternativeResponse>of() : selections)
        .stream()
        .map(
            selection ->
                String.join(
                    "|",
                    text(selection.alternativeGroupKey()),
                    text(selection.selectedMaterialCode()),
                    text(selection.selectionSource()),
                    text(selection.selectionVersion()),
                    text(selection.selectionId())))
        .toList();
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new QuoteIngestException(label + "不能为空");
    }
    return value.trim();
  }

  private String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }
}
