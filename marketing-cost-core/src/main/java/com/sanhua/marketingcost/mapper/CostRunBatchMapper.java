package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CostRunBatch;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CostRunBatchMapper extends BaseMapper<CostRunBatch> {

  @Insert("""
      INSERT IGNORE INTO lp_cost_run_batch (
        batch_no,
        scene,
        source_no,
        pricing_month,
        price_as_of_time,
        business_unit_type,
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
             success_count = 0,
             failed_count = 0,
             skipped_count = 0,
             progress = 0,
             started_at = NULL,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE batch_no = #{batchNo}
      """)
  int resetQuoteBatchForRerun(
      @Param("batchNo") String batchNo,
      @Param("updatedAt") LocalDateTime updatedAt);
}
