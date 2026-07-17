package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V185 报价价格准备取价时点快照")
class V185QuotePricePrepareAsOfSnapshotSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("价格准备批次固化统一取价时点并兼容历史批次")
  void addsBatchPriceAsOfTime() {
    assertThat(SQL)
        .contains("'lp_price_prepare_batch'")
        .contains("'price_as_of_time'")
        .contains("COALESCE(price_as_of_time, started_at, created_at, updated_at, NOW())")
        .contains("MODIFY COLUMN price_as_of_time DATETIME NOT NULL");
  }

  @Test
  @DisplayName("价格准备明细通过 current_flag 保留历史快照")
  void keepsHistoricalPrepareRows() {
    assertThat(SQL)
        .contains("'lp_price_prepare_item',\n  'current_flag'")
        .contains("'lp_price_prepare_gap',\n  'current_flag'")
        .contains("v185_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_current')")
        .contains("UNIQUE KEY uk_price_prepare_item_batch (prepare_no")
        .contains("UNIQUE KEY uk_price_prepare_gap_batch (prepare_no")
        .doesNotContain("DELETE FROM lp_price_prepare_item")
        .doesNotContain("DELETE FROM lp_price_prepare_gap");
  }

  @Test
  @DisplayName("报价联动价按取价时点分版本，避免覆盖旧核算引用")
  void versionsQuoteLinkedCalcByPriceAsOfTime() {
    assertThat(SQL)
        .contains("'lp_price_linked_calc_item'")
        .contains("UNIQUE KEY uk_pl_calc_quote_scene_as_of")
        .contains("price_as_of_time");
  }

  private static String readSql() {
    try (var in = V185QuotePricePrepareAsOfSnapshotSqlTest.class.getResourceAsStream(
        "/db/V185__quote_price_prepare_as_of_snapshot.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V185 SQL 失败", e);
    }
  }
}
