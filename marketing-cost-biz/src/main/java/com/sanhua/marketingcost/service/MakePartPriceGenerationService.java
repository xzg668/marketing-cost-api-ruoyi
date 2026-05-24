package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;

public interface MakePartPriceGenerationService {

  MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period);

  MakePartPriceGenerateResponse generateByMaterial(
      String parentMaterialNo, String businessUnitType, String period);

  MakePartPriceGenerateResponse generateAllLatest(String businessUnitType, String period);

  String findLatestBatchId(String oaNo, String businessUnitType, String parentMaterialNo);
}
