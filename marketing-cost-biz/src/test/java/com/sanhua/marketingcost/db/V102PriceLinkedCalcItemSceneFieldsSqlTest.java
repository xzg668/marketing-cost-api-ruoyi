package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V102 联动价计算结果场景字段 DDL")
class V102PriceLinkedCalcItemSceneFieldsSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增场景化结果上下文字段")
  void addsSceneContextColumns() {
    assertThat(SQL).contains(
        "calc_scene",
        "VARCHAR(32) NOT NULL DEFAULT 'QUOTE'",
        "pricing_month",
        "VARCHAR(7) NOT NULL DEFAULT ''",
        "adjust_batch_id",
        "factor_source",
        "calc_fingerprint",
        "calc_status",
        "calc_message");
  }

  @Test
  @DisplayName("历史数据回填为 QUOTE + OA_LOCKED + OK")
  void backfillsLegacyRowsAsQuote() {
    assertThat(SQL).contains(
        "SET calc_scene = 'QUOTE'",
        "SET factor_source = 'OA_LOCKED'",
        "SET calc_status = 'OK'",
        "SET business_unit_type = 'COMMERCIAL'");
  }

  @Test
  @DisplayName("按正常报价和月度调价两个业务上下文建立唯一键")
  void definesSceneUniqueKeys() {
    assertThat(SQL).contains(
        "uk_pl_calc_quote_scene",
        "`business_unit_type`, `calc_scene`, `oa_no`, `item_code`, `pricing_month`",
        "uk_pl_calc_adjust_scene",
        "`business_unit_type`, `calc_scene`, `adjust_batch_id`, `item_code`, `pricing_month`",
        "idx_pl_calc_fingerprint");
  }

  @Test
  @DisplayName("迁移幂等，并替换 V50 旧唯一键")
  void isIdempotentAndDropsOldUniqueKey() {
    assertThat(SQL).contains(
        "information_schema.columns",
        "information_schema.statistics",
        "CALL v102_drop_index_if_exists('lp_price_linked_calc_item', 'uk_pl_calc_oa_item_bu')",
        "DROP PROCEDURE IF EXISTS v102_add_column_if_not_exists");
  }

  private static String readSql() {
    try (var in = V102PriceLinkedCalcItemSceneFieldsSqlTest.class.getResourceAsStream(
        "/db/V102__price_linked_calc_item_scene_fields.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V102 SQL 失败", e);
    }
  }
}
