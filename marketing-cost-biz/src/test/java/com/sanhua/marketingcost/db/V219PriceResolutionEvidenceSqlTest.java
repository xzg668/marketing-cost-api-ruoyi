package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V219 取价证据迁移")
class V219PriceResolutionEvidenceSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("只固化取价来源、主供应商和历史价提醒所需字段")
  void containsMinimalResolutionEvidence() {
    assertThat(SQL).contains(
        "'lp_price_linked_calc_item', 'source_price_record_id'",
        "'lp_price_linked_calc_item', 'supply_ratio_record_id'",
        "'lp_price_linked_calc_item', 'carried_forward'",
        "'lp_price_linked_calc_item', 'failure_code'",
        "'lp_price_prepare_item', 'price_type'",
        "'lp_price_prepare_item', 'source_price_record_id'",
        "'lp_price_prepare_item', 'supply_ratio_record_id'",
        "'lp_price_prepare_item', 'carried_forward'",
        "'lp_price_prepare_gap', 'reason_code'");
  }

  @Test
  @DisplayName("迁移不复制价格主表且不改写、删除历史核算数据")
  void doesNotMutateHistoricalData() {
    assertThat(SQL)
        .doesNotContain(
            "CREATE TABLE `lp_price_",
            "INSERT INTO",
            "UPDATE lp_",
            "DELETE FROM",
            "TRUNCATE TABLE",
            "DROP TABLE");
  }

  private static String readSql() {
    try (var input = V219PriceResolutionEvidenceSqlTest.class.getResourceAsStream(
        "/db/V219__price_resolution_evidence.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V219 SQL 失败", exception);
    }
  }
}
