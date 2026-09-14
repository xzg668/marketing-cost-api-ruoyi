package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteCurrentSuccessMatcher;
import java.util.Optional;
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
    String prepareNo = StringUtils.hasText(version.getOaPricePrepareNo())
        ? version.getOaPricePrepareNo() : workspace.getCurrentPrepareNo();
    int warnings = workspace.getCarriedForwardPriceCount() == null
        ? 0 : workspace.getCarriedForwardPriceCount();
    return Optional.of(new ReusableCost(version, prepareNo, warnings));
  }

  public record ReusableCost(QuoteCostRunVersion version, String prepareNo, int warningCount) {}
}
