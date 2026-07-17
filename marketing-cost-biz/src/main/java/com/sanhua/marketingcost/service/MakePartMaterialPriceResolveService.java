package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface MakePartMaterialPriceResolveService {

  MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      String oaNo,
      String businessUnitType);

  default MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType) {
    return resolveMaterialUnitPrice(materialCode, period, quoteDate, oaNo, businessUnitType);
  }

  default MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    return resolveMaterialUnitPrice(
        materialCode, period, quoteDate, priceAsOfTime, oaNo, businessUnitType);
  }

  /** 联动价也只在内存计算的取价入口。 */
  default MakePartMaterialPriceResolveResult calculateMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    return resolveMaterialUnitPrice(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        scenarioContext);
  }
}
