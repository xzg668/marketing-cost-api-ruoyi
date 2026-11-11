package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteCurrentSuccessMatcher;
import java.util.Optional;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 单品执行前与并发执行后均按完整输入修订查找可复用版本。 */
@Component
public class ProductCostingSuccessLookup {
  private final QuoteCostingWorkspaceService workspaceService;
  private final OaFormItemMapper itemMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final CostingAlgorithmVersionProvider algorithmVersionProvider;

  public ProductCostingSuccessLookup(QuoteCostingWorkspaceService workspaceService,
      OaFormItemMapper itemMapper, QuoteCostRunVersionMapper versionMapper,
      CostingAlgorithmVersionProvider algorithmVersionProvider) {
    this.workspaceService = workspaceService;
    this.itemMapper = itemMapper;
    this.versionMapper = versionMapper;
    this.algorithmVersionProvider = algorithmVersionProvider;
  }

  public boolean hasCurrentVersion(ProductCostingContext context) {
    return workspaceService.find(context.itemId(), context.periodMonth())
        .map(workspace -> workspace.getCurrentCostVersionId() != null).orElse(false);
  }

  /** 已完成成本用于展示和提交，不重新读取公共来源来否定保存结果。 */
  public Optional<ReusableCost> findSaved(ProductCostingContext context) {
    var workspace = workspaceService.find(context.itemId(), context.periodMonth()).orElse(null);
    if (workspace == null || workspace.getCurrentCostVersionId() == null) return Optional.empty();
    var version = versionMapper.selectById(workspace.getCurrentCostVersionId());
    if (!QuoteCurrentSuccessMatcher.matchesSaved(context.oaNo(), context.itemId(), context.periodMonth(),
        context.item(), workspace, version)) return Optional.empty();
    return Optional.of(reusable(version, workspace));
  }

  /** 仅展示产品上次确认的成本；本次重算未完成时仍可读，不用于复用或提交。 */
  public Optional<QuoteCostRunVersion> findConfirmedForDisplay(OaForm form, OaFormItem item) {
    if (form == null || item == null || item.getConfirmedCostVersionId() == null
        || !Objects.equals(form.getId(), item.getOaFormId())
        || !StringUtils.hasText(form.getBusinessUnitType())) return Optional.empty();
    var version = versionMapper.selectById(item.getConfirmedCostVersionId());
    if (version == null || !Objects.equals(item.getConfirmedCostVersionId(), version.getId())
        || !Objects.equals(form.getOaNo(), version.getOaNo())
        || !Objects.equals(item.getId(), version.getOaFormItemId())
        || !Objects.equals(form.getBusinessUnitType(), version.getBusinessUnitType())
        || !QuoteCostRunStatus.isCurrentSuccess(version.getStatus())
        || version.getFinalQuoteAmount() == null || version.getFinalQuoteAmount().signum() < 0) {
      return Optional.empty();
    }
    return Optional.of(version);
  }

  public Optional<ReusableCost> find(ProductCostingContext context) {
    if (!StringUtils.hasText(context.sourceRevision())) return Optional.empty();
    var workspace = workspaceService.find(context.itemId(), context.periodMonth()).orElse(null);
    if (workspace == null || workspace.getCurrentCostVersionId() == null) return Optional.empty();
    var item = itemMapper.selectById(context.itemId());
    var version = versionMapper.selectById(workspace.getCurrentCostVersionId());
    if (!QuoteCurrentSuccessMatcher.matches(context.oaNo(), context.itemId(), context.periodMonth(),
        item, workspace, version, algorithmVersionProvider.currentVersion(), context.sourceRevision())) {
      return Optional.empty();
    }
    return Optional.of(reusable(version, workspace));
  }

  private ReusableCost reusable(QuoteCostRunVersion version,
      com.sanhua.marketingcost.entity.QuoteCostingWorkspace workspace) {
    String prepareNo = StringUtils.hasText(version.getOaPricePrepareNo())
        ? version.getOaPricePrepareNo() : workspace.getCurrentPrepareNo();
    int warnings = workspace.getCarriedForwardPriceCount() == null
        ? 0 : workspace.getCarriedForwardPriceCount();
    return new ReusableCost(version, prepareNo, warnings);
  }

  public record ReusableCost(QuoteCostRunVersion version, String prepareNo, int warningCount) {}
}
