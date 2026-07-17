package com.sanhua.marketingcost.service;

import java.util.Collection;

/** 单产品报价成本试算版本失效服务，只允许把未确认 TRIAL 标记为 STALE。 */
public interface QuoteCostRunVersionInvalidationService {

  int invalidateByFinanceCu(String pricingMonth, String businessUnitType);

  int invalidateByOaCu(String oaNo);

  int invalidateProduct(
      String oaNo, Long oaFormItemId, String productCode, String pricingMonth);

  int invalidateByPriceTypeConfirmNos(Collection<String> confirmNos);
}
