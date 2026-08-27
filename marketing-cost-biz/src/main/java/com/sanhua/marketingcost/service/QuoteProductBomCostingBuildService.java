package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
public interface QuoteProductBomCostingBuildService {
  /** 只从不可变最终有效BOM生成当前OA产品行的第2步结算行。 */
  QuoteBomCostingBuildResponse buildFromEffectiveBom(
      Long oaFormItemId, String effectiveBuildBatchId);
}
