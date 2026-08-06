package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;

/** QEB-12单产品最终BOM确认和第2步生成编排。 */
public interface QuoteEffectiveBomConfirmationService {

  /** 采用当前计价 BOM 并生成第 2 步明细，但不替财务确认第 2 步。 */
  QuoteBomCostingBuildResponse prepareCostingBom(
      String oaNo, Long oaFormItemId);

  QuoteEffectiveBomConfirmResponse confirm(
      String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request);

  QuoteBomCostingBuildResponse rebuildCostingFromEffective(
      String oaNo, Long oaFormItemId);
}
