package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V139 月度调价任务收敛到通用任务表")
class V139MonthlyRepriceTaskConvergeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("迁移旧任务后删除 lp_monthly_reprice_task")
  void migratesAndDropsLegacyMonthlyTaskTable() {
    assertThat(SQL).contains(
        "INSERT IGNORE INTO lp_cost_run_batch",
        "INSERT IGNORE INTO lp_cost_run_task",
        "'MONTHLY_REPRICE' AS scene",
        "DROP TABLE IF EXISTS lp_monthly_reprice_task");
  }

  @Test
  @DisplayName("状态兼容 CANCELLED 到通用 CANCELED")
  void mapsLegacyCancelledStatus() {
    assertThat(SQL).contains(
        "WHEN b.status = 'CANCELLED' THEN 'CANCELED'",
        "WHEN t.status = 'CANCELLED' THEN 'CANCELED'");
  }

  private static String readSql() {
    try (var input =
        V139MonthlyRepriceTaskConvergeSqlTest.class
            .getClassLoader()
            .getResourceAsStream("db/V139__monthly_reprice_task_converge_to_cost_run_task.sql")) {
      if (input == null) {
        throw new IllegalStateException("找不到 V139__monthly_reprice_task_converge_to_cost_run_task.sql");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V139 SQL 失败", e);
    }
  }
}
