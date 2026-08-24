package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceTypeRoute;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 物料价格路由服务 —— 根据物料当前最新类型给出该物料应走的取价桶。
 *
 * <p>设计要点：
 * <ul>
 *   <li>当前类型：严格按 {@code created_at DESC, id DESC} 取一条</li>
 *   <li>类型路由不按月份复制，也不因 {@code effective_to} 到期停止报价</li>
 *   <li>价格生效期、供应商选择和历史价沿用由具体价格源负责</li>
 *   <li>纯查询服务，无写操作；可在 Caffeine 中缓存（本期未启用，避免与导入/编辑窗口冲突）</li>
 * </ul>
 */
public interface MaterialPriceRouterService {

  /**
   * 解析当前最新价格类型。
   *
   * @param materialCode 物料编码
   * @param period       账期（格式 yyyy-MM）；保留在接口中供下游取价，类型路由不使用
   * @param quoteDate    询价/试算日期；保留在接口中供下游取价，类型路由不使用
   * @return 命中的路由；查不到任何候选时返回 empty（调用方应记录 WARN 并标红）
   */
  Optional<PriceTypeRoute> resolve(String materialCode, String period, LocalDate quoteDate);

  /**
   * 返回当前最新路由，结果最多一条。
   *
   * @return 当前路由；无任何登记或当前类型非法时返回空 list
   */
  List<PriceTypeRoute> listCandidates(String materialCode, String period, LocalDate quoteDate);
}
