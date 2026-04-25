package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceTypeRoute;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 物料价格路由服务 —— 根据 (materialCode, period, quoteDate) 给出该物料应走的取价桶。
 *
 * <p>设计要点：
 * <ul>
 *   <li>命中：按 priority 升序的第一条满足生效期的路由</li>
 *   <li>降级：listCandidates 返回所有候选，调用方可在第一条 Resolver 拿不到值时按顺序回退</li>
 *   <li>纯查询服务，无写操作；可在 Caffeine 中缓存（本期未启用，避免与导入/编辑窗口冲突）</li>
 * </ul>
 */
public interface MaterialPriceRouterService {

  /**
   * 解析当前生效的取价路由（priority 最小且在生效期内）。
   *
   * @param materialCode 物料编码
   * @param period       账期（格式 yyyy-MM）；与 lp_material_price_type.period 严格匹配
   * @param quoteDate    询价/试算日期，用于过滤 effective_from/to 窗口；null 表示不限
   * @return 命中的路由；查不到任何候选时返回 empty（调用方应记录 WARN 并标红）
   */
  Optional<PriceTypeRoute> resolve(String materialCode, String period, LocalDate quoteDate);

  /**
   * 列出所有候选路由（按 priority 升序）。
   *
   * <p>用于双跑模式下的 diff 审计，以及主路由 Resolver 拿不到值时的有序降级。
   *
   * @return 候选列表；无任何登记返回空 list（调用方需自行处理）
   */
  List<PriceTypeRoute> listCandidates(String materialCode, String period, LocalDate quoteDate);
}
