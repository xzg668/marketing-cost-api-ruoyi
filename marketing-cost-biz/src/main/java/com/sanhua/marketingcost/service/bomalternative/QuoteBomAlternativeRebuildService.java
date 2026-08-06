package com.sanhua.marketingcost.service.bomalternative;

/** 标准/替代选择、报价物料重建和后续状态失效的原子事务入口。 */
public interface QuoteBomAlternativeRebuildService {

  QuoteBomAlternativeRebuildResult rebuild(
      QuoteBomAlternativeRebuildCommand command);
}
