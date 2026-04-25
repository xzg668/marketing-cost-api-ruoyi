package com.sanhua.marketingcost.service.rule;

import com.sanhua.marketingcost.entity.BomStopDrillRule;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * BOM 下钻规则匹配器。
 *
 * <p>命中优先级（按规则内部）：
 * <ol>
 *   <li>如果 {@code match_condition_json} 非空 → 走 T8 复合条件评估器</li>
 *   <li>否则走 5 种老 match_type（NAME_LIKE / MATERIAL_CODE_PREFIX / MATERIAL_TYPE /
 *       CATEGORY_EQ / SHAPE_ATTR_EQ）单字段匹配</li>
 * </ol>
 *
 * <p>跨规则过滤条件（通用）：
 * <ul>
 *   <li>enabled = 1</li>
 *   <li>今天落在 [effective_from, effective_to]（任一端 NULL 视为永久）</li>
 *   <li>businessUnitType = NULL（全局）或等于节点 BU</li>
 * </ul>
 *
 * <p>多条命中时按 priority 升序取第一条；priority 为 NULL 视为最大（最后）。
 */
@Component
public class StopDrillRuleMatcher implements RuleMatcher<BomStopDrillRule> {

  private static final Logger log = LoggerFactory.getLogger(StopDrillRuleMatcher.class);

  private final CompositeRuleEvaluator compositeEvaluator;

  @Autowired
  public StopDrillRuleMatcher(CompositeRuleEvaluator compositeEvaluator) {
    this.compositeEvaluator = compositeEvaluator;
  }

  /**
   * 老签名 —— 保留向前兼容，用于只需单节点属性的场景。
   * 跨父子条件（T8 复合）要走 {@link #match(BomNodeContext, BomNodeContext, List, List)}。
   */
  @Override
  public Optional<BomStopDrillRule> match(BomNodeContext node, List<BomStopDrillRule> candidates) {
    return match(node, null, Collections.emptyList(), candidates);
  }

  /**
   * T8 扩展签名：接受父节点 + 直接子节点上下文，让复合条件里的 parentConditions /
   * childConditions 能被正确评估。
   *
   * @param node 本节点上下文（必填）
   * @param parent 父节点上下文，顶层节点传 null
   * @param children 本节点的直接子节点上下文列表（允许空）
   * @param candidates 候选规则列表
   */
  public Optional<BomStopDrillRule> match(
      BomNodeContext node,
      BomNodeContext parent,
      List<BomNodeContext> children,
      List<BomStopDrillRule> candidates) {
    if (candidates == null || candidates.isEmpty() || node == null) {
      return Optional.empty();
    }
    LocalDate today = LocalDate.now();
    return candidates.stream()
        .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
        .filter(r -> inEffectiveWindow(r, today))
        .filter(r -> buScopeMatches(r, node))
        .sorted(Comparator.comparingInt(r ->
            r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()))
        .filter(r -> hitBy(r, node, parent, children))
        .findFirst();
  }

  /** 时效窗口判定：两端 NULL 视为永久；否则 today ∈ [from, to]。 */
  private static boolean inEffectiveWindow(BomStopDrillRule r, LocalDate today) {
    if (r.getEffectiveFrom() != null && today.isBefore(r.getEffectiveFrom())) return false;
    if (r.getEffectiveTo() != null && today.isAfter(r.getEffectiveTo())) return false;
    return true;
  }

  /** BU 范围：规则 bu=NULL 视为全局匹配；否则要与节点 bu 一致。 */
  private static boolean buScopeMatches(BomStopDrillRule r, BomNodeContext n) {
    String ruleBu = r.getBusinessUnitType();
    if (ruleBu == null || ruleBu.isEmpty()) return true;
    return Objects.equals(ruleBu, n.businessUnitType());
  }

  /** 分派：优先 JSON 复合条件；回退老字段。 */
  private boolean hitBy(
      BomStopDrillRule r,
      BomNodeContext node,
      BomNodeContext parent,
      List<BomNodeContext> children) {
    if (StringUtils.hasText(r.getMatchConditionJson())) {
      return compositeEvaluator.evaluate(r.getMatchConditionJson(), node, parent, children);
    }
    return hitByMatchType(r, node);
  }

  /** 按老 match_type 执行单字段判定（与 T5 行为完全一致）。 */
  private boolean hitByMatchType(BomStopDrillRule r, BomNodeContext n) {
    String type = r.getMatchType();
    String value = r.getMatchValue();
    if (type == null || value == null) return false;
    return switch (type) {
      case "NAME_LIKE" -> n.materialName() != null && n.materialName().contains(value);
      case "MATERIAL_CODE_PREFIX" -> n.materialCode() != null && n.materialCode().startsWith(value);
      case "MATERIAL_TYPE" -> Objects.equals(n.materialCategory(), value);
      case "CATEGORY_EQ" -> Objects.equals(n.productionCategory(), value);
      case "SHAPE_ATTR_EQ" -> Objects.equals(n.shapeAttr(), value);
      case "COMPOSITE" -> {
        // COMPOSITE 是 T8 的占位 match_type —— 真规则要靠 match_condition_json；
        // 如果这里到了 COMPOSITE 但 JSON 为空，配置错误，记 WARN 不命中
        log.warn("规则 id={} match_type=COMPOSITE 但 match_condition_json 为空，跳过", r.getId());
        yield false;
      }
      default -> {
        log.warn("未知 match_type={} 跳过规则 id={}", type, r.getId());
        yield false;
      }
    };
  }
}
