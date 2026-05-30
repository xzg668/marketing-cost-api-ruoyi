package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import java.time.LocalDateTime;

public interface MakePartPriceGenerationService {

  MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period);

  default MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period, LocalDateTime priceAsOfTime) {
    return generateByOa(oaNo, businessUnitType, period);
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
}
