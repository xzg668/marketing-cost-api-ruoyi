package com.sanhua.marketingcost.enums;

/**
 * T24：lp_cost_run_cost_item.category 取值常量。
 *
 * <p>区分两类语义，避免成本分析时把"展示视图"行错误地累加到总成本：
 * <ul>
 *   <li>{@link #EXPENSE} — 传统费用项（14 个 cost_code），参与 totalAmount 累加链</li>
 *   <li>{@link #BOM_BUCKET} — 见机表原材料汇总（焊料 / 包装等），仅展示，不参与累加</li>
 * </ul>
 *
 * <p>设计文档：docs/cost-bucket-aggregation-20260501-design.md
 */
public final class CostItemCategory {
  /** 传统费用项：参与 totalAmount 累加（旧数据 default） */
  public static final String EXPENSE = "EXPENSE";
  /** 见机表汇总视图：仅展示，不参与累加 */
  public static final String BOM_BUCKET = "BOM_BUCKET";

  private CostItemCategory() {}
}
