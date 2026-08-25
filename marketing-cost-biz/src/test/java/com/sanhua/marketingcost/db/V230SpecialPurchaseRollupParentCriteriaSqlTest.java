package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V230 特殊采购上卷母件条件")
class V230SpecialPurchaseRollupParentCriteriaSqlTest {

  private static final List<String> PURCHASE_CATEGORIES = List.of(
      "挤压铜棒", "不锈钢棒", "锻镦件", "软磁不锈钢棒", "铝棒", "铸造件",
      "不锈钢钢管", "无缝钢管", "紫铜直管", "焊接钢管", "金加工件", "不锈钢板带",
      "冲压拉伸件", "黄铜管", "其它管件", "钢丝", "碳钢钢棒", "注塑件",
      "其它钢材", "电镀类", "紫铜盘管", "PEEK", "连铸铜棒", "粉末冶金件",
      "紫铜板带", "漆包线", "丝网", "铜包铝漆包线");

  private static final String SQL = readSql();

  @Test
  @DisplayName("规则保留28项采购分类并增加制造母件和副产品存在条件")
  void updatesRequiredPositiveConditions() {
    assertThat(PURCHASE_CATEGORIES).hasSize(28).doesNotHaveDuplicates();
    PURCHASE_CATEGORIES.forEach(value -> assertThat(SQL).contains("'" + value + "'"));
    assertThat(SQL).contains(
        "SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION",
        "'field', 'shape_attr', 'op', 'EQ', 'value', '采购件'",
        "'field', 'shape_attr', 'op', 'EQ', 'value', '制造件'",
        "'field', 'has_byproduct', 'op', 'EQ', 'value', 'true'",
        "'parentConditions'",
        "'excludeGroups'");
  }

  @Test
  @DisplayName("三组排除条件使用正式主分类编码和子件品名包含语义")
  void updatesCompoundExclusions() {
    assertThat(SQL).contains(
        "'101001018', '111001018', '101001007', '111001007'",
        "'121151306'",
        "'分磁环'",
        "'121191304'",
        "'NOT_LIKE', 'value', '毛坯'",
        "'NOT_LIKE', 'value', '半成品'");
  }

  @Test
  @DisplayName("迁移不新增表字段且把旧工作区标记为规则变更待重算")
  void changesDataOnlyAndMarksWorkspacesStale() {
    assertThat(SQL.toUpperCase()).doesNotContain(
        "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "TRUNCATE TABLE");
    assertThat(SQL).contains(
        "UPDATE lp_quote_costing_workspace",
        "workspace_status = 'STALE'",
        "current_step = 'QUOTE_BOM'",
        "stale_reason_code = 'BOM_RULE_CHANGED'");
  }

  private static String readSql() {
    try (InputStream input = V230SpecialPurchaseRollupParentCriteriaSqlTest.class
        .getResourceAsStream("/db/V230__special_purchase_rollup_parent_criteria.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new IllegalStateException("读取 V230 SQL 失败", exception);
    }
  }
}
