package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;

public interface QuoteProductBomCostingBuildService {

  QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId);

  QuoteBomCostingBuildResponse buildByTask(Long taskId);
}
