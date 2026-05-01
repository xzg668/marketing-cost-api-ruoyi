package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public interface CostRunCostItemService {
  List<CostRunCostItemDto> listByOaNo(String oaNo, String productCode);

  List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode);

  /** 兼容旧 caller，不上报进度 */
  default List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo, String productCode, Set<String> materialCodes) {
    return listByMaterialCodes(oaNo, productCode, materialCodes, p -> {});
  }

  /**
   * T16：算费用 + 上报进度。calculateItems 是原子 calculation，进度按几个里程碑（人工/损耗/
   * 制造/三项/部门/aux/other_exp）报。调用方把 0..100 映射到全局区间（trial 用 [60-95]/N）。
   */
  List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo, String productCode, Set<String> materialCodes, IntConsumer progress);
}
