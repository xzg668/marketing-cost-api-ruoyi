package com.sanhua.marketingcost.service.rule;

import com.sanhua.marketingcost.entity.BomByproductCostRule;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 新 BOM 副产品附加规则命中器。
 *
 * <p>只负责命中判断，不负责写结算行；是否补充 {@code BYPRODUCT_EXTRA} 行由后续成本构建服务执行。
 */
@Component
public class BomByproductCostRuleMatcher {

  private final BomByproductCostRuleConditionEvaluator conditionEvaluator;

  public BomByproductCostRuleMatcher(BomByproductCostRuleConditionEvaluator conditionEvaluator) {
    this.conditionEvaluator = conditionEvaluator;
  }

  public Optional<BomByproductCostRule> match(
      BomRuleNodeContext byproductContext,
      String addConditionType,
      String bomPurpose,
      LocalDate asOfDate,
      List<BomByproductCostRule> candidates) {
    if (byproductContext == null || candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
    return candidates.stream()
        .filter(rule -> rule != null)
        .filter(rule -> Integer.valueOf(1).equals(rule.getEnabled()))
        .filter(rule -> inEffectiveWindow(rule, today))
        .filter(rule -> scopeMatches(rule.getBusinessUnitType(), byproductContext.businessUnitType()))
        .filter(rule -> scopeMatches(rule.getBomPurpose(), bomPurpose))
        .filter(rule -> scopeMatches(rule.getAddConditionType(), addConditionType))
        .filter(rule -> conditionEvaluator.evaluate(rule.getMatchConditionJson(), byproductContext))
        .sorted(Comparator.comparing(
            BomByproductCostRule::getPriority,
            Comparator.nullsLast(Integer::compareTo)))
        .findFirst();
  }

  private static boolean inEffectiveWindow(BomByproductCostRule rule, LocalDate asOfDate) {
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
