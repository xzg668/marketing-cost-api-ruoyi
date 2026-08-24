package com.sanhua.marketingcost.service;

public class QuoteCostingWorkspaceOptimisticLockException extends IllegalStateException {
  public QuoteCostingWorkspaceOptimisticLockException(Long workspaceId) {
    super("核算工作区已被其他任务更新，请刷新后重试：" + workspaceId);
  }
}
