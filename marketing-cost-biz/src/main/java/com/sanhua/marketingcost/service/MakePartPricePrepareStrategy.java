package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;

/** 自制件价格准备策略：只消费制造件价格生成结果，禁止回退旧自制件人工维护价。 */
public interface MakePartPricePrepareStrategy {

  MakePartPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      PricePreparePlanItem planItem);
}
