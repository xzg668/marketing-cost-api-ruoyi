package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import java.time.LocalDate;

public interface QuoteProductBomCostingBuildService {

  QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId);

  QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId, String periodMonth);

  QuoteBomCostingBuildResponse buildByOaFormItem(
      Long oaFormItemId, String periodMonth, LocalDate quoteDate);

  QuoteBomCostingBuildResponse buildByTask(Long taskId);

  /** 只从不可变最终有效BOM生成当前OA产品行的第2步结算行。 */
  QuoteBomCostingBuildResponse buildFromEffectiveBom(
      Long oaFormItemId, String effectiveBuildBatchId);
}
