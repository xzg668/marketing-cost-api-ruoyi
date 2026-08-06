package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;

/** 将已冻结客户月度场景的标准/替代结果继承到当前OA产品行。 */
public interface QuoteBomAlternativeMonthlyInheritanceService {

  QuoteBomAlternativeMonthlyInheritanceResult inheritIfFrozen(
      QuoteBomMonthlyFreezeKey monthlyKey,
      QuoteBomAlternativeSelectionScope targetScope);

  /** 第2步尚未确认时撤回临时冻结结果，恢复为可继续调整的当前草稿。 */
  boolean releaseProvisional(
      QuoteBomMonthlyFreezeKey monthlyKey,
      QuoteBomAlternativeSelectionScope targetScope);
}
