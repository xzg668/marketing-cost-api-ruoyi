package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V191 财务价格准备中间结果隔离")
class V191FinancePricePrepareIntermediateIsolationSqlTest {
  private static final String SQL = readSql();

  @Test
  void isolatesExistingLinkedAndMakePartTablesWithoutCreatingBusinessTables() {
    assertThat(SQL)
        .contains("price_scenario_type")
        .contains("DEFAULT ''OA_LOCKED''")
        .contains("uk_make_part_price_current_as_of_scene")
        .contains("price_as_of_time, price_scenario_type")
        .contains("uk_pl_calc_quote_scene_as_of_factor")
        .contains("calc_scene, factor_source, oa_no")
        .doesNotContain("CREATE TABLE");
  }

  private static String readSql() {
    try (var in = V191FinancePricePrepareIntermediateIsolationSqlTest.class.getResourceAsStream(
        "/db/V191__finance_price_prepare_intermediate_isolation.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V191 SQL 失败", e);
    }
  }
}
