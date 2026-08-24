package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V224 特殊采购子件财务分类上卷规则")
class V224SpecialPurchaseRollupFinanceClassificationSqlTest {

  private static final List<String> PURCHASE_CATEGORIES = List.of(
      "挤压铜棒", "不锈钢棒", "锻镦件", "软磁不锈钢棒", "铝棒", "铸造件",
      "不锈钢钢管", "无缝钢管", "紫铜直管", "焊接钢管", "金加工件", "不锈钢板带",
      "冲压拉伸件", "黄铜管", "其它管件", "钢丝", "碳钢钢棒", "注塑件",
      "其它钢材", "电镀类", "紫铜盘管", "PEEK", "连铸铜棒", "粉末冶金件",
      "紫铜板带", "漆包线", "丝网", "铜包铝漆包线");

  private static final List<String> EXCLUDED_MAIN_CATEGORIES = List.of(
      "121191304", "121181508", "151511373", "151521376",
      "121151306", "171721412", "121181301", "171751410");

  private static final String SQL = readSql();

  @Test
  @DisplayName("停用全部旧特殊采购上卷规则并保留历史记录")
  void disablesLegacySpecialPurchaseRules() {
    assertThat(SQL).contains(
        "WHERE rule_category = 'SPECIAL_PURCHASE_ROLLUP'",
        "AND rule_code <> 'SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION'",
        "SET enabled = 0",
        "deleted = 1");
    assertThat(SQL.toUpperCase()).doesNotContain("DELETE FROM LP_BOM_SETTLEMENT_RULE");
  }

  @Test
  @DisplayName("新规则使用采购形态、28项采购分类名称和8项主分类排除代码")
  void seedsFinanceClassificationRuleWithCompleteLists() {
    assertThat(PURCHASE_CATEGORIES).hasSize(28).doesNotHaveDuplicates();
    assertThat(EXCLUDED_MAIN_CATEGORIES).hasSize(8).doesNotHaveDuplicates();
    assertThat(SQL).contains(
        "SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION",
        "'field', 'shape_attr', 'op', 'EQ', 'value', '采购件'",
        "'field', 'purchase_category'",
        "'op', 'IN'",
        "'field', 'main_category_code', 'op', 'NOT_BLANK'",
        "'op', 'NOT_IN'",
        "'ROLLUP_TO_PARENT'",
        "'SPECIAL_ROLLUP_PARENT'",
        "'SPECIAL_ROLLUP_CHILD'");
    PURCHASE_CATEGORIES.forEach(value -> assertThat(SQL).contains("'" + value + "'"));
    EXCLUDED_MAIN_CATEGORIES.forEach(value -> assertThat(SQL).contains("'" + value + "'"));
  }

  @Test
  @DisplayName("迁移幂等且不修改表结构")
  void isIdempotentDataOnlyMigration() {
    assertThat(SQL).contains("ON DUPLICATE KEY UPDATE", "deleted = VALUES(deleted)");
    assertThat(SQL.toUpperCase()).doesNotContain(
        "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "TRUNCATE TABLE");
  }

  private static String readSql() {
    try (InputStream input = V224SpecialPurchaseRollupFinanceClassificationSqlTest.class
        .getResourceAsStream("/db/V224__special_purchase_rollup_finance_classification.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("读取 V224 SQL 失败", e);
    }
  }
}
