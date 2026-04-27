package com.sanhua.marketingcost.service.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.BomLeafRollupCodesProvider;
import com.sanhua.marketingcost.service.BomRawMaterialCostElementsProvider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T8 · CompositeRuleEvaluator 单测：覆盖 8 个分支 + 边界。
 *
 * <p>不启 Spring，直接 new；用真实 Jackson ObjectMapper 做 JSON 解析。
 */
@DisplayName("T8 CompositeRuleEvaluator · 三组条件 + op + 字段白名单")
class CompositeRuleEvaluatorTest {

  private CompositeRuleEvaluator evaluator;

  /** T11：测试用桩 Provider，按测试需要返回固定值集 */
  private BomLeafRollupCodesProvider stubProvider;

  /** T11 增强：测试用桩原材料 Provider；默认放 No101 让老测试用例（cost_element=No101）能命中 */
  private BomRawMaterialCostElementsProvider stubRawProvider;

  @BeforeEach
  void setUp() {
    // 默认 stub 返回空集（绝大多数老测试用例不依赖 IN_DICT，行为不变）
    stubProvider = new BomLeafRollupCodesProvider() {
      @Override public Set<String> getCategoryCodes() { return Set.of(); }
      @Override public Set<String> getNameKeywords() { return Set.of(); }
      @Override public boolean matches(String c, String n) { return false; }
    };
    // 默认 stub：把 No101 当原材料；T11 增强后 IN_DICT 命中前置条件
    stubRawProvider = new BomRawMaterialCostElementsProvider() {
      @Override public Set<String> getCostElementCodes() { return Set.of("No101"); }
      @Override public boolean isRawMaterial(String code) { return "No101".equals(code); }
    };
    evaluator = new CompositeRuleEvaluator(new ObjectMapper(), stubProvider, stubRawProvider);
  }

  // ============================ T11 IN_DICT op ============================

