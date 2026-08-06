package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;

/** 类型 2 影响因素身份、月度价格和来源行的确认写入。 */
public interface PriceLinkedType2FactorMonthlyUpsertService {

  default FactorMonthlyPriceUpsertResult upsert(
      PriceLinkedType2WorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId) {
    return upsert(
        parseResult,
        priceMonth,
        businessUnitType,
        operator,
        sourceUploadBatchId,
        FactorPriceConflictStrategy.KEEP_EXISTING.getCode());
  }

  FactorMonthlyPriceUpsertResult upsert(
      PriceLinkedType2WorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId,
      String factorPriceConflictStrategy);
}
