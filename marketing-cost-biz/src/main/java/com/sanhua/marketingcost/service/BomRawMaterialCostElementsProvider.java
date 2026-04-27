package com.sanhua.marketingcost.service;

import java.util.Set;

/**
 * T11 增强 · BOM 原材料成本要素白名单字典查询服务。
 *
 * <p>从 sys_dict_data（dict_type=bom_raw_material_cost_elements，status=0，deleted=0）
 * 拉值集，作为 LEAF_ROLLUP_TO_PARENT 命中的<b>前置硬条件</b>：叶子的 cost_element_code
 * 必须在此白名单内，才允许进入字典 bom_leaf_rollup_codes 的料号 / 名称匹配。
 *
 * <p>例：拉制铜管叶子 cost_element_code='No101'（U9 标准"主要材料-原材料"）→ 在白名单 → 允许上卷；
 * 一个名字凑巧含"拉制铜管"但 cost_element_code='No104'（包装材料）的节点 → 不在白名单 → 不上卷。
 *
 * <p>设计要点：
 * <ul>
 *   <li>带 cache：BOM 拍平一次循环数千节点，每节点都查字典撑不住</li>
 *   <li>未来扩展：业务在 /system/dict 加新 cost_element_code 即可，不改代码</li>
 * </ul>
 */
public interface BomRawMaterialCostElementsProvider {

  /**
   * 拉所有"原材料" cost_element_code（启用条目）。
   *
   * @return 不可空集合（字典空时返回 {@link java.util.Collections#emptySet()}）
   */
  Set<String> getCostElementCodes();

  /**
   * 判定一个叶子节点的 cost_element_code 是否在原材料白名单。
   *
   * @param costElementCode 叶子的 cost_element_code（允许 null）
   * @return true 是原材料；null / 不在白名单都返 false
   */
  boolean isRawMaterial(String costElementCode);
}
