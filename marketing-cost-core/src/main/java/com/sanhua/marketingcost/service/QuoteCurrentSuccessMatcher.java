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
      String currentAlgorithmVersion,
      String currentSourceRevision) {
    if (!matchesSaved(oaNo, oaFormItemId, periodMonth, item, workspace, version)) return false;
    if (!StringUtils.hasText(currentSourceRevision)
        || !Objects.equals(currentSourceRevision, version.getSourceRevision())) return false;
    return StringUtils.hasText(currentAlgorithmVersion)
        && Objects.equals(currentAlgorithmVersion.trim(), version.getAlgorithmVersion());
  }

  /** 提交已完成的保存结果；公共价格、费率或新算法只在主动重新核算时参与取价。 */
  public static boolean matchesSaved(
      String oaNo, Long oaFormItemId, String periodMonth, OaFormItem item,
      QuoteCostingWorkspace workspace, QuoteCostRunVersion version) {
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
    if (!StringUtils.hasText(version.getSourceRevision())
        || !Objects.equals(version.getSourceRevision(), workspace.getSourceRevision())
        || !Objects.equals(version.getSourceRevision(), workspace.getLastSuccessSourceRevision())) {
      return false;
    }
    return Objects.equals(oaNo, version.getOaNo())
        && Objects.equals(oaFormItemId, version.getOaFormItemId())
        && Objects.equals(periodMonth, version.getPricingMonth())
        && Objects.equals(version.getInputFingerprint(), workspace.getInputFingerprint());
  }
}
