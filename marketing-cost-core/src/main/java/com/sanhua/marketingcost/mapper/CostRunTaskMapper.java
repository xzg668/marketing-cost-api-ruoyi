package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.entity.CostRunTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CostRunTaskMapper extends BaseMapper<CostRunTask> {

  @Insert("""
      INSERT IGNORE INTO lp_cost_run_task (
        batch_no,
        execution_no,
        scene,
        source_no,
        calc_object_key,
        oa_no,
        oa_form_item_id,
        product_code,
        package_method,
        customer_name,
        business_unit_type,
        pricing_month,
        price_as_of_time,
        adjust_batch_id,
        bom_source_policy,
        status,
        progress,
        retry_count,
        max_retry_count,
        request_snapshot_json,
        created_at,
        updated_at
      ) VALUES (
        #{task.batchNo},
        #{task.executionNo},
        #{task.scene},
        #{task.sourceNo},
        #{task.calcObjectKey},
        #{task.oaNo},
        #{task.oaFormItemId},
        #{task.productCode},
        #{task.packageMethod},
        #{task.customerName},
        #{task.businessUnitType},
        #{task.pricingMonth},
        #{task.priceAsOfTime},
        #{task.adjustBatchId},
        #{task.bomSourcePolicy},
        #{task.status},
        #{task.progress},
        #{task.retryCount},
        #{task.maxRetryCount},
        #{task.requestSnapshotJson},
        #{task.createdAt},
        #{task.updatedAt}
      )
      """)
  int insertIgnore(@Param("task") CostRunTask task);

  @Select("""
      <script>
      SELECT t.*
        FROM lp_cost_run_task t
        JOIN lp_cost_run_batch b
         ON b.batch_no = t.batch_no
         AND b.execution_no = t.execution_no
         AND b.status IN ('PENDING', 'RUNNING', 'PARTIAL_FAILED')
         AND b.prerequisite_status IN ('SUCCESS', 'NOT_REQUIRED')
       WHERE t.scene IN
        <foreach collection="scenes" item="scene" open="(" separator="," close=")">
          #{scene}
        </foreach>
         AND (
           t.status IN ('PENDING', 'RETRYABLE')
           OR (
             t.status = 'RUNNING'
             AND t.lock_expire_time IS NOT NULL
             AND t.lock_expire_time &lt;= #{now}
           )
         )
       ORDER BY t.id ASC
       LIMIT #{limit}
      </script>
      """)
  List<CostRunTask> selectClaimCandidates(
      @Param("scenes") Set<String> scenes,
      @Param("now") LocalDateTime now,
      @Param("limit") int limit);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'RUNNING',
             progress = CASE WHEN progress < 1 THEN 1 ELSE progress END,
             worker_id = #{workerId},
             locked_at = #{lockedAt},
             lock_expire_time = #{lockExpireTime},
             started_at = COALESCE(started_at, #{lockedAt}),
             finished_at = NULL,
             updated_at = #{lockedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND (
           status IN ('PENDING', 'RETRYABLE')
           OR (
             status = 'RUNNING'
             AND lock_expire_time IS NOT NULL
             AND lock_expire_time <= #{lockedAt}
           )
         )
      """)
  int claimTask(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("lockedAt") LocalDateTime lockedAt,
      @Param("lockExpireTime") LocalDateTime lockExpireTime);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'SUCCESS',
             progress = 100,
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             result_summary_json = #{resultSummaryJson},
             error_message = NULL,
             error_stack = NULL,
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND status = 'RUNNING'
         AND worker_id = #{workerId}
      """)
  int markSuccess(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("resultSummaryJson") String resultSummaryJson,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'COLLABORATION',
             progress = 100,
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             result_summary_json = #{resultSummaryJson},
             error_message = #{message},
             error_stack = NULL,
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND status = 'RUNNING'
         AND worker_id = #{workerId}
      """)
  int markCollaboration(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("resultSummaryJson") String resultSummaryJson,
      @Param("message") String message,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET progress = #{progress},
             updated_at = #{updatedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND status = 'RUNNING'
         AND worker_id = #{workerId}
      """)
  int updateProgress(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("progress") int progress,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'RETRYABLE',
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             retry_count = retry_count + 1,
             error_message = #{errorMessage},
             error_stack = #{errorStack},
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND status = 'RUNNING'
         AND worker_id = #{workerId}
         AND retry_count + 1 < max_retry_count
      """)
  int markRetryable(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("errorMessage") String errorMessage,
      @Param("errorStack") String errorStack,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'FAILED',
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             retry_count = CASE
               WHEN retry_count < max_retry_count THEN retry_count + 1
               ELSE retry_count
             END,
             error_message = #{errorMessage},
             error_stack = #{errorStack},
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE id = #{taskId}
         AND execution_no = (
           SELECT b.execution_no FROM lp_cost_run_batch b
            WHERE b.batch_no = lp_cost_run_task.batch_no
         )
         AND status = 'RUNNING'
         AND worker_id = #{workerId}
      """)
  int markFailure(
      @Param("taskId") Long taskId,
      @Param("workerId") String workerId,
      @Param("errorMessage") String errorMessage,
      @Param("errorStack") String errorStack,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task t
      JOIN lp_cost_run_batch b
        ON b.batch_no = t.batch_no
       AND b.execution_no = t.execution_no
       AND b.scene = 'QUOTE'
       AND b.prerequisite_status = 'FAILED'
         SET t.status = 'FAILED',
             t.progress = 100,
             t.worker_id = NULL,
             t.locked_at = NULL,
             t.lock_expire_time = NULL,
             t.error_message = #{errorMessage},
             t.error_stack = NULL,
             t.finished_at = #{finishedAt},
             t.updated_at = #{finishedAt}
       WHERE t.batch_no = #{batchNo}
         AND t.execution_no = #{executionNo}
         AND t.status IN ('PENDING', 'RETRYABLE')
      """)
  int markQuoteTasksFailedByPrerequisite(
      @Param("batchNo") String batchNo,
      @Param("executionNo") int executionNo,
      @Param("errorMessage") String errorMessage,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'PENDING',
             progress = 0,
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             retry_count = 0,
             error_message = NULL,
             error_stack = NULL,
             result_summary_json = NULL,
             started_at = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
         AND status IN ('FAILED', 'PARTIAL_FAILED', 'CANCELED', 'RETRYABLE', 'SUCCESS')
      """)
  int resetBatchTasksForRetry(
      @Param("batchNo") String batchNo,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      <script>
      UPDATE lp_cost_run_task
         SET execution_no = #{executionNo},
             status = #{status},
             progress = CASE WHEN #{status} = 'SKIPPED_CURRENT' THEN 100 ELSE 0 END,
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             retry_count = 0,
             error_message = NULL,
             error_stack = NULL,
             result_summary_json = NULL,
             started_at = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
         AND calc_object_key IN
        <foreach collection="calcObjectKeys" item="key" open="(" separator="," close=")">
          #{key}
        </foreach>
         AND status IN (
           'PENDING', 'SUCCESS', 'COLLABORATION', 'SKIPPED_CURRENT',
           'FAILED', 'CANCELED', 'RETRYABLE'
         )
      </script>
      """)
  int resetQuoteTasksForRerun(
      @Param("batchNo") String batchNo,
      @Param("calcObjectKeys") List<String> calcObjectKeys,
      @Param("executionNo") int executionNo,
      @Param("status") String status,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Select("""
      SELECT t.*
        FROM lp_cost_run_task t
        JOIN lp_cost_run_batch b
          ON b.batch_no = t.batch_no
         AND b.execution_no = t.execution_no
       WHERE t.scene = 'QUOTE'
         AND t.oa_no = #{oaNo}
         AND t.oa_form_item_id = #{oaFormItemId}
         AND t.pricing_month = #{pricingMonth}
       ORDER BY t.id DESC
       LIMIT 1
      """)
  CostRunTask selectCurrentQuoteTask(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("pricingMonth") String pricingMonth);

  @Select("""
      SELECT t.status AS status, COUNT(*) AS count
        FROM lp_cost_run_task t
        JOIN lp_cost_run_batch b
          ON b.batch_no = t.batch_no
         AND b.execution_no = t.execution_no
       WHERE t.batch_no = #{batchNo}
       GROUP BY t.status
      """)
  List<CostRunTaskStatusCount> selectStatusCounts(@Param("batchNo") String batchNo);

  @Select("""
      SELECT status AS status, COUNT(*) AS count
        FROM lp_cost_run_task
       WHERE scene = 'MONTHLY_REPRICE'
         AND source_no = #{repriceNo}
       GROUP BY status
      """)
  List<CostRunTaskStatusCount> selectMonthlyRepriceStatusCounts(
      @Param("repriceNo") String repriceNo);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'CANCELED',
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             error_message = '批次已取消',
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE scene = 'MONTHLY_REPRICE'
         AND source_no = #{repriceNo}
         AND status IN ('PENDING', 'RUNNING', 'RETRYABLE')
      """)
  int cancelMonthlyRepriceOpenTasks(
      @Param("repriceNo") String repriceNo,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_cost_run_task
         SET status = 'PENDING',
             progress = 0,
             worker_id = NULL,
             locked_at = NULL,
             lock_expire_time = NULL,
             retry_count = 0,
             error_message = NULL,
             error_stack = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE scene = 'MONTHLY_REPRICE'
         AND source_no = #{repriceNo}
         AND status = 'FAILED'
      """)
  int retryMonthlyRepriceFailedTasks(
      @Param("repriceNo") String repriceNo,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Select("""
      SELECT COUNT(*)
        FROM lp_cost_run_task t
       WHERE t.scene = 'MONTHLY_REPRICE'
         AND t.source_no = #{repriceNo}
         AND t.status = 'SUCCESS'
         AND NOT EXISTS (
           SELECT 1
             FROM lp_monthly_reprice_result r
            WHERE r.reprice_no = t.source_no
              AND r.calc_object_key = t.calc_object_key
         )
      """)
  long countMonthlyRepriceSuccessfulTasksMissingResult(@Param("repriceNo") String repriceNo);
}
