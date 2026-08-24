package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V221 删除旧整单确认模型迁移")
class V221DropLegacyQuoteConfirmationTablesSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("删除四张旧确认表")
  void dropsLegacyConfirmationTables() {
    assertThat(SQL).contains(
        "DROP TABLE IF EXISTS `lp_quote_bom_confirmation_log`",
        "DROP TABLE IF EXISTS `lp_quote_price_type_confirm_item`",
        "DROP TABLE IF EXISTS `lp_quote_price_type_confirm_batch`",
        "DROP TABLE IF EXISTS `lp_quote_bom_confirmation`");
  }

  @Test
  @DisplayName("删除只关联旧确认模型的八个字段")
  void dropsLegacyReferenceColumns() {
    assertThat(SQL).contains(
        "'lp_cost_run_result', 'price_type_confirm_no'",
        "'lp_price_prepare_batch', 'price_type_confirm_no'",
        "'lp_price_prepare_gap', 'price_type_confirm_no'",
        "'lp_price_prepare_gap', 'price_type_confirm_item_id'",
        "'lp_price_prepare_item', 'price_type_confirm_no'",
        "'lp_price_prepare_item', 'price_type_confirm_item_id'",
        "'lp_quote_cost_run_version', 'bom_confirm_no'",
        "'lp_quote_cost_run_version', 'price_type_confirm_no'");
  }

  @Test
  @DisplayName("迁移可重复执行且不改写现存业务数据")
  void isIdempotentAndDoesNotRewriteBusinessData() {
    assertThat(SQL).contains(
        "v221_drop_index_if_exists",
        "v221_drop_column_if_exists",
        "INFORMATION_SCHEMA.COLUMNS",
        "INFORMATION_SCHEMA.STATISTICS");
    assertThat(SQL).doesNotContain("DELETE FROM", "TRUNCATE TABLE", "UPDATE ");
  }

  private static String readSql() {
    try (var input =
        V221DropLegacyQuoteConfirmationTablesSqlTest.class.getResourceAsStream(
            "/db/V221__drop_legacy_quote_confirmation_tables.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V221 SQL 失败", exception);
    }
  }
}
