package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QEB-01 最终有效BOM和形态规则结构 SQL")
class QuoteEffectiveBomSchemaSqlTest {

  private static final String SQL = readMigrationSql();

  @Test
  @DisplayName("迁移只新增最终节点和形态规则两张业务表")
  void createsExactlyTwoBusinessTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_effective_bom_node",
        "CREATE TABLE IF NOT EXISTS lp_material_quote_shape_policy");
    assertThat(countOccurrences(SQL, "CREATE TABLE IF NOT EXISTS")).isEqualTo(2);
    assertThat(SQL)
        .doesNotContain("lp_quote_effective_bom_build")
        .doesNotContain("lp_quote_effective_bom_header");
  }

  @Test
  @DisplayName("最终节点表覆盖不可变构建、树结构、形态、供应商、替代和来源证据")
  void createsCompleteEffectiveNodeTable() {
    assertThat(SQL).contains(
        "build_batch_id VARCHAR(64) NOT NULL",
        "origin_monthly_snapshot_id BIGINT NOT NULL",
        "effective_variant_hash VARCHAR(64) NOT NULL",
        "top_product_code VARCHAR(64) NOT NULL",
        "cost_period_month CHAR(7) NOT NULL",
        "price_org_code VARCHAR(64) NOT NULL",
        "node_key VARCHAR(128) NOT NULL",
        "parent_node_key VARCHAR(128) NULL",
        "node_level INT NOT NULL",
        "sort_seq INT NOT NULL DEFAULT 0",
        "node_path VARCHAR(2000) NOT NULL",
        "qty_per_parent DECIMAL(24,8) NOT NULL",
        "qty_per_top DECIMAL(24,8) NOT NULL",
        "source_material_shape VARCHAR(32) NULL",
        "effective_material_shape VARCHAR(32) NOT NULL",
        "shape_resolution_source VARCHAR(32) NOT NULL",
        "shape_policy_id BIGINT NULL",
        "selected_supplier_ratio_id BIGINT NULL",
        "selected_supply_ratio DECIMAL(12,6) NULL",
        "alternative_group_key VARCHAR(128) NULL",
        "alternative_selection_id BIGINT NULL",
        "source_bom_type VARCHAR(32) NOT NULL",
        "source_hierarchy_id BIGINT NULL",
        "UNIQUE KEY uk_build_node (build_batch_id, node_key)",
        "KEY idx_variant_hash (effective_variant_hash)",
        "KEY idx_top_month (top_product_code, cost_period_month)",
        "KEY idx_material_code (material_code)",
        "KEY idx_origin_snapshot (origin_monthly_snapshot_id)");

    String effectiveTable = tableDefinition(
        "lp_quote_effective_bom_node", "lp_material_quote_shape_policy");
    assertThat(effectiveTable)
        .doesNotContain("oa_no")
        .doesNotContain("oa_form_item_id")
        .doesNotContain("customer_code");
  }

  @Test
  @DisplayName("形态规则表覆盖组织料号、模式、JSON动作和月份索引")
  void createsCompleteShapePolicyTable() {
    assertThat(SQL).contains(
        "material_org_code VARCHAR(64) NOT NULL",
        "material_code VARCHAR(64) NOT NULL",
        "policy_mode VARCHAR(32) NOT NULL",
        "fixed_target_shape VARCHAR(32) NULL",
        "condition_config_json JSON NULL",
        "action_config_json JSON NULL",
        "effective_from_month CHAR(7) NOT NULL",
        "effective_to_month CHAR(7) NULL",
        "enabled TINYINT NOT NULL DEFAULT 1",
        "remark VARCHAR(1000) NULL",
        "KEY idx_material_month (material_org_code, material_code,"
            + " effective_from_month, effective_to_month, enabled)");
  }

  @Test
  @DisplayName("现有月度卡片、确认和替代选择只补必要字段")
  void extendsExistingTablesWithRequiredTraceFields() {
    assertThat(SQL).contains(
        "'lp_quote_bom_monthly_snapshot'",
        "'freeze_status'",
        "freeze_status VARCHAR(16) NOT NULL DEFAULT ''DRAFT''",
        "'effective_build_batch_id'",
        "effective_build_batch_id VARCHAR(64) NULL",
        "'effective_variant_hash'",
        "effective_variant_hash VARCHAR(64) NULL",
        "'frozen_at'",
        "frozen_at DATETIME NULL",
        "'frozen_by'",
        "frozen_by BIGINT NULL",
        "'lp_quote_bom_confirmation'",
        "'costing_build_batch_id'",
        "costing_build_batch_id VARCHAR(64) NULL",
        "'lp_quote_bom_alternative_selection'",
        "'inherited_monthly_snapshot_id'",
        "inherited_monthly_snapshot_id BIGINT NULL");
    assertThat(SQL)
        .doesNotContain("ADD COLUMN business_unit_type")
        .doesNotContain("RENAME COLUMN selection_source");
  }

  @Test
  @DisplayName("迁移具备存在性保护且不改写历史业务数据")
  void isIdempotentAndDoesNotRewriteHistoricalBusinessData() {
    String upper = SQL.toUpperCase();
    assertThat(SQL).contains(
        "CREATE PROCEDURE v202_add_column_if_not_exists",
        "CREATE PROCEDURE v202_add_index_if_not_exists",
        "information_schema.TABLES",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS v202_add_column_if_not_exists",
        "DROP PROCEDURE IF EXISTS v202_add_index_if_not_exists");
    assertThat(upper)
        .doesNotContain("UPDATE LP_QUOTE_BOM_MONTHLY_SNAPSHOT")
        .doesNotContain("UPDATE LP_QUOTE_BOM_ALTERNATIVE_SELECTION")
        .doesNotContain("UPDATE LP_QUOTE_BOM_CONFIRMATION")
        .doesNotContain("UPDATE LP_BOM_COSTING_ROW")
        .doesNotContain("DELETE FROM LP_QUOTE_BOM_MONTHLY_SNAPSHOT")
        .doesNotContain("DELETE FROM LP_QUOTE_BOM_ALTERNATIVE_SELECTION")
        .doesNotContain("DELETE FROM LP_QUOTE_BOM_CONFIRMATION")
        .doesNotContain("DELETE FROM LP_BOM_COSTING_ROW")
        .doesNotContain("INSERT INTO LP_QUOTE_BOM_MONTHLY_SNAPSHOT")
        .doesNotContain("INSERT INTO LP_QUOTE_BOM_ALTERNATIVE_SELECTION")
        .doesNotContain("INSERT INTO LP_QUOTE_BOM_CONFIRMATION")
        .doesNotContain("INSERT INTO LP_BOM_COSTING_ROW");
  }

  private static String tableDefinition(String startTable, String nextTable) {
    int start = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + startTable);
    int end = SQL.indexOf("CREATE TABLE IF NOT EXISTS " + nextTable);
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    return SQL.substring(start, end);
  }

  private static int countOccurrences(String value, String token) {
    int count = 0;
    int from = 0;
    while ((from = value.indexOf(token, from)) >= 0) {
      count++;
      from += token.length();
    }
    return count;
  }

  private static String readMigrationSql() {
    try (var in = QuoteEffectiveBomSchemaSqlTest.class.getResourceAsStream(
        "/db/V202__quote_effective_bom_and_shape_policy.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取 V202 SQL 失败", ex);
    }
  }
}
