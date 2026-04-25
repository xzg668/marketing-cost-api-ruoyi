package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;

/**
 * BOM 阶段 C：按 asOfDate 取 raw_hierarchy 版本快照 + 应用 bom_stop_drill_rule 过滤规则
 * → 写入 lp_bom_costing_row。
 *
 * <p>职责边界（见 project memory "接管子树算法在取价阶段做"）：
 * <ul>
 *   <li>本服务只做"拍平 + 打标"：判定 is_costing_row / subtree_cost_required / matched_drill_rule_id</li>
 *   <li>不算接管子树的实际价格（那是取价阶段第 7 桶 SUBTREE_COMPOSITE Resolver 的事）</li>
 *   <li>不动 raw_hierarchy 数据</li>
 * </ul>
 */
public interface BomFlattenService {

  /**
   * 按请求拍平。
   *
   * <p>mode = BY_OA / BY_PRODUCT / ALL；asOfDate 必填。
   *
   * @return 拍平批次结果（rowsWritten / subtreeRequiredCount / warnings）
   */
  FlattenResult flatten(FlattenRequest request);
}
