package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;

/** 包装组件价格准备策略：必须带顶层产品上下文，不能回退普通父料号取价。 */
public interface PackageComponentPricePrepareStrategy {

  PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem);
}
