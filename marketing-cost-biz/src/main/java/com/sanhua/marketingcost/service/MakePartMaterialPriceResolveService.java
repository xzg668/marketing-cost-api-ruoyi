package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 原材料和废料统一取价，调用方必须传入包含取价组织的场景。 */
public interface MakePartMaterialPriceResolveService {
  enum Purpose {
    MATERIAL,
    SCRAP,
    PACKAGE
  }

  /** 预览不写价格及优先级配置。 */
  MakePartMaterialPriceResolveResult calculateMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext,
      Purpose purpose);
}
