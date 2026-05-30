package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingProductPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageStructurePageResponse;

public interface QuoteBomDetailQueryService {

  QuoteBomPackageStructurePageResponse pagePackageStructures(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String packageParentCode,
      String periodMonth,
      Integer page,
      Integer pageSize);

  QuoteBomCostingRowPageResponse pageCostingRows(
      String oaNo,
      String topProductCode,
      String materialCode,
      String periodMonth,
      Integer page,
      Integer pageSize);

  QuoteBomCostingProductPageResponse pageCostingProducts(
      String oaNo,
      String topProductCode,
      String materialCode,
      String periodMonth,
      Integer page,
      Integer pageSize);
}
