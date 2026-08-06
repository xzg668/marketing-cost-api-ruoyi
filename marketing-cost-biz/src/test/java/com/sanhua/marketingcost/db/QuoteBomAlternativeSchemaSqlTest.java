package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-01 报价BOM标准/替代选择结构 SQL")
class QuoteBomAlternativeSchemaSqlTest {

  private static final String SQL = readMigrationSql();

  @Test
  @DisplayName("正式层级增加两个可空字段和替代组索引")
  void extendsRawHierarchyWithNullableAlternativeFields() {
    assertThat(SQL).contains(
        "'lp_bom_raw_hierarchy'",
        "'child_type'",
        "'VARCHAR(16) NULL COMMENT ''U9子项类型：标准/替代'''",
        "'alternative_group_key'",
        "'CHAR(64) NULL COMMENT ''同一BOM位置的标准/替代组稳定键'''",
        "'idx_bom_raw_alt_group'",
        "(`price_org_code`, `top_product_code`, `bom_purpose`, `alternative_group_key`)");
  }

  @Test
  @DisplayName("选择表覆盖报价作用域、选择版本、来源快照和审计字段")
  void createsCompleteSelectionTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS `lp_quote_bom_alternative_selection`",
        "`selection_no` VARCHAR(64) NOT NULL",
        "`oa_no` VARCHAR(64) NOT NULL",
        "`oa_form_item_id` BIGINT NOT NULL",
        "`top_product_code` VARCHAR(64) NOT NULL",
        "`period_month` CHAR(7) NOT NULL",
        "`price_org_code` VARCHAR(32) NOT NULL",
        "`alternative_group_key` CHAR(64) NOT NULL",
        "`parent_path` VARCHAR(2000) NULL",
        "`standard_material_code` VARCHAR(64) NOT NULL",
        "`selected_material_code` VARCHAR(64) NOT NULL",
        "`selected_child_type` VARCHAR(16) NOT NULL",
        "`selection_source` VARCHAR(32) NOT NULL",
        "`selection_version` INT NOT NULL",
        "`selection_status` VARCHAR(16) NOT NULL",
        "`current_slot` TINYINT NULL",
        "`candidate_snapshot_json` JSON NULL",
        "`source_import_batch_id` VARCHAR(128) NULL",
        "`source_build_batch_id` VARCHAR(128) NULL",
        "`selected_by` VARCHAR(64) NULL",
        "`selected_at` DATETIME NULL",
        "`business_unit_type` VARCHAR(32) NULL");
  }

  @Test
  @DisplayName("当前选择唯一约束和历史版本约束符合设计")
  void definesSelectionUniquenessAndLookupIndexes() {
    assertThat(SQL).contains(
        "`uk_quote_alt_selection_no`",
        "(`selection_no`)",
        "`uk_quote_alt_selection_version`",
        "(`oa_no`, `oa_form_item_id`, `top_product_code`, `period_month`, '",
        "`alternative_group_key`, `selection_version`)",
        "`uk_quote_alt_selection_current`",
        "`alternative_group_key`, `current_slot`)",
        "`idx_quote_alt_selection_item`",
        "`idx_quote_alt_selection_selected`",
        "`idx_quote_alt_selection_status`");
  }

  @Test
  @DisplayName("迁移具备存在性保护且不改写三张旧业务表")
  void migrationIsIdempotentAndDoesNotRewriteLegacyBusinessData() {
    String upper = SQL.toUpperCase();
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS",
        "v199_add_column_if_not_exists",
        "v199_add_index_if_not_exists",
        "DROP PROCEDURE IF EXISTS v199_add_index_if_not_exists;",
        "DROP PROCEDURE IF EXISTS v199_add_column_if_not_exists;");
    assertThat(upper)
        .doesNotContain("UPDATE LP_BOM_U9_SOURCE")
        .doesNotContain("UPDATE `LP_BOM_U9_SOURCE`")
        .doesNotContain("UPDATE LP_BOM_RAW_HIERARCHY")
        .doesNotContain("UPDATE `LP_BOM_RAW_HIERARCHY`")
        .doesNotContain("UPDATE LP_BOM_COSTING_ROW")
        .doesNotContain("UPDATE `LP_BOM_COSTING_ROW`")
        .doesNotContain("UPDATE LP_QUOTE_BOM_CONFIRMATION")
        .doesNotContain("UPDATE `LP_QUOTE_BOM_CONFIRMATION`")
        .doesNotContain("DELETE FROM LP_BOM_U9_SOURCE")
        .doesNotContain("DELETE FROM LP_BOM_RAW_HIERARCHY")
        .doesNotContain("DELETE FROM LP_BOM_COSTING_ROW")
        .doesNotContain("DELETE FROM LP_QUOTE_BOM_CONFIRMATION")
        .doesNotContain("INSERT INTO LP_BOM_U9_SOURCE")
        .doesNotContain("INSERT INTO LP_BOM_RAW_HIERARCHY")
        .doesNotContain("INSERT INTO LP_BOM_COSTING_ROW")
        .doesNotContain("INSERT INTO LP_QUOTE_BOM_CONFIRMATION");
  }

  private static String readMigrationSql() {
    try (var in = QuoteBomAlternativeSchemaSqlTest.class.getResourceAsStream(
        "/db/V199__quote_bom_alternative_selection.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取 V199 SQL 失败", ex);
    }
  }
}
