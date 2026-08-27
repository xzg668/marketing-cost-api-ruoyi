package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成本任务执行轮次与历史归档SQL契约")
class CostRunExecutionGuardSqlContractTest {

  @Test
  @DisplayName("任务领取和进度只读取批次当前执行轮次")
  void claimAndProgressUseCurrentExecutionOnly() throws Exception {
    String claim = selectSql(
        CostRunTaskMapper.class.getMethod(
            "selectClaimCandidates", java.util.Set.class, java.time.LocalDateTime.class, int.class));
    String counts = selectSql(
        CostRunTaskMapper.class.getMethod("selectStatusCounts", String.class));

    assertThat(claim).contains("b.execution_no = t.execution_no");
    assertThat(counts).contains("b.execution_no = t.execution_no");
  }

  @Test
  @DisplayName("任务完成回写拒绝旧执行轮次")
  void completionRejectsOldExecution() throws Exception {
    String success = updateSql(
        CostRunTaskMapper.class.getMethod(
            "markSuccess",
            Long.class,
            String.class,
            Long.class,
            String.class,
            String.class,
            java.time.LocalDateTime.class));

    assertThat(success)
        .contains("cost_run_version_id = #{costRunVersionId}")
        .contains("cost_run_no = #{costRunNo}")
        .contains("execution_no = (")
        .contains("b.batch_no = lp_cost_run_task.batch_no");
  }

  @Test
  @DisplayName("报价重提原子开启新轮次，并拒绝覆盖运行中的产品任务")
  void quoteResubmitStartsNewExecutionAndRejectsRunningTasks() throws Exception {
    String batchSql = updateSql(
        CostRunBatchMapper.class.getMethod(
            "resetQuoteBatchForRerun",
            String.class,
            int.class,
            int.class,
            int.class,
            int.class,
            String.class,
            java.time.LocalDateTime.class));
    String taskSql = updateSql(
        CostRunTaskMapper.class.getMethod(
            "resetQuoteTasksForRerun",
            String.class,
            java.util.List.class,
            int.class,
            String.class,
            String.class,
            String.class,
            java.time.LocalDateTime.class));

    assertThat(batchSql)
        .contains("execution_no = execution_no + 1")
        .contains("execution_no = #{expectedExecutionNo}")
        .contains("control_version = #{expectedControlVersion}")
        .contains("t.status = 'RUNNING'");
    assertThat(taskSql)
        .contains("execution_no = #{executionNo}")
        .contains("status = #{status}")
        .contains("request_snapshot_json = #{requestSnapshotJson}");
  }

  @Test
  @DisplayName("重跑前按批次和执行轮次归档不可变历史")
  void executionHistoryUsesStableUniqueKeys() throws Exception {
    String batchArchive = insertSql(CostRunBatchMapper.class.getMethod(
        "archiveExecution", String.class, int.class, java.time.LocalDateTime.class));
    String taskArchive = insertSql(CostRunTaskMapper.class.getMethod(
        "archiveExecutionTasks", String.class, int.class, java.time.LocalDateTime.class));

    assertThat(batchArchive)
        .contains("INSERT IGNORE INTO lp_cost_run_execution_history")
        .contains("execution_no = #{executionNo}");
    assertThat(taskArchive)
        .contains("INSERT IGNORE INTO lp_cost_run_task_history")
        .contains("execution_no = #{executionNo}");
  }

  private String selectSql(Method method) {
    return String.join("\n", method.getAnnotation(Select.class).value());
  }

  private String updateSql(Method method) {
    return String.join("\n", method.getAnnotation(Update.class).value());
  }

  private String insertSql(Method method) {
    return String.join("\n", method.getAnnotation(Insert.class).value());
  }
}
