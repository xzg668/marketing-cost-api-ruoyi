package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V148 BOM 旧规则表和旧追溯列删除")
class V148DropLegacyBomRuleSqlTest {

  private static final String SQL = readSql();
  private static final String LEGACY_RULE_TABLE = "bom_" + "stop_" + "drill_" + "rule";
  private static final String LEGACY_RULE_COLUMN = "matched_" + "drill_" + "rule_id";

  @Test
  @DisplayName("删除旧规则表和结算行旧追溯列")
  void dropsLegacyTableAndColumn() {
    assertThat(SQL)
        .contains("CALL v148_drop_column_if_exists('lp_bom_costing_row', '" + LEGACY_RULE_COLUMN + "')")
        .contains("DROP TABLE IF EXISTS " + LEGACY_RULE_TABLE);
  }

  @Test
  @DisplayName("删除列通过 information_schema 做幂等保护")
  void dropColumnIsIdempotent() {
    assertThat(SQL)
        .contains("INFORMATION_SCHEMA.COLUMNS")
        .contains("TABLE_SCHEMA = DATABASE()")
        .contains("DROP PROCEDURE IF EXISTS v148_drop_column_if_exists");
  }

  private static String readSql() {
    try (var in = V148DropLegacyBomRuleSqlTest.class.getResourceAsStream(
        "/db/V148__drop_legacy_bom_drill_rule.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V148 SQL 失败", e);
    }
  }
}
