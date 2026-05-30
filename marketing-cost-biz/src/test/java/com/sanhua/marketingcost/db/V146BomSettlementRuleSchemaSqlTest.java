package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V146 BOM 结算规则新模型 schema")
class V146BomSettlementRuleSchemaSqlTest {

  private static final String SQL = readSql();
  private static final String LEGACY_RULE_TABLE = "bom_" + "stop_" + "drill_" + "rule";
  private static final String LEGACY_RULE_COLUMN = "matched_" + "drill_" + "rule_id";

  @Test
  @DisplayName("新增树节点结算规则表和副产品附加规则表")
  void createsRuleTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_bom_settlement_rule",
        "CREATE TABLE IF NOT EXISTS lp_bom_byproduct_cost_rule",
        "COMMENT='BOM 树节点结算规则：特殊采购分类上卷、辅料排除、包装截断等'",
        "COMMENT='BOM 副产品附加结算行规则：基于 U9 副产品档案和废料映射判断是否额外输出'");
  }

  @Test
  @DisplayName("规则字段明确区分树节点规则和副产品附加规则")
  void separatesTreeNodeAndByproductRuleSemantics() {
    assertThat(SQL).contains(
        "rule_category VARCHAR(32) NOT NULL COMMENT '树节点规则分类",
        "settlement_action VARCHAR(32) NOT NULL COMMENT '命中动作：ROLLUP_TO_PARENT/EXCLUDE/STOP_AS_PACKAGE/ADD_PROCESS_FEE'",
        "match_condition_json JSON NOT NULL COMMENT '树节点规则匹配条件 JSON；只描述 BOM 节点字段，不描述副产品附加逻辑'",
        "add_condition_type VARCHAR(64) NOT NULL COMMENT '附加条件：NO_SCRAP_REF_MATCH 表示未命中 lp_material_scrap_ref 时输出'",
        "match_condition_json JSON DEFAULT NULL COMMENT '副产品附加规则匹配条件 JSON；区别于 BOM 树节点规则'");
  }

  @Test
  @DisplayName("结算结果表补充新规则追溯和行类型字段")
  void extendsCostingRowTraceFields() {
    assertThat(SQL).contains(
        "matched_settlement_rule_id",
        "命中的新 BOM 树节点结算规则 id；旧 " + LEGACY_RULE_COLUMN + " 在 BSR-09 前保留",
        "settlement_row_type",
        "结算行类型：DEFAULT_LEAF/PACKAGE_PARENT/SPECIAL_ROLLUP_PARENT/OUTSOURCED_PROCESS_FEE/BYPRODUCT_EXTRA",
        "idx_bom_costing_settlement_rule");
  }

  @Test
  @DisplayName("sub_ref 可区分上卷来源并追溯新规则")
  void extendsSubRefTraceFields() {
    assertThat(SQL).contains(
        "ref_type",
        "子件引用类型：SPECIAL_ROLLUP_CHILD 等，用于区分上卷来源",
        "matched_settlement_rule_id",
        "产生该子件引用的新 BOM 树节点结算规则 id",
        "idx_bom_sub_ref_type_rule");
  }

  @Test
  @DisplayName("初始化特殊采购分类上卷、辅料排除和副产品附加规则")
  void seedsInitialRules() {
    assertThat(SQL).contains(
        "SPECIAL_PURCHASE_ROLLUP_MESH",
        "SPECIAL_PURCHASE_ROLLUP_STAINLESS_STRIP",
        "SPECIAL_PURCHASE_ROLLUP_SOFT_MAGNETIC_STAINLESS_BAR",
        "SPECIAL_PURCHASE_ROLLUP_PURPLE_COPPER_STRAIGHT_TUBE",
        "SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE",
        "JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '丝网')",
        "JSON_OBJECT('field', 'material_name', 'op', 'LIKE', 'value', '拉制铜管')",
        "AUXILIARY_EXCLUDE_GREASE",
        "AUXILIARY_EXCLUDE_OIL",
        "AUXILIARY_EXCLUDE_ADHESIVE",
        "BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF",
        "NO_SCRAP_REF_MATCH");
  }

  @Test
  @DisplayName("迁移幂等且不删除旧规则表")
  void isIdempotentAndKeepsLegacyRuleTable() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS")
        .contains("v146_add_column_if_not_exists")
        .contains("v146_add_index_if_not_exists")
        .contains("WHERE NOT EXISTS")
        .contains("旧 " + LEGACY_RULE_TABLE)
        .doesNotContain("DROP TABLE " + LEGACY_RULE_TABLE)
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM " + LEGACY_RULE_TABLE);
  }

  private static String readSql() {
    try (var in = V146BomSettlementRuleSchemaSqlTest.class.getResourceAsStream(
        "/db/V146__bom_settlement_rule_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V146 SQL 失败", e);
    }
  }
}
