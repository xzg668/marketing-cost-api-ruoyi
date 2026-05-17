package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V80 月度调价批次价 DDL")
class V80PriceFactorMonthlyAdjustmentSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增调价批次和调价价格明细表")
  void createsAdjustBatchAndPriceTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_factor_adjust_batch",
        "CREATE TABLE IF NOT EXISTS lp_factor_adjust_price",
        "adjust_batch_no",
        "usage_scope",
        "REPRICE_ONLY/REPRICE_AND_DAILY",
        "apply_to_daily",
        "SYSTEM_ID/IDENTITY_FIELDS");
  }

  @Test
  @DisplayName("调价批次价和日常报价生效价在字段语义上隔离")
  void separatesAdjustPriceFromDailyEffectivePrice() {
    assertThat(SQL).contains(
        "lp_factor_monthly_price 仍只表示“日常报价生效价”",
        "REPRICE_ONLY 调价批次不写 lp_factor_monthly_price",
        "latest_adjust_batch_id",
        "source_tag");
  }

  @Test
  @DisplayName("联动价与影响因素导入批次记录用途和生效策略")
  void extendsFactorUploadBatchWithStrategy() {
    assertThat(SQL).contains(
        "lp_factor_upload_batch",
        "import_purpose",
        "effective_strategy",
        "LINKED_APPEND_ONLY",
        "LINKED_OVERRIDE_EFFECTIVE",
        "APPEND_ONLY/OVERRIDE_EFFECTIVE");
  }

  @Test
  @DisplayName("脚本包含幂等加列和加索引过程")
  void containsIdempotentAlterHelpers() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE add_column_if_not_exists_v80",
        "CREATE PROCEDURE add_index_if_not_exists_v80",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS add_column_if_not_exists_v80");
  }

  private static String readSql() {
    try (var in = V80PriceFactorMonthlyAdjustmentSchemaSqlTest.class.getResourceAsStream(
        "/db/V80__price_factor_monthly_adjustment_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V80 SQL 失败", e);
    }
  }
}
