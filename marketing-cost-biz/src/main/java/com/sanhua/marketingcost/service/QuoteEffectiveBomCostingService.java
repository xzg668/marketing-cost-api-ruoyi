package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;

/** 自动生成单个报价产品当前计价 BOM 与结算行。 */
public interface QuoteEffectiveBomCostingService {

  QuoteBomCostingBuildResponse prepareCurrent(String oaNo, Long oaFormItemId);
}
