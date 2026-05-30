package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V138 通用成本核算任务抢占性能索引")
class V138CostRunTaskClaimPerformanceIndexSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("幂等补齐 scene + status + lock_expire_time 抢占索引")
  void addsSceneClaimIndexIdempotently() {
    assertThat(SQL).contains(
        "DROP PROCEDURE IF EXISTS add_index_if_not_exists_v138",
        "information_schema.STATISTICS",
        "idx_cost_run_task_scene_claim",
        "KEY idx_cost_run_task_scene_claim (scene, status, lock_expire_time, id)");
  }

  @Test
  @DisplayName("只补索引，不改业务结果数据")
  void doesNotTouchBusinessResults() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_monthly_reprice_result");
  }

  private static String readSql() {
    try (var in = V138CostRunTaskClaimPerformanceIndexSqlTest.class.getResourceAsStream(
        "/db/V138__cost_run_task_claim_performance_index.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V138 SQL 失败", e);
    }
  }
}
