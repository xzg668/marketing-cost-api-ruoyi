package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorAdjustBatchDetailDto;
import com.sanhua.marketingcost.dto.FactorAdjustBatchPageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest;
import com.sanhua.marketingcost.dto.FactorAdjustPricePageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListPageResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest;

public interface FactorAdjustQueryService {

  FactorAdjustBatchPageResponse pageBatches(FactorAdjustBatchQueryRequest request);

  FactorAdjustBatchDetailDto getBatchDetail(Long adjustBatchId);

  FactorAdjustPricePageResponse pagePrices(FactorAdjustPriceQueryRequest request);

  FactorMonthlyPriceListPageResponse pageMonthlyPrices(FactorMonthlyPriceListQueryRequest request);
}
