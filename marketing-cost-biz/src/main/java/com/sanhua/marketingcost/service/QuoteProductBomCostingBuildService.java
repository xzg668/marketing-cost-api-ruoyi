package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import java.time.LocalDate;

public interface QuoteProductBomCostingBuildService {

  QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId);

  QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId, String periodMonth);

  QuoteBomCostingBuildResponse buildByOaFormItem(
      Long oaFormItemId, String periodMonth, LocalDate quoteDate);

  QuoteBomCostingBuildResponse buildByTask(Long taskId);
}
