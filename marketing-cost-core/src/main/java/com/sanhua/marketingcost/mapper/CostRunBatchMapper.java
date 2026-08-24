package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CostRunBatch;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CostRunBatchMapper extends BaseMapper<CostRunBatch> {

  @Select("""
      SELECT *
        FROM lp_cost_run_batch
       WHERE scene = 'QUOTE'
         AND status IN ('PENDING', 'RUNNING', 'PARTIAL_FAILED')
         AND (
           prerequisite_status = 'PENDING'
           OR (
             prerequisite_status = 'RUNNING'
             AND updated_at <= #{staleBefore}
           )
         )
       ORDER BY id ASC
       LIMIT #{limit}
      """)
  List<CostRunBatch> selectQuotePrerequisiteCandidates(
      @Param("staleBefore") LocalDateTime staleBefore,
      @Param("limit") int limit);

  @Insert("""
      INSERT IGNORE INTO lp_cost_run_batch (
        batch_no,
        scene,
        source_no,
        pricing_month,
        price_as_of_time,
        business_unit_type,
        execution_no,
        prerequisite_status,
        control_version,
        status,
        total_count,
        success_count,
        failed_count,
        skipped_count,
        progress,
        request_snapshot_json,
        created_by,
        created_name,
        created_at,
        updated_at
      ) VALUES (
        #{batch.batchNo},
        #{batch.scene},
        #{batch.sourceNo},
        #{batch.pricingMonth},
        #{batch.priceAsOfTime},
        #{batch.businessUnitType},
        #{batch.executionNo},
        #{batch.prerequisiteStatus},
        #{batch.controlVersion},
        #{batch.status},
        #{batch.totalCount},
        #{batch.successCount},
        #{batch.failedCount},
        #{batch.skippedCount},
        #{batch.progress},
        #{batch.requestSnapshotJson},
        #{batch.createdBy},
        #{batch.createdName},
        #{batch.createdAt},
        #{batch.updatedAt}
      )
      """)
  int insertIgnore(@Param("batch") CostRunBatch batch);

  @Update("""
      UPDATE lp_cost_run_batch
         SET status = #{status},
             total_count = #{totalCount},
             success_count = #{successCount},
             failed_count = #{failedCount},
             skipped_count = #{skippedCount},
             progress = #{progress},
             started_at = CASE
               WHEN started_at IS NULL AND #{startedAt} IS NOT NULL THEN #{startedAt}
               ELSE started_at
             END,
             finished_at = #{finishedAt},
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
      """)
  int updateProgress(
      @Param("batchNo") String batchNo,
      @Param("status") String status,
      @Param("totalCount") int totalCount,
      @Param("successCount") int successCount,
      @Param("failedCount") int failedCount,
      @Param("skippedCount") int skippedCount,
      @Param("progress") int progress,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("finishedAt") LocalDateTime finishedAt,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_cost_run_batch
         SET status = 'PENDING',
             success_count = 0,
             failed_count = 0,
             skipped_count = 0,
             progress = 0,
             started_at = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
         AND status IN ('FAILED', 'PARTIAL_FAILED', 'CANCELED')
      """)
  int resetFailedBatchForRetry(
      @Param("batchNo") String batchNo,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_cost_run_batch
         SET status = 'PENDING',
             execution_no = execution_no + 1,
             prerequisite_status = #{prerequisiteStatus},
             control_version = control_version + 1,
             total_count = #{totalCount},
             success_count = 0,
             failed_count = 0,
             skipped_count = #{skippedCount},
             progress = 0,
             error_message = NULL,
             error_stack = NULL,
             started_at = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
         AND scene = 'QUOTE'
         AND execution_no = #{expectedExecutionNo}
         AND control_version = #{expectedControlVersion}
         AND prerequisite_status <> 'RUNNING'
         AND NOT EXISTS (
           SELECT 1
             FROM lp_cost_run_task t
            WHERE t.batch_no = lp_cost_run_batch.batch_no
              AND t.execution_no = lp_cost_run_batch.execution_no
              AND t.status = 'RUNNING'
         )
      """)
  int resetQuoteBatchForRerun(
      @Param("batchNo") String batchNo,
      @Param("expectedExecutionNo") int expectedExecutionNo,
      @Param("expectedControlVersion") int expectedControlVersion,
      @Param("totalCount") int totalCount,
      @Param("skippedCount") int skippedCount,
      @Param("prerequisiteStatus") String prerequisiteStatus,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_cost_run_batch b
         SET b.total_count = (
               SELECT COUNT(*) FROM lp_cost_run_task t
                WHERE t.batch_no = b.batch_no
                  AND t.execution_no = b.execution_no
             ),
             b.skipped_count = (
               SELECT COUNT(*) FROM lp_cost_run_task t
                WHERE t.batch_no = b.batch_no
                  AND t.execution_no = b.execution_no
                  AND t.status = 'SKIPPED_CURRENT'
             ),
             b.updated_at = #{updatedAt}
       WHERE b.batch_no = #{batchNo}
         AND b.execution_no = #{executionNo}
      """)
  int syncActiveQuoteBatchCounts(
      @Param("batchNo") String batchNo,
      @Param("executionNo") int executionNo,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Select("""
      SELECT *
        FROM lp_cost_run_batch
       WHERE scene = 'QUOTE'
         AND source_no = #{oaNo}
         AND pricing_month = #{pricingMonth}
         AND business_unit_type = #{businessUnitType}
       LIMIT 1
      """)
  CostRunBatch selectCurrentQuoteBatch(
      @Param("oaNo") String oaNo,
      @Param("pricingMonth") String pricingMonth,
      @Param("businessUnitType") String businessUnitType);

  @Update("""
      UPDATE lp_cost_run_batch
         SET prerequisite_status = #{nextStatus},
             control_version = control_version + 1,
             status = CASE
               WHEN #{nextStatus} = 'FAILED' THEN 'FAILED'
               ELSE status
             END,
             failed_count = CASE
               WHEN #{nextStatus} = 'FAILED' THEN GREATEST(total_count - skipped_count, 0)
               ELSE failed_count
             END,
             progress = CASE
               WHEN #{nextStatus} = 'FAILED' THEN 100
               ELSE progress
             END,
             error_message = CASE
               WHEN #{nextStatus} = 'FAILED' THEN #{errorMessage}
               ELSE NULL
             END,
             finished_at = CASE
               WHEN #{nextStatus} = 'FAILED' THEN #{updatedAt}
               ELSE finished_at
             END,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
         AND execution_no = #{executionNo}
         AND control_version = #{expectedControlVersion}
         AND prerequisite_status = #{expectedStatus}
      """)
  int transitionPrerequisite(
      @Param("batchNo") String batchNo,
      @Param("executionNo") int executionNo,
      @Param("expectedControlVersion") int expectedControlVersion,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("errorMessage") String errorMessage,
      @Param("updatedAt") LocalDateTime updatedAt);
}
