package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("月度调价压测数据脚本")
class MonthlyRepricePerfSeedSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("只清理 PERF 批次自身数据，不触碰日常 OA 结果表")
  void scriptOnlyCleansPerfBatch() {
    assertThat(SQL)
        .contains("SET @perf_reprice_no")
        .contains("DELETE FROM lp_cost_run_task WHERE scene = 'MONTHLY_REPRICE'")
        .contains("DELETE FROM lp_monthly_reprice_result WHERE reprice_no = @perf_reprice_no")
        .doesNotContain("lp_cost_run_result")
        .doesNotContain("DELETE FROM oa_form")
        .doesNotContain("TRUNCATE TABLE");
  }

  @Test
  @DisplayName("覆盖 500 对象 OA、1万/5万/10万调价和复杂 BOM 场景")
  void scriptDocumentsRequiredScenarios() {
    assertThat(SQL).contains(
        "@perf_object_count",
        "@perf_bom_lines",
        "((n - 1) DIV 500) + 1",
        "缺失影响因素价格",
        "公式异常",
        "物料缺失",
        "纸箱",
        "木箱",
        "托盘");
  }

  @Test
  @DisplayName("支持结果查询压测和 Worker 抢任务压测两种模式")
  void supportsResultAndWorkerModes() {
    assertThat(SQL).contains(
        "@perf_seed_results = 1",
        "@perf_seed_results = 0",
        "IF(@perf_seed_results = 1, 'WAIT_CONFIRM', 'RUNNING')",
        "INSERT INTO lp_cost_run_task",
        "IF(@perf_seed_results = 1, IF(n % 997 = 0, 'FAILED', 'SUCCESS'), 'PENDING')");
  }

  private static String readSql() {
    Path path = findScript();
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取压测脚本失败：" + path, e);
    }
  }

  private static Path findScript() {
    Path direct = Path.of("marketing-cost-api", "scripts", "monthly_reprice_perf_seed.sql");
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromModule = Path.of("..", "scripts", "monthly_reprice_perf_seed.sql");
    if (Files.exists(fromModule)) {
      return fromModule;
    }
    throw new IllegalStateException("找不到 monthly_reprice_perf_seed.sql");
  }
}
