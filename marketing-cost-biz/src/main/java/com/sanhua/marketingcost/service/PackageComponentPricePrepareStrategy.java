package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import java.time.LocalDateTime;

/** 包装组件价格准备策略：必须带顶层产品上下文，不能回退普通父料号取价。 */
public interface PackageComponentPricePrepareStrategy {

  PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem);

  default PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem) {
    return prepare(prepareNo, oaNo, periodMonth, bomPurpose, sourceType, planItem);
  }

  /** 只读组装包装结构并计算子件价格，不写包装快照、价格明细或缺口。 */
  default PackageComponentPricePrepareResult calculate(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem) {
    return prepare(
        prepareNo, oaNo, periodMonth, priceAsOfTime, bomPurpose, sourceType, planItem);
  }
}
