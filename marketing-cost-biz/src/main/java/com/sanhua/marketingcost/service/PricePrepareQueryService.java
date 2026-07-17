package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import java.util.List;

public interface PricePrepareQueryService {

  PricePrepareBatchPageResponse pageBatches(PricePrepareBatchQueryRequest request);

  PricePrepareOaSummaryPageResponse pageOaSummaries(PricePrepareOaSummaryQueryRequest request);

  PricePrepareTopProductSummaryPageResponse pageTopProductSummaries(
      PricePrepareTopProductSummaryQueryRequest request);

  PricePrepareCandidatePageResponse pageCandidates(PricePrepareCandidateQueryRequest request);

  PricePrepareItemPageResponse pageItems(PricePrepareItemQueryRequest request);

  PricePrepareGapPageResponse pageGaps(PricePrepareGapQueryRequest request);

  /** 为数据库缺口或纯计算缺口补齐价格类型、无废料确认等只读展示信息。 */
  void enrichGaps(List<PricePrepareGap> gaps);
}
