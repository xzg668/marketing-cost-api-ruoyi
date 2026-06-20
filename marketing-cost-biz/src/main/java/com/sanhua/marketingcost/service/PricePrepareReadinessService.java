package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;

public interface PricePrepareReadinessService {

  PricePrepareReadinessResult check(String oaNo, String periodMonth);

  PricePrepareReadinessResult check(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth);

  PricePrepareReadinessResult check(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth,
      String priceTypeConfirmNo);
}
