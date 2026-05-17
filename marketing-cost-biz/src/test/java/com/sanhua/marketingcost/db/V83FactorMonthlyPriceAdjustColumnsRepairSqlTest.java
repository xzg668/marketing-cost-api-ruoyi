package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V83 月度价格最近调价字段修复 DDL")
class V83FactorMonthlyPriceAdjustColumnsRepairSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐月度价格最近调价字段")
  void repairsMonthlyPriceAdjustColumns() {
    assertThat(SQL).contains(
        "lp_factor_monthly_price",
        "latest_adjust_batch_id",
        "latest_adjust_source_type",
        "latest_adjusted_by",
        "latest_adjusted_at",
        "source_tag");
  }

  @Test
  @DisplayName("补齐月度价格变更日志的调价批次字段")
  void repairsChangeLogAdjustColumns() {
    assertThat(SQL).contains(
        "lp_factor_monthly_price_change_log",
        "adjust_batch_id",
        "source_type");
  }

  @Test
  @DisplayName("补齐相关查询索引")
  void repairsIndexes() {
    assertThat(SQL).contains(
        "idx_factor_monthly_latest_adjust",
        "KEY idx_factor_monthly_latest_adjust (latest_adjust_batch_id)",
        "idx_factor_monthly_source_tag",
        "KEY idx_factor_monthly_source_tag (source_tag)",
        "idx_factor_price_log_adjust_batch",
        "KEY idx_factor_price_log_adjust_batch (adjust_batch_id)");
  }

  @Test
  @DisplayName("修复脚本幂等，并且表不存在时不会执行 ALTER")
  void isIdempotentAndTableAware() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE add_column_if_not_exists_v83",
        "CREATE PROCEDURE add_index_if_not_exists_v83",
        "information_schema.TABLES",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS add_column_if_not_exists_v83");
  }

  private static String readSql() {
    try (var in = V83FactorMonthlyPriceAdjustColumnsRepairSqlTest.class.getResourceAsStream(
        "/db/V83__factor_monthly_price_adjust_columns_repair.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V83 SQL 失败", e);
    }
  }
}
