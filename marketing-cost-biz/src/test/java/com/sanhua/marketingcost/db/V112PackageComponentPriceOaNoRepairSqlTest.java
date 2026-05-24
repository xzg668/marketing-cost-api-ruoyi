package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V112 包装组件价格 OA 字段修复")
class V112PackageComponentPriceOaNoRepairSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("幂等补齐 lp_package_component_price.oa_no")
  void addsOaNoColumnIdempotently() {
    assertThat(SQL).contains(
        "v112_add_column_if_not_exists",
        "'lp_package_component_price'",
        "'oa_no'",
        "VARCHAR(64) NOT NULL DEFAULT");
  }

  @Test
  @DisplayName("从包装组件快照回填 OA 和来源顶层产品")
  void backfillsOaAndTopProductFromSnapshot() {
    assertThat(SQL).contains(
        "LEFT JOIN lp_package_component_snapshot s ON s.id = p.snapshot_id",
        "NULLIF(s.source_oa_no, '')",
        "s.source_top_product_code");
  }

  @Test
  @DisplayName("唯一键切换到月份 + 顶层产品 + 包装父件")
  void switchesUniqueKeyToCurrentBusinessKey() {
    assertThat(SQL).contains(
        "CALL v112_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top')",
        "CALL v112_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top')",
        "UNIQUE KEY uk_pkg_price_month_top (package_material_code, period_month, source_top_product_code)",
        "KEY idx_pkg_price_month_top (period_month, source_top_product_code)");
  }

  @Test
  @DisplayName("迁移不删除整表")
  void migrationDoesNotDropTables() {
    assertThat(SQL)
        .contains("DELETE p1 FROM lp_package_component_price p1")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V112PackageComponentPriceOaNoRepairSqlTest.class.getResourceAsStream(
        "/db/V112__package_component_price_oa_no_repair.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V112 SQL 失败", e);
    }
  }
}
