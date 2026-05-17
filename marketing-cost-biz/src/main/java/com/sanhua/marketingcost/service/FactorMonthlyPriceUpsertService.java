package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;

public interface FactorMonthlyPriceUpsertService {
  FactorMonthlyPriceUpsertResult upsert(
      FactorWorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId);

  default FactorMonthlyPriceUpsertResult upsert(
      FactorWorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId,
      String effectiveStrategy) {
    return upsert(parseResult, priceMonth, businessUnitType, operator, sourceUploadBatchId);
  }
}
