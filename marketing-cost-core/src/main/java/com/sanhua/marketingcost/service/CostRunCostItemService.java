package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
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

  /**
   * 统一核算引擎入口。
   *
   * <p>persistDailyResult=true 时兼容老调用直接写 lp_cost_run_cost_item；统一核算引擎传 false，
   * 使用本次内存部品明细计算费用，最后交给场景 writer 在同一事务内幂等覆盖写入。
   */
  default List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo,
      String productCode,
      Set<String> materialCodes,
      List<CostRunPartItemDto> currentPartItems,
      boolean persistDailyResult,
      IntConsumer progress) {
    return listByMaterialCodes(oaNo, productCode, materialCodes, progress);
  }

  default List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo,
      String productCode,
      Set<String> materialCodes,
      CostRunContext context,
      List<CostRunPartItemDto> currentPartItems,
      boolean persistDailyResult,
      IntConsumer progress) {
    return listByMaterialCodes(
        oaNo, productCode, materialCodes, currentPartItems, persistDailyResult, progress);
  }
}
