package com.sanhua.marketingcost.service.bomalternative;

import java.time.LocalDate;

/** 报价物料确认前同步当前替代组，并返回当前有效人工替代组数量。 */
public interface QuoteBomAlternativeConfirmationGuard {

  int validateAndCountManualAlternatives(
      QuoteBomAlternativeSelectionScope scope,
      LocalDate quoteDate,
      String bomPurpose);
}
