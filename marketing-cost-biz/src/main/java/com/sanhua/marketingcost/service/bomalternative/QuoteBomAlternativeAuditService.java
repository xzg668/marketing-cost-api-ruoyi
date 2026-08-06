package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;

/** 把一次有效的标准/替代切换写入统一系统操作日志。 */
public interface QuoteBomAlternativeAuditService {

  void recordSelectionChange(
      QuoteBomAlternativeSelectionScope scope,
      String groupKey,
      QuoteBomAlternativeSelection before,
      QuoteBomAlternativeSelectionResult after,
      String operator,
      String selectionRemark);
}
