package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BOM 结算规则实体字段契约")
class BomSettlementRuleEntityContractTest {

  @Test
  @DisplayName("新规则实体映射到独立规则表")
  void entitiesMapToExpectedTables() {
    assertTable(BomSettlementRule.class, "lp_bom_settlement_rule");
    assertTable(BomByproductCostRule.class, "lp_bom_byproduct_cost_rule");
  }

  @Test
  @DisplayName("树节点结算规则字段覆盖匹配、动作、行类型、时效和业务单元")
  void settlementRuleHasExpectedFields() {
    assertFields(
        BomSettlementRule.class,
        "ruleCode",
        "ruleName",
        "ruleCategory",
        "settlementAction",
        "settlementRowType",
        "subRefType",
        "matchConditionJson",
        "markSubtreeCostRequired",
        "priority",
        "enabled",
        "businessUnitType",
        "bomPurpose",
        "effectiveFrom",
        "effectiveTo",
        "deleted");
  }

  @Test
  @DisplayName("副产品附加规则字段与树节点规则分离")
  void byproductRuleHasExpectedFields() {
    assertFields(
        BomByproductCostRule.class,
        "ruleCode",
        "ruleName",
        "ruleCategory",
        "addConditionType",
        "settlementRowType",
        "matchConditionJson",
        "priority",
        "enabled",
        "businessUnitType",
        "bomPurpose",
        "effectiveFrom",
        "effectiveTo",
        "deleted");
  }

  @Test
  @DisplayName("结算行结果表只保留新规则追溯")
  void costingRowHasSettlementRuleTraceFields() {
    assertFields(
        BomCostingRow.class,
        "matchedSettlementRuleId",
        "settlementRowType");
  }

  @Test
  @DisplayName("父结算行子件引用可区分上卷来源")
  void subRefHasTypeAndRuleTraceFields() {
    assertFields(
        BomCostingRowSubRef.class,
        "refType",
        "matchedSettlementRuleId",
        "subMaterialCode",
        "subRawHierarchyId");
  }

  private static void assertTable(Class<?> type, String tableName) {
    TableName annotation = type.getAnnotation(TableName.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo(tableName);
  }

  private static void assertFields(Class<?> type, String... fields) {
    Set<String> names =
        Arrays.stream(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    assertThat(names).contains(fields);
  }
}