  @Test
  @DisplayName("T11 IN_DICT：编码命中（material_category_1 在编码白名单）")
  void inDictHitByCode() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\","
            + "\"value\":\"bom_leaf_rollup_codes\"}]}";
    BomLeafRollupCodesProvider provider = new BomLeafRollupCodesProvider() {
      @Override public Set<String> getCategoryCodes() { return Set.of("171711404"); }
      @Override public Set<String> getNameKeywords() { return Set.of(); }
      @Override public boolean matches(String c, String n) {
        return "171711404".equals(c);
      }
    };
    CompositeRuleEvaluator e = new CompositeRuleEvaluator(new ObjectMapper(), provider, stubRawProvider);
    // T11 增强：cost_element_code=No101 才能进入 leafRollup 字典命中流程
    BomNodeContext node = new BomNodeContext(
        "M1", "拉制铜管 phi8", null, null, null, null, "No101", "171711404", null);
    assertThat(e.evaluate(json, node, null, List.of())).isTrue();
  }

  @Test
  @DisplayName("T11 IN_DICT：名称兜底命中（cat1=NULL，name contains 关键词）")
  void inDictHitByName() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\","
            + "\"value\":\"bom_leaf_rollup_codes\"}]}";
    BomLeafRollupCodesProvider provider = new BomLeafRollupCodesProvider() {
      @Override public Set<String> getCategoryCodes() { return Set.of(); }
      @Override public Set<String> getNameKeywords() { return Set.of("拉制铜管"); }
      @Override public boolean matches(String c, String n) {
        return n != null && n.contains("拉制铜管");
      }
    };
    CompositeRuleEvaluator e = new CompositeRuleEvaluator(new ObjectMapper(), provider, stubRawProvider);
    // T11 增强：cost_element_code=No101 是前置硬条件
    BomNodeContext node = new BomNodeContext(
        "M1", "拉制铜管 D8x0.5", null, null, null, null, "No101", null, null);
    assertThat(e.evaluate(json, node, null, List.of())).isTrue();
  }

  @Test
  @DisplayName("T11 增强 IN_DICT：cost_element_code 不在原材料白名单则不命中（即使料号 / 名字命中）")
  void inDictMissByNonRawMaterial() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\","
            + "\"value\":\"bom_leaf_rollup_codes\"}]}";
    BomLeafRollupCodesProvider provider = new BomLeafRollupCodesProvider() {
      @Override public Set<String> getCategoryCodes() { return Set.of("171711404"); }
      @Override public Set<String> getNameKeywords() { return Set.of("拉制铜管"); }
      @Override public boolean matches(String c, String n) {
        return "171711404".equals(c) || (n != null && n.contains("拉制铜管"));
      }
    };
    CompositeRuleEvaluator e = new CompositeRuleEvaluator(new ObjectMapper(), provider, stubRawProvider);
    // 名字含拉制铜管 + cat1 是 171711404，但 cost_element=No104（包装材料）→ 不命中
    BomNodeContext node = new BomNodeContext(
        "M1", "拉制铜管 装饰用", null, null, null, null, "No104", "171711404", null);
    assertThat(e.evaluate(json, node, null, List.of())).isFalse();
  }

  @Test
  @DisplayName("T11 IN_DICT：value 缺字典 key 视为不命中 + warn")
  void inDictMissingKey() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\"}]}";
    BomNodeContext node = new BomNodeContext(
        "M1", "拉制铜管", null, null, null, null, null, "171711404", null);
    assertThat(evaluator.evaluate(json, node, null, List.of())).isFalse();
  }

  @Test
  @DisplayName("T11 IN_DICT：未识别的字典 key 视为不命中 + warn")
  void inDictUnknownKey() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\","
            + "\"value\":\"unknown_dict_key\"}]}";
    BomNodeContext node = new BomNodeContext(
        "M1", "拉制铜管", null, null, null, null, null, "171711404", null);
    assertThat(evaluator.evaluate(json, node, null, List.of())).isFalse();
  }

  // ============================ 基本字段 + op ============================

  @Test
  @DisplayName("nodeConditions EQ：本节点 cost_element_code 命中")
  void nodeEqHits() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"主要材料-原材料\"}]}";
    BomNodeContext node = nodeWithCostElement("主要材料-原材料");
    assertThat(evaluator.evaluate(json, node, null, List.of())).isTrue();
  }

  @Test
  @DisplayName("nodeConditions EQ：值不等则不命中")
  void nodeEqMiss() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"主要材料-原材料\"}]}";
    BomNodeContext node = nodeWithCostElement("辅料");
    assertThat(evaluator.evaluate(json, node, null, List.of())).isFalse();
  }

  @Test
  @DisplayName("nodeConditions IN：字段属于列表命中")
  void nodeInHits() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN\","
            + "\"values\":[\"紫铜盘管\",\"紫铜直管\"]}]}";
    BomNodeContext node = nodeWithCategory1("紫铜盘管");
    assertThat(evaluator.evaluate(json, node, null, List.of())).isTrue();
  }

  @Test
  @DisplayName("nodeConditions IN：字段不在列表不命中")
  void nodeInMiss() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN\","
            + "\"values\":[\"紫铜盘管\"]}]}";
    BomNodeContext node = nodeWithCategory1("铝盘管");
    assertThat(evaluator.evaluate(json, node, null, List.of())).isFalse();
  }

  // ============================ 三组 AND 语义 ============================

  @Test
  @DisplayName("T8 典型场景：本节点 cost_element + 子件 category IN 组合命中")
  void compositeAndHits() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"主要材料-原材料\"}],"
            + "\"childConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN\","
            + "\"values\":[\"紫铜盘管\",\"紫铜直管\"]}]}";
    BomNodeContext parent = nodeWithCostElement("主要材料-原材料");
    List<BomNodeContext> children = List.of(
        nodeWithCategory1("紫铜盘管"), nodeWithCategory1("紫铜直管"));
    assertThat(evaluator.evaluate(json, parent, null, children)).isTrue();
  }

  @Test
  @DisplayName("childConditions：无子件满足 → 不命中（AND 破缺）")
  void childMiss() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"主要材料-原材料\"}],"
            + "\"childConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN\","
            + "\"values\":[\"紫铜盘管\"]}]}";
    BomNodeContext parent = nodeWithCostElement("主要材料-原材料");
    List<BomNodeContext> children = List.of(nodeWithCategory1("铝盘管")); // 非紫铜
    assertThat(evaluator.evaluate(json, parent, null, children)).isFalse();
  }

  @Test
  @DisplayName("childConditions：至少一个子件满足 → 命中（OR within children）")
  void childAnyHits() {
    String json =
        "{\"childConditions\":[{\"field\":\"material_category_1\",\"op\":\"EQ\","
            + "\"value\":\"紫铜盘管\"}]}";
    BomNodeContext parent = nodeWithCategory1(null); // 本节点无条件
    List<BomNodeContext> children = List.of(
        nodeWithCategory1("铝盘管"),        // 不命中
        nodeWithCategory1("紫铜盘管"));      // 命中
    assertThat(evaluator.evaluate(json, parent, null, children)).isTrue();
  }

  @Test
  @DisplayName("parentConditions：顶层节点无父 → 有父条件则必定不命中")
  void parentConditionButNoParent() {
    String json =
        "{\"parentConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"组件\"}]}";
    BomNodeContext node = nodeWithCategory1("紫铜盘管");
    assertThat(evaluator.evaluate(json, node, null, List.of())).isFalse();
  }

  @Test
  @DisplayName("parentConditions：父节点满足条件 → 命中")
  void parentConditionHits() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"EQ\",\"value\":\"紫铜盘管\"}],"
            + "\"parentConditions\":[{\"field\":\"cost_element_code\",\"op\":\"EQ\",\"value\":\"主要材料-原材料\"}]}";
    BomNodeContext node = nodeWithCategory1("紫铜盘管");
    BomNodeContext parent = nodeWithCostElement("主要材料-原材料");
    assertThat(evaluator.evaluate(json, node, parent, List.of())).isTrue();
  }

  // ============================ 边界 / 异常 ============================

  @Test
  @DisplayName("空 JSON 字符串 → 不命中")
  void emptyJson() {
    assertThat(evaluator.evaluate("", nodeWithCategory1("x"), null, List.of())).isFalse();
    assertThat(evaluator.evaluate(null, nodeWithCategory1("x"), null, List.of())).isFalse();
  }

  @Test
  @DisplayName("无效 JSON → 不命中 + 不抛异常")
  void invalidJson() {
    assertThat(evaluator.evaluate("{不是合法 JSON", nodeWithCategory1("x"), null, List.of()))
        .isFalse();
  }

  @Test
  @DisplayName("未知 op → 不命中 + log WARN")
  void unknownOp() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"REGEX\",\"value\":\"紫铜.*\"}]}";
    assertThat(evaluator.evaluate(json, nodeWithCategory1("紫铜盘管"), null, List.of())).isFalse();
  }

  @Test
  @DisplayName("未知 field → 不命中 + log WARN")
  void unknownField() {
    String json =
        "{\"nodeConditions\":[{\"field\":\"not_exist_field\",\"op\":\"EQ\",\"value\":\"x\"}]}";
    assertThat(evaluator.evaluate(json, nodeWithCategory1("紫铜盘管"), null, List.of())).isFalse();
  }

  @Test
  @DisplayName("所有三组都空 → 视为无约束 → 命中")
  void allEmptyIsNoop() {
    String json = "{}";
    assertThat(evaluator.evaluate(json, nodeWithCategory1("x"), null, List.of())).isTrue();
  }

  // ============================ 辅助 ============================

  private static BomNodeContext nodeWithCostElement(String code) {
    return new BomNodeContext(
        "M1", "material1", null, null, null, null, code, null, null);
  }

  private static BomNodeContext nodeWithCategory1(String cat1) {
    return new BomNodeContext(
        "M1", "material1", cat1, null, null, null, null, cat1, null);
  }
}
