package com.sanhua.marketingcost.service.rule;

import com.sanhua.marketingcost.entity.BomSettlementRule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 新 BOM 树节点结算规则命中器。
 *
 * <p>只负责命中判断，不负责写结算行；写 {@code lp_bom_costing_row} /
 * {@code lp_bom_costing_row_sub_ref} 的动作由后续成本构建服务按命中结果执行。
 */
@Component
public class BomSettlementRuleMatcher {

  private final BomSettlementRuleConditionEvaluator conditionEvaluator;

  public BomSettlementRuleMatcher(BomSettlementRuleConditionEvaluator conditionEvaluator) {
    this.conditionEvaluator = conditionEvaluator;
  }

  public Optional<BomSettlementRule> match(
      BomRuleNodeContext node,
      BomRuleNodeContext parent,
      List<BomRuleNodeContext> children,
      String bomPurpose,
      LocalDate asOfDate,
      List<BomSettlementRule> candidates) {
    if (node == null || candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
    return candidates.stream()
        .filter(rule -> rule != null)
        .filter(rule -> Integer.valueOf(1).equals(rule.getEnabled()))
        .filter(rule -> inEffectiveWindow(rule, today))
        .filter(rule -> scopeMatches(rule.getBusinessUnitType(), node.businessUnitType()))
        .filter(rule -> scopeMatches(rule.getBomPurpose(), bomPurpose))
        .filter(rule -> conditionEvaluator.evaluate(
            rule.getMatchConditionJson(), node, parent, children))
        .sorted(Comparator.comparing(
            BomSettlementRule::getPriority,
            Comparator.nullsLast(Integer::compareTo)))
        .findFirst();
  }

  private static boolean inEffectiveWindow(BomSettlementRule rule, LocalDate asOfDate) {
    if (rule.getEffectiveFrom() != null && asOfDate.isBefore(rule.getEffectiveFrom())) {
      return false;
    }
    return rule.getEffectiveTo() == null || !asOfDate.isAfter(rule.getEffectiveTo());
  }

  private static boolean scopeMatches(String ruleScope, String requestedScope) {
    if (!StringUtils.hasText(ruleScope)) {
      return true;
    }
    return ruleScope.equals(requestedScope);
  }
}
