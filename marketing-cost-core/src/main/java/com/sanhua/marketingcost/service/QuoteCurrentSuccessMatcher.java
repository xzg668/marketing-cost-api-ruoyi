package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** 单品与整单入口共用的“当前成功结果可直接复用”判定。 */
public final class QuoteCurrentSuccessMatcher {

  private QuoteCurrentSuccessMatcher() {}

  public static boolean matches(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      OaFormItem item,
      QuoteCostingWorkspace workspace,
      QuoteCostRunVersion version,
      String currentAlgorithmVersion) {
    if (workspace == null
        || item == null
        || version == null
        || !"SUCCESS".equals(workspace.getWorkspaceStatus())
        || workspace.getCurrentCostVersionId() == null
        || !StringUtils.hasText(workspace.getInputFingerprint())
        || !workspace.getInputFingerprint().equals(workspace.getLastSuccessInputFingerprint())
        || !Objects.equals(item.getConfirmedCostVersionId(), workspace.getCurrentCostVersionId())
        || !Objects.equals(version.getId(), workspace.getCurrentCostVersionId())
        || !QuoteCostRunStatus.isCurrentSuccess(version.getStatus())) {
      return false;
    }
    // BOM、价格等业务输入不随程序修复而变化；必须额外比较算法版本，避免新算法上线后仍复用旧成本。
    if (!StringUtils.hasText(currentAlgorithmVersion)
        || !Objects.equals(currentAlgorithmVersion.trim(), version.getAlgorithmVersion())) {
      return false;
    }
    return Objects.equals(oaNo, version.getOaNo())
        && Objects.equals(oaFormItemId, version.getOaFormItemId())
        && Objects.equals(periodMonth, version.getPricingMonth())
        && Objects.equals(version.getInputFingerprint(), workspace.getInputFingerprint());
  }
}
