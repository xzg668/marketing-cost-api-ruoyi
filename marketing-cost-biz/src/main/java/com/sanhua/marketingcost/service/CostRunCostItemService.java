package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public interface CostRunCostItemService {
  List<CostRunCostItemDto> listByOaNo(String oaNo, String productCode);

  List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode);

  /**
   * T24：带 category 过滤的查询。
   * <ul>
   *   <li>category="EXPENSE" — 只拉传统费用项（旧行为）</li>
   *   <li>category="BOM_BUCKET" — 只拉见机表汇总行（焊料/包装等）</li>
   *   <li>category=null/空 — 拉全量</li>
   * </ul>
   */
  List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode, String category);

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
