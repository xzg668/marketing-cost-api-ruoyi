package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V84 月度调价批次表修复 DDL")
class V84FactorAdjustTablesRepairSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐调价批次和调价明细表")
  void createsAdjustTablesIfMissing() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_factor_adjust_batch",
        "CREATE TABLE IF NOT EXISTS lp_factor_adjust_price",
        "adjust_batch_no",
        "usage_scope",
        "adjust_batch_id",
        "adjusted_price",
        "apply_to_daily",
        "deleted");
  }

  @Test
  @DisplayName("保留 V80 的关键索引")
  void keepsV80Indexes() {
    assertThat(SQL).contains(
        "uk_factor_adjust_batch_no",
        "idx_factor_adjust_context",
        "idx_factor_adjust_price_batch",
        "idx_factor_adjust_price_identity",
        "idx_factor_adjust_price_status",
        "idx_factor_adjust_price_source_row");
  }

  @Test
  @DisplayName("使用 IF NOT EXISTS，避免影响已有表")
  void isIdempotent() {
    assertThat(SQL)
        .contains("IF NOT EXISTS")
        .doesNotContain("DROP TABLE");
  }

  private static String readSql() {
    try (var in = V84FactorAdjustTablesRepairSqlTest.class.getResourceAsStream(
        "/db/V84__factor_adjust_tables_repair.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V84 SQL 失败", e);
    }
  }
}
