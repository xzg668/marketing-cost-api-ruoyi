package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import java.time.LocalDateTime;

/** 普通料号价格准备策略：只消费现有价格类型路由和 Resolver，不生成包装组件或自制件结果。 */
public interface NormalMaterialPricePrepareStrategy {

  NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      PricePreparePlanItem planItem);

  default NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, planItem);
  }

  default NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, priceAsOfTime, planItem);
  }

  /** 与正式准备同口径取价，但联动价只在内存计算，不生成联动价结果行。 */
  default NormalMaterialPricePrepareResult calculate(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return prepare(
        oaNo, businessUnitType, periodMonth, priceAsOfTime, scenarioContext, planItem);
  }
}
