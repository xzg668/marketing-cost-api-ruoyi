package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.time.LocalDateTime;
import java.util.List;

public interface MakePartPriceGenerationService {

  MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period);

  default MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period, LocalDateTime priceAsOfTime) {
    return generateByOa(oaNo, businessUnitType, period);
  }

  default MakePartPriceGenerateResponse generateByOa(
      String oaNo,
      String businessUnitType,
      String period,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext) {
    return generateByOa(oaNo, businessUnitType, period, priceAsOfTime);
  }

  MakePartPriceGenerateResponse generateByMaterial(
      String parentMaterialNo, String businessUnitType, String period);

  default MakePartPriceGenerateResponse generateByMaterial(
      String parentMaterialNo, String businessUnitType, String period, LocalDateTime priceAsOfTime) {
    return generateByMaterial(parentMaterialNo, businessUnitType, period);
  }

  MakePartPriceGenerateResponse generateAllLatest(String businessUnitType, String period);

  default MakePartPriceGenerateResponse generateAllLatest(
      String businessUnitType, String period, LocalDateTime priceAsOfTime) {
    return generateAllLatest(businessUnitType, period);
  }

  String findLatestBatchId(String oaNo, String businessUnitType, String parentMaterialNo);

  /**
   * 只读取制造件结构、废料映射和无废料确认，不生成价格、不写计算批次或缺口表。
   */
  List<MakePartPriceCalcRow> previewStructureByOa(
      String oaNo, String businessUnitType, String period);

  /** 完整计算自制件价格但不写计算行、缺口或联动价结果。 */
  List<MakePartPriceCalcRow> calculateRowsByOa(
      String oaNo,
      String businessUnitType,
      String period,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext);
}
