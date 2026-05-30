package com.sanhua.marketingcost.service.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomSettlementRuleMatcher · 新树节点结算规则命中")
class BomSettlementRuleMatcherTest {

  private final BomSettlementRuleMatcher matcher =
      new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper()));

  @Test
  @DisplayName("JSON 条件覆盖编码白名单、名称、主分类、采购分类、形态属性、成本要素")
  void conditionSupportsSettlementFieldWhitelist() {
    BomSettlementRule rule = baseRule("FIELD_WHITELIST", 10);
    rule.setMatchConditionJson("""
        {
          "nodeConditions": [
            {"field":"material_code","op":"IN","values":["301240123"]},
            {"field":"material_name","op":"LIKE","value":"不锈钢"},
            {"field":"main_category_name","op":"EQ","value":"辅料"},
            {"field":"purchase_category","op":"EQ","value":"丝网"},
            {"field":"shape_attr","op":"EQ","value":"制造件"},
            {"field":"cost_element_code","op":"PREFIX","value":"RM"},
            {"field":"material_category_code","op":"PREFIX","value":"18"}
          ]
        }
        """);

    assertThat(matcher.match(node(), null, List.of(), null, LocalDate.of(2026, 5, 29), List.of(rule)))
        .contains(rule);
  }

  @Test
  @DisplayName("enabled / priority / BU / bomPurpose / effective window 统一过滤")
  void filtersRuleScopeAndSortsByPriority() {
    BomSettlementRule disabled = baseRule("DISABLED", 1);
    disabled.setEnabled(0);

    BomSettlementRule expired = baseRule("EXPIRED", 2);
    expired.setEffectiveTo(LocalDate.of(2026, 1, 1));

    BomSettlementRule wrongBu = baseRule("WRONG_BU", 3);
    wrongBu.setBusinessUnitType("HOUSEHOLD");

    BomSettlementRule wrongPurpose = baseRule("WRONG_PURPOSE", 4);
    wrongPurpose.setBomPurpose("普机");

    BomSettlementRule lowerPriority = baseRule("LOWER_PRIORITY", 20);
    BomSettlementRule higherPriority = baseRule("HIGHER_PRIORITY", 10);
    higherPriority.setBusinessUnitType("COMMERCIAL");
    higherPriority.setBomPurpose("主制造");

    assertThat(matcher.match(
            node(),
            null,
            List.of(),
            "主制造",
            LocalDate.of(2026, 5, 29),
            List.of(disabled, expired, wrongBu, wrongPurpose, lowerPriority, higherPriority)))
        .contains(higherPriority);
  }

  @Test
  @DisplayName("父节点条件要求父节点存在，子节点条件要求至少一个直接子节点命中")
  void parentAndChildConditionsHaveExplicitNullSemantics() {
    BomSettlementRule rule = baseRule("PARENT_CHILD", 10);
    rule.setMatchConditionJson("""
        {
          "parentConditions": [
            {"field":"purchase_category","op":"EQ","value":"丝网"}
          ],
          "childConditions": [
            {"field":"material_name","op":"LIKE","value":"下层原料"}
          ]
        }
        """);
    BomRuleNodeContext parent = nodeWith("父件", "丝网");
    BomRuleNodeContext child = nodeWith("下层原料A", "普通");

    assertThat(matcher.match(node(), null, List.of(child), null, LocalDate.of(2026, 5, 29), List.of(rule)))
        .isEmpty();
    assertThat(matcher.match(node(), parent, List.of(), null, LocalDate.of(2026, 5, 29), List.of(rule)))
        .isEmpty();
    assertThat(matcher.match(node(), parent, List.of(child), null, LocalDate.of(2026, 5, 29), List.of(rule)))
        .contains(rule);
  }

  private static BomSettlementRule baseRule(String code, int priority) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId((long) priority);
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setMatchConditionJson("{\"nodeConditions\":[]}");
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomRuleNodeContext node() {
    return new BomRuleNodeContext(
        "301240123",
        "不锈钢带",
        "1801",
        "辅料",
        "丝网",
        "制造件",
        "RM01",
        "采购件",
        "COMMERCIAL",
        "主制造");
  }

  private static BomRuleNodeContext nodeWith(String materialName, String purchaseCategory) {
    return new BomRuleNodeContext(
        "X",
        materialName,
        "18",
        "辅料",
        purchaseCategory,
        "制造件",
        "RM01",
        "采购件",
        "COMMERCIAL",
        "主制造");
  }
}
