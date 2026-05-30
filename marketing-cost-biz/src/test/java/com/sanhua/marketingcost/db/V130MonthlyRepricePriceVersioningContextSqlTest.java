package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V130 月度调价价格版本化上下文")
class V130MonthlyRepricePriceVersioningContextSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("批次表固化统一取价时点和 BOM 来源策略")
  void addsMonthlyRepriceContextColumns() {
    assertThat(SQL).contains(
        "price_as_of_time",
        "本批次统一取价时点",
        "bom_source_policy",
        "HISTORICAL_OA_BOM",
        "idx_monthly_reprice_price_as_of");
  }

  @Test
  @DisplayName("价格路由、固定价、区间价补齐版本化查询索引")
  void addsPriceVersionLookupIndexes() {
    assertThat(SQL).contains(
        "idx_price_type_version_lookup",
        "idx_price_fixed_version_lookup",
        "idx_price_range_version_lookup",
        "effective_from",
        "effective_to");
  }

  @Test
  @DisplayName("迁移幂等且不修改日常 OA 成本结果")
  void isIdempotentAndDoesNotTouchDailyCostResults() {
    assertThat(SQL)
        .contains("add_column_if_not_exists_v130")
        .contains("add_index_if_not_exists_v130")
        .contains("NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_cost_run_result")
        .doesNotContain("UPDATE lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V130MonthlyRepricePriceVersioningContextSqlTest.class.getResourceAsStream(
        "/db/V130__monthly_reprice_price_versioning_context.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V130 SQL 失败", e);
    }
  }
}
