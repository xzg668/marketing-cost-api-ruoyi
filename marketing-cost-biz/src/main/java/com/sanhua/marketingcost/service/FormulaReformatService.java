package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FormulaReformatResponse;

/**
 * 历史脏数据回洗服务 —— Plan B T7。
 *
 * <p>定位：一次性（幂等）工具，把 {@code lp_price_linked_item.formula_expr} 里
 * 残留的中英文混排公式统一跑过 {@code FormulaNormalizer} 回写成 {@code [code]} 形态。
 * 已规范化的行 normalize 后字符串不变，所以重跑安全。
 */
public interface FormulaReformatService {

  /**
   * 扫表并回写。失败行不落库，明细见 {@link FormulaReformatResponse#getFailed()}。
   */
  FormulaReformatResponse reformatAll();
}
