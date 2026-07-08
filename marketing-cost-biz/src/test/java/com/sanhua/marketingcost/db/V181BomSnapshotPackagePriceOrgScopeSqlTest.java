package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V181 BOM 快照和包装组件价格组织隔离")
class V181BomSnapshotPackagePriceOrgScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("三张复用表补 price_org_code 且不默认商用")
  void addsPriceOrgCodeColumns() {
    assertThat(SQL).contains(
        "'lp_quote_bom_monthly_snapshot'",
        "'lp_package_component_snapshot'",
        "'lp_package_component_price'",
        "'price_org_code'",
        "price_org_code VARCHAR(32) DEFAULT NULL");
    assertThat(SQL)
        .doesNotContain("DEFAULT ''210''")
        .doesNotContain("SET price_org_code = ''210''")
        .doesNotContain("p.price_org_code = COALESCE(NULLIF(p.price_org_code, ''''), NULLIF(s.price_org_code, ''''), ''210'')");
  }

  @Test
  @DisplayName("BOM 月度快照复用索引包含组织")
  void bomMonthlySnapshotIndexIncludesOrganization() {
    assertThat(SQL).contains(
        "idx_quote_bom_monthly_key",
        "KEY idx_quote_bom_monthly_key (product_code, price_org_code, customer_code, package_method, cost_period_month, active_flag)");
  }

  @Test
  @DisplayName("包装组件结构和价格唯一键包含组织")
  void packageSnapshotAndPriceUniqueKeysIncludeOrganization() {
    assertThat(SQL).contains(
        "uk_pkg_snapshot_month_top_org",
        "UNIQUE KEY uk_pkg_snapshot_month_top_org (package_material_code, period_month, source_top_product_code, price_org_code)",
        "uk_pkg_price_month_top_org_as_of",
        "UNIQUE KEY uk_pkg_price_month_top_org_as_of (package_material_code, period_month, source_top_product_code, price_org_code, price_as_of_time)");
  }

  @Test
  @DisplayName("迁移幂等且不清空历史数据")
  void isIdempotentAndDoesNotClearData() {
    assertThat(SQL)
        .contains(
            "CREATE PROCEDURE v181_add_column_if_not_exists",
            "CREATE PROCEDURE v181_modify_column_if_exists",
            "CREATE PROCEDURE v181_drop_index_if_exists",
            "CREATE PROCEDURE v181_add_index_if_not_exists",
            "CREATE PROCEDURE v181_execute_if_table_exists",
            "CREATE PROCEDURE v181_execute_if_tables_exist",
            "information_schema.COLUMNS",
            "information_schema.STATISTICS")
        .doesNotContain("DROP TABLE lp_quote_bom_monthly_snapshot")
        .doesNotContain("DROP TABLE lp_package_component_snapshot")
        .doesNotContain("DROP TABLE lp_package_component_price")
        .doesNotContain("TRUNCATE TABLE");
  }

  @Test
  @DisplayName("历史空组织不回填为 210")
  void doesNotBackfillBlankOrganization() {
    assertThat(SQL)
        .doesNotContain("SET price_org_code = ''210''")
        .doesNotContain("DEFAULT ''210''")
        .doesNotContain("COALESCE(NULLIF(p.price_org_code");
  }

  private static String readSql() {
    try (var in = V181BomSnapshotPackagePriceOrgScopeSqlTest.class.getResourceAsStream(
        "/db/V181__bom_snapshot_package_price_org_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V181 SQL 失败", e);
    }
  }
}
