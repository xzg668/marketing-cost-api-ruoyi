package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.time.LocalDate;
import java.util.List;

public interface MakePartSourceDataService {
  List<BomCostingRow> listManufacturedParents(
      String oaNo, String businessUnitType, String buildBatchId);

  List<BomU9Source> listDedupedChildren(
      String parentMaterialNo, LocalDate asOfDate, String priceOrgCode);

  default List<BomU9Source> listDedupedChildren(String parentMaterialNo, LocalDate asOfDate) {
    throw new IllegalArgumentException("读取制造件 U9 直接子项必须显式传入 priceOrgCode");
  }
}
