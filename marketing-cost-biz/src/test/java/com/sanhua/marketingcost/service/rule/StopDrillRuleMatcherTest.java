package com.sanhua.marketingcost.service.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StopDrillRuleMatcher} 纯对象单测（不依赖 DB / Spring）。
 *
 * <p>覆盖 5 种 match_type + priority / enabled / effective window / BU scope / unknown type
 * 8 个分支，对应 T5 §5.1。
 *
 * <p>T8：matcher 构造器加了 CompositeRuleEvaluator 依赖；这里直接 new 一份带真实 Jackson 的。
 */
@DisplayName("StopDrillRuleMatcher · 5 种 match_type + 过滤条件")
class StopDrillRuleMatcherTest {

  private final StopDrillRuleMatcher matcher =
      new StopDrillRuleMatcher(new CompositeRuleEvaluator(new ObjectMapper()));

  @Test
  @DisplayName("testNameLike：NAME_LIKE '接管' → 节点名 'D接管' 命中，'阀座' 不命中")
  void testNameLike() {
    BomStopDrillRule rule = baseRule("NAME_LIKE", "接管");
    assertThat(matcher.match(node("X", "D接管"), List.of(rule))).isPresent();
    assertThat(matcher.match(node("X", "阀座"), List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("testMaterialCodePrefix：materialCode 以 '2032' 开头命中")
  void testMaterialCodePrefix() {
    BomStopDrillRule rule = baseRule("MATERIAL_CODE_PREFIX", "2032");
    assertThat(matcher.match(node("203250582", "x"), List.of(rule))).isPresent();
    assertThat(matcher.match(node("301050054", "x"), List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("testMaterialType：materialCategory 完全等于 match_value 命中")
  void testMaterialType() {
    BomStopDrillRule rule = baseRule("MATERIAL_TYPE", "接管");
    BomNodeContext n =
        BomNodeContext.legacy("X", "name", "接管", null, null, null);
    assertThat(matcher.match(n, List.of(rule))).isPresent();
    BomNodeContext n2 =
        BomNodeContext.legacy("X", "name", "其他", null, null, null);
    assertThat(matcher.match(n2, List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("testCategoryEq：productionCategory='采购件' 命中")
  void testCategoryEq() {
    BomStopDrillRule rule = baseRule("CATEGORY_EQ", "采购件");
    BomNodeContext n =
        BomNodeContext.legacy("X", "name", null, null, "采购件", null);
    assertThat(matcher.match(n, List.of(rule))).isPresent();
    BomNodeContext n2 =
        BomNodeContext.legacy("X", "name", null, null, "制造件", null);
    assertThat(matcher.match(n2, List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("testShapeAttrEq：shapeAttr='部品联动' 命中")
  void testShapeAttrEq() {
    BomStopDrillRule rule = baseRule("SHAPE_ATTR_EQ", "部品联动");
    BomNodeContext n =
        BomNodeContext.legacy("X", "name", null, "部品联动", null, null);
    assertThat(matcher.match(n, List.of(rule))).isPresent();
  }

  @Test
  @DisplayName("testPriorityOrder：两条都命中时 priority 小的优先返回")
  void testPriorityOrder() {
    BomStopDrillRule r10 = baseRule("NAME_LIKE", "接管");
    r10.setId(10L);
    r10.setPriority(10);
    BomStopDrillRule r20 = baseRule("NAME_LIKE", "接");
    r20.setId(20L);
    r20.setPriority(20);
    Optional<BomStopDrillRule> hit =
        matcher.match(node("X", "D接管"), List.of(r20, r10));
    assertThat(hit).isPresent();
    assertThat(hit.get().getId()).isEqualTo(10L);
  }

  @Test
  @DisplayName("testDisabledNotHit：enabled=0 的规则永不命中")
  void testDisabledNotHit() {
    BomStopDrillRule rule = baseRule("NAME_LIKE", "接管");
    rule.setEnabled(0);
    assertThat(matcher.match(node("X", "D接管"), List.of(rule))).isEmpty();
  }

  @Test
  @DisplayName("testBuScopeMatch：规则 bu=COMMERCIAL，节点 bu=HOUSEHOLD → 不命中；节点 bu=COMMERCIAL → 命中")
  void testBuScopeMatch() {
    BomStopDrillRule rule = baseRule("NAME_LIKE", "接管");
    rule.setBusinessUnitType("COMMERCIAL");
    BomNodeContext household =
        BomNodeContext.legacy("X", "D接管", null, null, null, "HOUSEHOLD");
    BomNodeContext commercial =
        BomNodeContext.legacy("X", "D接管", null, null, null, "COMMERCIAL");
    assertThat(matcher.match(household, List.of(rule))).isEmpty();
    assertThat(matcher.match(commercial, List.of(rule))).isPresent();

    // 全局规则（bu=null）对任意节点都命中
    rule.setBusinessUnitType(null);
    assertThat(matcher.match(household, List.of(rule))).isPresent();
  }

  @Test
  @DisplayName("testEffectiveWindow：effective_to 已过则不命中；两端 NULL 视为永久")
  void testEffectiveWindow() {
    BomStopDrillRule expired = baseRule("NAME_LIKE", "接管");
    expired.setEffectiveFrom(LocalDate.of(2020, 1, 1));
    expired.setEffectiveTo(LocalDate.of(2020, 12, 31));
    assertThat(matcher.match(node("X", "D接管"), List.of(expired))).isEmpty();

    BomStopDrillRule forever = baseRule("NAME_LIKE", "接管");
    forever.setEffectiveFrom(null);
    forever.setEffectiveTo(null);
    assertThat(matcher.match(node("X", "D接管"), List.of(forever))).isPresent();
  }

  @Test
  @DisplayName("testUnknownMatchTypeWarn：match_type='UNKNOWN_XXX' 不命中（内部 log 已 warn）")
  void testUnknownMatchTypeWarn() {
    BomStopDrillRule rule = baseRule("UNKNOWN_XXX", "whatever");
    assertThat(matcher.match(node("X", "D接管"), List.of(rule))).isEmpty();
  }

  // ============================ 辅助 ============================

  private static BomStopDrillRule baseRule(String type, String value) {
    BomStopDrillRule r = new BomStopDrillRule();
    r.setId(1L);
    r.setMatchType(type);
    r.setMatchValue(value);
    r.setDrillAction("STOP_AND_COST_ROW");
    r.setMarkSubtreeCostRequired(1);
    r.setPriority(100);
    r.setEnabled(1);
    return r;
  }

  private static BomNodeContext node(String code, String name) {
    return BomNodeContext.legacy(code, name, null, null, null, null);
  }
}
