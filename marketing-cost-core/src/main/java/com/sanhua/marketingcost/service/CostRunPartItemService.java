package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import java.time.LocalDate;
import java.util.List;
import java.util.function.IntConsumer;

public interface CostRunPartItemService {
  /** 兼容旧 caller（controller 直查），不上报进度 */
  default List<CostRunPartItemDto> listByOaNo(String oaNo) {
    return listByOaNo(oaNo, p -> {});
  }

  /**
   * T16：取价 + 上报进度。每完成 1 个部品 resolve 调 {@code progress.accept(0..100)}，
   * 调用方把 0..100 子百分比映射到全局进度区间（trial doRun 用 [5-60]）。
   */
  List<CostRunPartItemDto> listByOaNo(String oaNo, IntConsumer progress);

  default List<CostRunPartItemDto> listByOaNo(
      String oaNo, LocalDate quoteDate, IntConsumer progress) {
    return listByOaNo(oaNo, progress);
  }

  /**
   * 统一核算引擎入口。
   *
   * <p>persistDailyResult=true 时兼容老调用直接写 lp_cost_run_part_item；统一核算引擎传 false，
   * 只返回内存明细，最后交给场景 writer 在同一事务内幂等覆盖写入。
   */
  default List<CostRunPartItemDto> listByOaNo(
      String oaNo,
      LocalDate quoteDate,
      CostRunContext context,
      boolean persistDailyResult,
      IntConsumer progress) {
    return listByOaNo(oaNo, quoteDate, progress);
  }

  List<CostRunPartItemDto> listStoredByOaNo(String oaNo);

  /**
   * T26：见机表聚合视图 — 在 raw 部品列表基础上把焊料 N 行 + 包装 N 行各自合并成 1 行。
   *
   * <p>聚合规则：
   * <ul>
   *   <li>子件主档 cost_element='主要材料-焊料' → 合并成 1 行 partName=焊料，amount=Σ</li>
   *   <li>BOM 父件 main_category_name='包装组件' 的子件 → 合并成 1 行 partName=包装，
   *       amount=Σ × 1.05 ÷ 12（与 Excel 见机表 r45 一致）</li>
   *   <li>其他部品保持原样不变</li>
   * </ul>
   *
   * <p>DB 明细完整保留（{@code lp_cost_run_part_item} 不动），仅 API 返回合并视图。
   *
   * @param oaNo OA 单号
   * @param productCode 产品料号（聚合维度，必传）
   * @return 聚合后的部品列表，焊料和包装各 1 行，其他原样
   */
  List<CostRunPartItemDto> listAggregatedByOaNo(String oaNo, String productCode);
}
