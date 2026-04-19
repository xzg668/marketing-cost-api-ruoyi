package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import java.util.List;

public interface CostRunCostItemService {
  List<CostRunCostItemDto> listByOaNo(String oaNo, String productCode);

  List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode);

  List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo, String productCode, java.util.Set<String> materialCodes);
}
