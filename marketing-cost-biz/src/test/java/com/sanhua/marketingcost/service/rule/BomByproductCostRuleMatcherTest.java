package com.sanhua.marketingcost.service.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomByproductCostRuleMatcher · 新副产品附加规则命中")
class BomByproductCostRuleMatcherTest {

  private final BomByproductCostRuleMatcher matcher =
      new BomByproductCostRuleMatcher(
          new BomByproductCostRuleConditionEvaluator(new ObjectMapper()));

  @Test
  @DisplayName("空 JSON 表示不追加字段限制，addConditionType 仍必须命中")
  void blankJsonMeansNoExtraFieldConstraint() {
    BomByproductCostRule rule = baseRule("BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF", 10);
    rule.setMatchConditionJson(null);

    assertThat(matcher.match(
            node(), "NO_SCRAP_REF_MATCH", "主制造", LocalDate.of(2026, 5, 29), List.of(rule)))
        .contains(rule);
    assertThat(matcher.match(
            node(), "SCRAP_REF_MATCHED", "主制造", LocalDate.of(2026, 5, 29), List.of(rule)))
        .isEmpty();
  }

  @Test
  @DisplayName("副产品 JSON 条件、BU、bomPurpose、生效期和 priority 统一过滤")
  void filtersScopeJsonAndPriority() {
    BomByproductCostRule wrongShape = baseRule("WRONG_SHAPE", 1);
    wrongShape.setMatchConditionJson("""
        {"byproductConditions":[{"field":"shape_attr","op":"EQ","value":"采购件"}]}
        """);

    BomByproductCostRule expired = baseRule("EXPIRED", 2);
    expired.setEffectiveTo(LocalDate.of(2026, 1, 1));

    BomByproductCostRule lowerPriority = baseRule("LOWER_PRIORITY", 20);
    BomByproductCostRule higherPriority = baseRule("HIGHER_PRIORITY", 10);
    higherPriority.setBusinessUnitType("COMMERCIAL");
    higherPriority.setBomPurpose("主制造");

    assertThat(matcher.match(
            node(),
            "NO_SCRAP_REF_MATCH",
            "主制造",
            LocalDate.of(2026, 5, 29),
            List.of(wrongShape, expired, lowerPriority, higherPriority)))
        .contains(higherPriority);
  }

  private static BomByproductCostRule baseRule(String code, int priority) {
    BomByproductCostRule rule = new BomByproductCostRule();
    rule.setId((long) priority);
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");
    rule.setSettlementRowType("BYPRODUCT_EXTRA");
    rule.setMatchConditionJson("""
        {"byproductConditions":[{"field":"shape_attr","op":"EQ","value":"制造件"}]}
        """);
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomRuleNodeContext node() {
    return new BomRuleNodeContext(
        "P001",
        "副产品",
        "31",
        "副产品",
        null,
        "制造件",
        "BY01",
        "制造件",
        "COMMERCIAL",
        "主制造");
  }
}
