package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.time.LocalDate;

public interface MakePartWeightService {
  MakePartWeightResult resolveWeights(
      String parentMaterialNo,
      BomU9Source child,
      String itemProcessType,
      String periodMonth,
      LocalDate asOfDate,
      String businessUnitType,
      boolean requireChildNetWeight);
}
