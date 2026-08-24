package com.sanhua.marketingcost.service.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomSettlementRuleMatcher · 新树节点结算规则命中")
class BomSettlementRuleMatcherTest {

  private final BomSettlementRuleMatcher matcher =
      new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper()));

  @Test
  @DisplayName("JSON 条件覆盖编码白名单、正式主分类编码、采购分类、形态属性、成本要素")
  void conditionSupportsSettlementFieldWhitelist() {
    BomSettlementRule rule = baseRule("FIELD_WHITELIST", 10);
    rule.setMatchConditionJson("""
        {
          "nodeConditions": [
            {"field":"material_code","op":"IN","values":["301240123"]},
            {"field":"material_name","op":"LIKE","value":"不锈钢"},
            {"field":"main_category_code","op":"EQ","value":"181831498"},
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
  @DisplayName("塑料默认排除，但财务指定采购分类保留")
  void plasticExcludeRuleSupportsPurchaseCategoryKeepList() {
    BomSettlementRule rule = baseRule("AUXILIARY_EXCLUDE_PLASTIC", 10);
    rule.setMatchConditionJson("""
        {"nodeConditions":[
          {"field":"main_category_code","op":"EQ","value":"171721412"},
          {"field":"purchase_category","op":"NOT_IN","values":[
            "PEEK","PVC套管","其它高分子材料","其它橡塑制品","热缩管","注塑件"
          ]}
        ]}
        """);

    assertThat(matcher.match(
        nodeWithClassification("171721412", "普通塑料"), null, List.of(), null,
        LocalDate.of(2026, 7, 20), List.of(rule))).contains(rule);
    assertThat(matcher.match(
        nodeWithClassification("171721412", "PEEK"), null, List.of(), null,
        LocalDate.of(2026, 7, 20), List.of(rule))).isEmpty();
    assertThat(matcher.match(
        nodeWithClassification("171721412", null), null, List.of(), null,
        LocalDate.of(2026, 7, 20), List.of(rule))).contains(rule);
  }

  @Test
  @DisplayName("粘胶辅料默认排除，其它包装材料保留")
  void adhesiveAuxiliaryRuleSupportsPackagingException() {
    BomSettlementRule rule = baseRule("AUXILIARY_EXCLUDE_ADHESIVE_AUX", 10);
    rule.setMatchConditionJson("""
        {"nodeConditions":[
          {"field":"main_category_code","op":"EQ","value":"181841444"},
          {"field":"purchase_category","op":"NE","value":"其它包装材料"}
        ]}
        """);

    assertThat(matcher.match(
        nodeWithClassification("181841444", "普通粘胶"), null, List.of(), null,
        LocalDate.of(2026, 7, 20), List.of(rule))).contains(rule);
    assertThat(matcher.match(
        nodeWithClassification("181841444", "其它包装材料"), null, List.of(), null,
        LocalDate.of(2026, 7, 20), List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("财务特殊采购上卷同时校验采购形态、28项采购分类和8项主分类排除")
  void financeSpecialPurchaseRollupRequiresAllThreeConditions() {
    BomSettlementRule rule = baseRule("SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION", 10);
    rule.setMatchConditionJson("""
        {"nodeConditions":[
          {"field":"shape_attr","op":"EQ","value":"采购件"},
          {"field":"purchase_category","op":"IN","values":["挤压铜棒","不锈钢棒"]},
          {"field":"main_category_code","op":"NOT_BLANK"},
          {"field":"main_category_code","op":"NOT_IN","values":["121191304","121181508"]}
        ]}
        """);

    assertThat(match(rule, financeNode(" 采购件 ", " 挤压铜棒 ", "171711402")))
        .contains(rule);
    assertThat(match(rule, financeNode("制造件", "挤压铜棒", "171711402")))
        .isEmpty();
    assertThat(match(rule, financeNode("采购件", "普通采购分类", "171711402")))
        .isEmpty();
    assertThat(match(rule, financeNode("采购件", "挤压铜棒", "121191304")))
        .isEmpty();
    assertThat(match(rule, financeNode("采购件", "挤压铜棒", null)))
        .isEmpty();
    assertThat(match(rule, financeNode("采购件", null, "171711402")))
        .isEmpty();
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
        "181831498",
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
        null,
        "辅料",
        purchaseCategory,
        "制造件",
        "RM01",
        "采购件",
        "COMMERCIAL",
        "主制造");
  }

  private static BomRuleNodeContext nodeWithClassification(
      String mainCategoryCode, String purchaseCategory) {
    return new BomRuleNodeContext(
        "X",
        "辅料",
        "legacy-category",
        mainCategoryCode,
        "仅供展示的分类名称",
        purchaseCategory,
        "采购件",
        "RM01",
        "采购件",
        "COMMERCIAL",
        "主制造");
  }

  private Optional<BomSettlementRule> match(BomSettlementRule rule, BomRuleNodeContext context) {
    return matcher.match(
        context, null, List.of(), null, LocalDate.of(2026, 8, 20), List.of(rule));
  }

  private static BomRuleNodeContext financeNode(
      String shapeAttr, String purchaseCategory, String mainCategoryCode) {
    return new BomRuleNodeContext(
        "FINANCE-ROLLUP-CHILD",
        "财务上卷测试子件",
        null,
        mainCategoryCode,
        null,
        purchaseCategory,
        shapeAttr,
        null,
        null,
        "COMMERCIAL",
        "主制造");
  }
}
