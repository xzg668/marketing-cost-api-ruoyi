package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.math.BigDecimal;

public interface QuoteCostRunVersionService {

  QuoteCostRunVersion createTrial(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String pricingMonth,
      String resultPeriod,
      String pricePrepareNo,
      String priceTypeConfirmNo,
      String bomConfirmNo,
      String businessUnitType);

  void finishTrial(
      Long versionId,
      BigDecimal totalCost,
      int partItemCount,
      int costItemCount);
}
