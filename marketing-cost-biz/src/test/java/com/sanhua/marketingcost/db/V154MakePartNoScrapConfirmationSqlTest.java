package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V154 制造件无废料人工确认表")
class V154MakePartNoScrapConfirmationSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增无废料人工确认表和审计字段")
  void createsNoScrapConfirmationTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_make_part_no_scrap_confirmation",
        "business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型'",
        "material_no VARCHAR(64) NOT NULL COMMENT '被确认无废料的制造件子项/原材料料号'",
        "effective_from_month VARCHAR(7) NOT NULL COMMENT '生效起始月份 YYYY-MM'",
        "effective_to_month VARCHAR(7) DEFAULT NULL COMMENT '生效结束月份 YYYY-MM；为空表示持续有效'",
        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/REVOKED'",
        "confirm_reason VARCHAR(500) NOT NULL COMMENT '人工确认原因'",
        "confirmed_by VARCHAR(64) DEFAULT NULL COMMENT '确认人'",
        "revoked_by VARCHAR(64) DEFAULT NULL COMMENT '撤销人'");
  }

  @Test
  @DisplayName("ACTIVE 唯一键限制同一事业部料号同一开始月重复生效")
  void definesActiveUniqueKey() {
    assertThat(SQL).contains(
        "active_effective_from_month VARCHAR(7)",
        "CASE WHEN status = 'ACTIVE' THEN effective_from_month ELSE NULL END",
        "UNIQUE KEY uk_no_scrap_active_month",
        "business_unit_type",
        "material_no",
        "active_effective_from_month");
  }

  @Test
  @DisplayName("新增价格准备确认和撤销权限")
  void insertsPricePreparePermissions() {
    assertThat(SQL).contains(
        "40473, '价格准备 确认无废料'",
        "cost:price-prepare:no-scrap-confirm",
        "40474, '价格准备 撤销无废料确认'",
        "cost:price-prepare:no-scrap-revoke",
        "WHERE menu_id IN (40456, 40460)");
  }

  @Test
  @DisplayName("迁移不删除历史价格准备或CMS映射数据")
  void doesNotDestroyExistingData() {
    assertThat(SQL)
        .contains("人工确认")
        .contains("lp_material_scrap_ref")
        .contains("ON DUPLICATE KEY UPDATE")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_material_scrap_ref")
        .doesNotContain("DELETE FROM lp_price_prepare_gap");
  }

  private static String readSql() {
    try (var in = V154MakePartNoScrapConfirmationSqlTest.class.getResourceAsStream(
        "/db/V154__make_part_no_scrap_confirmation.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V154 SQL 失败", e);
    }
  }
}
