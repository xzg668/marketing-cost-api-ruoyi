package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuoteCostingWorkspaceService {

  Optional<QuoteCostingWorkspace> find(Long oaFormItemId, String periodMonth);

  List<QuoteCostingWorkspace> findAll(
      Collection<Long> oaFormItemIds,
      String periodMonth);

  QuoteCostingWorkspace getOrCreate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String businessUnitType);

  /** 创建后锁定唯一工作区，调用方必须处于事务中。 */
  QuoteCostingWorkspace lockOrCreate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String businessUnitType);

  QuoteCostingWorkspace update(
      QuoteCostingWorkspace workspace,
      int expectedLockVersion);

  int markItemStale(Long oaFormItemId, String periodMonth, String reasonCode);

  int markBomRuleWorkspacesStale(String businessUnitType, String reasonCode);
}
