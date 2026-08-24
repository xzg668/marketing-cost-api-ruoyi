package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成本任务执行轮次与前置状态SQL契约")
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
    assertThat(claim).contains("b.prerequisite_status IN ('SUCCESS', 'NOT_REQUIRED')");
    assertThat(counts).contains("b.execution_no = t.execution_no");
  }

  @Test
  @DisplayName("只有待准备或超时的报价批次可进入前置抢占")
  void quotePrerequisiteCandidatesExcludeFailedAndFreshRunningBatches() throws Exception {
    String sql = selectSql(
        CostRunBatchMapper.class.getMethod(
            "selectQuotePrerequisiteCandidates", java.time.LocalDateTime.class, int.class));

    assertThat(sql)
        .contains("scene = 'QUOTE'")
        .contains("prerequisite_status = 'PENDING'")
        .contains("prerequisite_status = 'RUNNING'")
        .contains("updated_at <= #{staleBefore}")
        .doesNotContain("prerequisite_status = 'FAILED'");
  }

  @Test
  @DisplayName("任务完成回写拒绝旧执行轮次")
  void completionRejectsOldExecution() throws Exception {
    String success = updateSql(
        CostRunTaskMapper.class.getMethod(
            "markSuccess", Long.class, String.class, String.class, java.time.LocalDateTime.class));

    assertThat(success)
        .contains("execution_no = (")
        .contains("b.batch_no = lp_cost_run_task.batch_no");
  }

  @Test
  @DisplayName("批次前置状态转换同时校验轮次、状态和控制版本")
  void prerequisiteTransitionIsOptimistic() throws Exception {
    String sql = updateSql(
        CostRunBatchMapper.class.getMethod(
            "transitionPrerequisite",
            String.class,
            int.class,
            int.class,
            String.class,
            String.class,
            String.class,
            java.time.LocalDateTime.class));

    assertThat(sql)
        .contains("execution_no = #{executionNo}")
        .contains("control_version = #{expectedControlVersion}")
        .contains("prerequisite_status = #{expectedStatus}")
        .contains("control_version = control_version + 1")
        .contains("WHEN #{nextStatus} = 'FAILED' THEN 'FAILED'")
        .contains("WHEN #{nextStatus} = 'FAILED' THEN 100");

    String taskFailure = updateSql(
        CostRunTaskMapper.class.getMethod(
            "markQuoteTasksFailedByPrerequisite",
            String.class,
            int.class,
            String.class,
            java.time.LocalDateTime.class));
    assertThat(taskFailure)
        .contains("b.prerequisite_status = 'FAILED'")
        .contains("t.execution_no = #{executionNo}")
        .contains("t.status IN ('PENDING', 'RETRYABLE')");
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
            java.time.LocalDateTime.class));

    assertThat(batchSql)
        .contains("execution_no = execution_no + 1")
        .contains("execution_no = #{expectedExecutionNo}")
        .contains("control_version = #{expectedControlVersion}")
        .contains("t.status = 'RUNNING'");
    assertThat(taskSql)
        .contains("execution_no = #{executionNo}")
        .contains("status = #{status}");
  }

  private String selectSql(Method method) {
    return String.join("\n", method.getAnnotation(Select.class).value());
  }

  private String updateSql(Method method) {
    return String.join("\n", method.getAnnotation(Update.class).value());
  }
}
