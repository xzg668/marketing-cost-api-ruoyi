package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.time.LocalDate;
import java.util.List;

public interface MakePartSourceDataService {
  List<BomCostingRow> listManufacturedParents(
      String oaNo, String businessUnitType, String buildBatchId);

  List<BomU9Source> listDedupedChildren(String parentMaterialNo, LocalDate asOfDate);
}
