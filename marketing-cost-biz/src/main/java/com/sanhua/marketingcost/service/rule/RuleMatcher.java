package com.sanhua.marketingcost.service.rule;

import java.util.List;
import java.util.Optional;

/**
 * 节点 → 规则命中的泛型匹配器接口。
 *
 * <p>泛型参数 {@code R} 是规则实体类型。T5 只实现一个 {@link StopDrillRuleMatcher}
 * 对应 {@link com.sanhua.marketingcost.entity.BomStopDrillRule}；未来其他类型规则
 * （比如"导入阻断规则"、"替换规则"）可以复用本接口。
 *
 * @param <R> 规则实体
 */
public interface RuleMatcher<R> {

  /**
   * 按启用 / 时效窗口 / 业务单元过滤 {@code candidates}，再按 priority 升序挑首个命中。
   *
   * @param node 节点上下文
   * @param candidates 候选规则列表（可以来自 DB 全量拉取）
   * @return 命中的规则，或 {@link Optional#empty()} 表示无命中
   */
  Optional<R> match(BomNodeContext node, List<R> candidates);
}
