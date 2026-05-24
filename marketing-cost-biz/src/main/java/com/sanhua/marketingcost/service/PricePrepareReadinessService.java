package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;

public interface PricePrepareReadinessService {

  PricePrepareReadinessResult check(String oaNo, String periodMonth);
}
