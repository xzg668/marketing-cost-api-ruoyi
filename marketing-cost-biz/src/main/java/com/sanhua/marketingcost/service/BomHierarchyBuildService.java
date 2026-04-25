package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import java.time.LocalDate;

/**
 * BOM 阶段 B：从 lp_bom_u9_source 单层父子数据构建出带 path / level / qty_per_top
 * 的层级事实层 lp_bom_raw_hierarchy。
 *
 * <p>关键语义（见 project memory "BOM 版本 append-only 多版本并存"）：
 * <ul>
 *   <li>永不 DELETE 历史：不同 effective_from 的版本必须并存</li>
 *   <li>UK 命中走 {@code ON DUPLICATE KEY UPDATE} 刷新可变字段</li>
 *   <li>环检测：BOM 有环则对应顶层进 failedProducts，不阻塞其他产品</li>
 * </ul>
 */
public interface BomHierarchyBuildService {

  /**
   * 按请求构建层级。
   *
   * <p>mode=ALL 时遍历该 importBatch 的全部顶层；mode=BY_PRODUCT 时只构建指定顶层。
   *
   * @param request 参见 {@link BuildHierarchyRequest}
   * @return 构建批次 ID + 统计 + failedProducts
   */
  BuildHierarchyResult build(BuildHierarchyRequest request);

  /**
   * 按顶层料号查嵌套树（供 T6 前端树查看器）。
   *
   * @param topProductCode 顶层产品料号
   * @param bomPurpose 可选，按 bomPurpose 过滤
   * @param asOfDate 可选，按 effective_from ≤ d ≤ effective_to（或 effective_to IS NULL）过滤；null=当前日期
   * @param sourceType 可选，默认 U9
   * @return 嵌套树；顶层未找到返回 null
   */
  BomHierarchyTreeDto getHierarchyTree(
      String topProductCode, String bomPurpose, LocalDate asOfDate, String sourceType);
}
