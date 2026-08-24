package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V218 报价核算工作区与执行隔离迁移")
class V218QuoteCostingWorkspaceSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("当前工作区按产品行和月份唯一且具备乐观锁")
  void workspaceIsUniqueAndVersioned() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS `lp_quote_costing_workspace`",
        "UNIQUE KEY `uk_quote_costing_workspace_item_month` (`oa_form_item_id`, `period_month`)",
        "`lock_version` INT NOT NULL DEFAULT 0",
        "`current_prepare_no` VARCHAR(64)",
        "`current_cost_version_id` BIGINT");
  }

  @Test
  @DisplayName("批次与任务同时增加执行轮次且批次包含前置控制状态")
  void queueHasExecutionAndPrerequisiteGuards() {
    assertThat(SQL).contains(
        "'lp_cost_run_batch', 'execution_no'",
        "'lp_cost_run_batch', 'prerequisite_status'",
        "'lp_cost_run_batch', 'control_version'",
        "'lp_cost_run_task', 'execution_no'",
        "idx_cost_run_task_batch_execution");
  }

  @Test
  @DisplayName("迁移不改写历史成功结果、最终价格或确认指针")
  void doesNotMutateHistoricalResults() {
    assertThat(SQL)
        .doesNotContain(
            "UPDATE oa_form_item",
            "DELETE FROM",
            "TRUNCATE TABLE",
            "DROP TABLE",
            "UPDATE lp_quote_cost_run_version",
            "UPDATE lp_price_prepare_batch",
            "UPDATE lp_price_prepare_item");
  }

  private static String readSql() {
    try (var input = V218QuoteCostingWorkspaceSqlTest.class.getResourceAsStream(
        "/db/V218__quote_costing_workspace_and_execution_guard.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V218 SQL 失败", exception);
    }
  }
}
