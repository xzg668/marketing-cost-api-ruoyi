package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import java.time.LocalDateTime;

/** 普通料号价格准备策略：消费本次月价会话，保留每个 BOM 结算项的数量与来源。 */
public interface NormalMaterialPricePrepareStrategy {

  /** 同批普通料号批量读取月价并记录实际使用；正式采用由价格准备应用操作持有。 */
  default java.util.Map<PricePreparePlanItem, NormalMaterialPricePrepareResult> prepareBatch(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      java.util.List<PricePreparePlanItem> items,
      boolean persist) {
    var results =
        new java.util.LinkedHashMap<PricePreparePlanItem, NormalMaterialPricePrepareResult>();
    for (var item : items) {
      var scenario = scenarioContext.forOrganization(item.getBomRow().getPriceOrgCode());
      results.put(
          item,
          persist
              ? prepare(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenario, item)
              : calculate(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenario, item));
    }
    return results;
  }

  NormalMaterialPricePrepareResult prepare(
      String oaNo, String businessUnitType, String periodMonth, PricePreparePlanItem planItem);

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

  /** 与正式准备消费同一月价口径，不首次采用或保存准备明细。 */
  default NormalMaterialPricePrepareResult calculate(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenarioContext, planItem);
  }
}
