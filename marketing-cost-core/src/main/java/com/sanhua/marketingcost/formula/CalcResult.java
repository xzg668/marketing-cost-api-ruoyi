package com.sanhua.marketingcost.formula;

import java.math.BigDecimal;

/**
 * 公式引擎计算结果 —— 单价 + 审计 trace。
 *
 * <p>调用方读 unitPrice 写入 lp_cost_run_part_item.unit_price；
 * trace 写入审计/日志便于财务对账（建议落表 lp_formula_calc_log，本期先打日志）。
 */
public record CalcResult(BigDecimal unitPrice, CalcTrace trace) {
}
