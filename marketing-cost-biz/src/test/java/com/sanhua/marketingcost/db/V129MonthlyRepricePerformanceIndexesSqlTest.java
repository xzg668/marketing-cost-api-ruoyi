package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V129 月度调价性能索引")
class V129MonthlyRepricePerformanceIndexesSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐 Worker 抢任务和任务分页索引")
  void addsTaskPerformanceIndexes() {
    assertThat(SQL).contains(
        "idx_reprice_task_claim_perf (status, lock_expire_time, id)",
        "idx_reprice_task_page (reprice_no, id)",
        "idx_reprice_task_status_page (reprice_no, status, id)");
  }

  @Test
  @DisplayName("补齐结果分页和明细下钻索引")
  void addsResultAndDetailIndexes() {
    assertThat(SQL).contains(
        "idx_reprice_result_page (reprice_no, id)",
        "idx_reprice_result_status_page (reprice_no, calc_status, id)",
        "idx_reprice_result_product_page (reprice_no, product_code, id)",
        "idx_monthly_part_drilldown (reprice_no, calc_object_key, line_no, id)",
        "idx_monthly_cost_drilldown (reprice_no, calc_object_key, line_no, id)",
        "idx_monthly_audit_page (reprice_no, operation_time, id)");
  }

  @Test
  @DisplayName("迁移幂等且不修改日常 OA 结果数据")
  void isIdempotentAndDoesNotTouchDailyResults() {
    assertThat(SQL)
        .contains("add_index_if_not_exists_v129")
        .contains("NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_cost_run_result")
        .doesNotContain("UPDATE lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V129MonthlyRepricePerformanceIndexesSqlTest.class.getResourceAsStream(
        "/db/V129__monthly_reprice_performance_indexes.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V129 SQL 失败", e);
    }
  }
}
