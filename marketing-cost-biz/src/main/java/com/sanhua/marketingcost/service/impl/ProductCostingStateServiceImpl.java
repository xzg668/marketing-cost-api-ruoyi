package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductCostingStateServiceImpl implements ProductCostingStateService {

  private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final PricePrepareItemMapper priceItemMapper;
  private final QuoteCostingWorkspaceService workspaceService;
  private final QuoteCostingInputFingerprintService fingerprintService;

  public ProductCostingStateServiceImpl(
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      PricePrepareItemMapper priceItemMapper,
      QuoteCostingWorkspaceService workspaceService,
      QuoteCostingInputFingerprintService fingerprintService) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.priceItemMapper = priceItemMapper;
    this.workspaceService = workspaceService;
    this.fingerprintService = fingerprintService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String bindCurrentPriceFingerprint(
      String oaNo, Long oaFormItemId, String periodMonth, String prepareNo) {
    Scope scope = scope(oaNo, oaFormItemId, periodMonth);
    QuoteCostingWorkspace workspace = lockWorkspace(scope);
    String normalizedPrepareNo = required(prepareNo, "最终价格批次");
    if (!normalizedPrepareNo.equals(trimToNull(workspace.getCurrentPrepareNo()))) {
      throw new QuoteIngestException("最终价格批次已变化，请重新核算");
    }
    List<PricePrepareItem> prices =
        priceItemMapper.selectList(
            Wrappers.lambdaQuery(PricePrepareItem.class)
                .eq(PricePrepareItem::getPrepareNo, normalizedPrepareNo)
                .eq(PricePrepareItem::getOaFormItemId, oaFormItemId)
                .eq(PricePrepareItem::getCurrentFlag, 1)
                .orderByAsc(PricePrepareItem::getSettlementKey)
                .orderByAsc(PricePrepareItem::getId));
    if (prices == null || prices.isEmpty()) {
      throw new QuoteIngestException("当前最终价格没有完整明细，请重新生成");
    }
    List<QuoteCostingInputFingerprintService.PriceReference> references =
        prices.stream()
            .map(
                price ->
                    new QuoteCostingInputFingerprintService.PriceReference(
                        null,
                        price.getPriceType(),
                        price.getSourcePriceRecordId(),
                        price.getSupplierCode(),
                        price.getSupplyRatioRecordId()))
            .toList();
    List<String> evidence = prices.stream().map(this::priceEvidence).toList();
    OaFormItem item = scope.item();
    String fingerprint =
        fingerprintService.calculate(
            new QuoteCostingInputFingerprintService.Input(
                item.getId(),
                QuoteProductIdentityUtils.resolveCostingCode(item),
                periodMonth,
                item.getPackageMethod(),
                item.getPackageComponentCode(),
                item.getPackageQty() == null ? null : item.getPackageQty().toPlainString(),
                item.getProductAttr(),
                item.getBusinessType(),
                // 上一阶段输入指纹已包含 BOM来源、规则和替代料选择；将其作为本层稳定输入。
                workspace.getInputFingerprint(),
                workspace.getBomRuleFingerprint(),
                List.of(),
                references,
                evidence));
    int expectedVersion = valueOrZero(workspace.getLockVersion());
    workspace.setInputFingerprint(fingerprint);
    workspace.setLastErrorStep(null);
    workspace.setLastErrorCode(null);
    workspace.setLastErrorMessage(null);
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedVersion);
    return fingerprint;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void markBlocked(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String workspaceStatus,
      String currentStep,
      String errorCode,
      String message,
      int gapCount) {
    updateFailure(
        scope(oaNo, oaFormItemId, periodMonth),
        required(workspaceStatus, "工作区状态"),
        currentStep,
        errorCode,
        message,
        Math.max(1, gapCount));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public void markSystemFailed(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String currentStep,
      String errorCode,
      String message) {
    updateFailure(
        scope(oaNo, oaFormItemId, periodMonth),
        "SYSTEM_FAILED",
        currentStep,
        errorCode,
        message,
        0);
  }

  private void updateFailure(
      Scope scope,
      String workspaceStatus,
      String currentStep,
      String errorCode,
      String message,
      int gapCount) {
    QuoteCostingWorkspace workspace = lockWorkspace(scope);
    int expectedVersion = valueOrZero(workspace.getLockVersion());
    workspace.setWorkspaceStatus(workspaceStatus);
    workspace.setCurrentStep(required(currentStep, "失败步骤"));
    workspace.setGapCount(gapCount);
    workspace.setStaleReasonCode(trimToNull(errorCode));
    workspace.setLastErrorStep(currentStep);
    workspace.setLastErrorCode(trimToNull(errorCode));
    workspace.setLastErrorMessage(truncate(message));
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedVersion);
  }

  private QuoteCostingWorkspace lockWorkspace(Scope scope) {
    return workspaceService.lockOrCreate(
        scope.form().getOaNo(),
        scope.item().getId(),
        required(
            QuoteProductIdentityUtils.resolveCostingCode(scope.item()),
            "产品料号、三花型号或客户图号"),
        scope.periodMonth(),
        required(
            firstText(scope.item().getBusinessUnitType(), scope.form().getBusinessUnitType()),
            "业务单元"));
  }

  private Scope scope(String oaNo, Long oaFormItemId, String periodMonth) {
    String normalizedOaNo = required(oaNo, "OA单号");
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new QuoteIngestException("报价产品行 ID 必须大于0");
    }
    OaForm form =
        formMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, normalizedOaNo)
                .last("LIMIT 1"));
    OaFormItem item = itemMapper.selectById(oaFormItemId);
    if (form == null || item == null || !Objects.equals(form.getId(), item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单");
    }
    return new Scope(form, item, required(periodMonth, "核算月份"));
  }

  private String priceEvidence(PricePrepareItem price) {
    return String.join(
        "|",
        text(price.getSettlementKey()),
        text(price.getMaterialCode()),
        text(price.getQuantity()),
        text(price.getUnitPrice()),
        text(price.getResultRefType()),
        text(price.getResultRefId()),
        text(price.getSourcePriceBatchNo()),
        text(price.getSourceEffectiveFrom()),
        text(price.getSourceEffectiveTo()),
        text(price.getCarriedForward()));
  }

  private String truncate(String value) {
    String text = StringUtils.hasText(value) ? value.trim() : "未提供错误说明";
    return text.length() <= MAX_ERROR_MESSAGE_LENGTH
        ? text
        : text.substring(0, MAX_ERROR_MESSAGE_LENGTH);
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new QuoteIngestException(label + "不能为空");
    }
    return value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }

  private record Scope(OaForm form, OaFormItem item, String periodMonth) {}
}
