package com.sanhua.marketingcost.service;

import java.util.Set;

/**
 * T11 · BOM 叶子上卷分类字典查询服务。
 *
 * <p>从 sys_dict_data（dict_type=bom_leaf_rollup_codes，status=0，deleted=0）拉值集，
 * 区分两路：
 * <ul>
 *   <li>编码白名单（dict_value 不带 NAME: 前缀）—— 与叶子节点 material_category_1 做 IN 命中</li>
 *   <li>名称关键词（dict_value 以 NAME: 开头）—— 去前缀后与叶子节点 material_name 做 contains 命中</li>
 * </ul>
 *
 * <p>设计要点（设计文档 §3.2 / §6.3）：
 * <ul>
 *   <li>带 cache：BOM 拍平一次循环数千节点，每节点都查字典撑不住</li>
 *   <li>"编码 OR 名称"双路匹配：U9 端 90%+ 拉制铜管节点 material_category_1=NULL，单走编码会漏</li>
 * </ul>
 */
public interface BomLeafRollupCodesProvider {

  /**
   * 拉所有非 NAME: 前缀的 dict_value，作为编码白名单。
   *
   * @return 不可空集合（字典空时返回 {@link java.util.Collections#emptySet()}）
   */
  Set<String> getCategoryCodes();

  /**
   * 拉所有 NAME: 前缀的 dict_value，**去前缀**后作为名称关键词。
   *
   * @return 不可空集合（字典空时返回 {@link java.util.Collections#emptySet()}）
   */
  Set<String> getNameKeywords();

  /**
   * 综合判定一个叶子节点是否命中 LEAF_ROLLUP 字典。
   *
   * <p>命中规则：编码命中 OR 名称命中（两路独立，任一即可）。
   *
   * @param materialCategory1 叶子的 material_category_1（允许 null）
   * @param materialName 叶子的 material_name（允许 null）
   * @return true 命中
   */
  boolean matches(String materialCategory1, String materialName);
}
