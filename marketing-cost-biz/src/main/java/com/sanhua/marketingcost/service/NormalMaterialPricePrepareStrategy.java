package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;

/** 普通料号价格准备策略：只消费现有价格类型路由和 Resolver，不生成包装组件或自制件结果。 */
public interface NormalMaterialPricePrepareStrategy {

  NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      PricePreparePlanItem planItem);
}
