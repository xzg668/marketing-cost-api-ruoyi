package com.sanhua.marketingcost.service.bomalternative;

/** 统一使当前报价产品的价格类型、价格准备和成本版本失效。 */
public interface QuoteBomAlternativeWorkflowInvalidationService {

  QuoteBomAlternativeWorkflowInvalidationResult invalidate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth);
}
