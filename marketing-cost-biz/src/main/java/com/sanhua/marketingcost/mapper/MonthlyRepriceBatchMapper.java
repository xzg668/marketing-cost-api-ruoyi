package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MonthlyRepriceBatchMapper extends BaseMapper<MonthlyRepriceBatch> {

  @Select("""
      SELECT *
        FROM lp_monthly_reprice_batch
       WHERE reprice_no = #{repriceNo}
       LIMIT 1
       FOR UPDATE
      """)
  MonthlyRepriceBatch selectByRepriceNoForUpdate(@Param("repriceNo") String repriceNo);

  @Update("""
      UPDATE lp_monthly_reprice_batch
         SET success_count = #{successCount},
             failed_count = #{failedCount},
             skipped_count = #{skippedCount},
             status = #{status},
             finished_at = #{finishedAt},
             updated_at = #{updatedAt}
       WHERE id = #{batchId}
         AND status NOT IN ('CONFIRMED', 'CANCELLED')
      """)
  int updateProgress(
      @Param("batchId") Long batchId,
      @Param("successCount") int successCount,
      @Param("failedCount") int failedCount,
      @Param("skippedCount") int skippedCount,
      @Param("status") String status,
      @Param("finishedAt") LocalDateTime finishedAt,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_monthly_reprice_batch
         SET status = 'CONFIRMED',
             confirmed_by = #{confirmedBy},
             confirmed_name = #{confirmedName},
             confirmed_at = #{confirmedAt},
             updated_at = #{confirmedAt}
       WHERE id = #{batchId}
         AND status = 'WAIT_CONFIRM'
      """)
  int confirmBatch(
      @Param("batchId") Long batchId,
      @Param("confirmedBy") String confirmedBy,
      @Param("confirmedName") String confirmedName,
      @Param("confirmedAt") LocalDateTime confirmedAt);

  @Update("""
      UPDATE lp_monthly_reprice_batch
         SET status = 'CANCELLED',
             finished_at = #{finishedAt},
             updated_at = #{finishedAt}
       WHERE reprice_no = #{repriceNo}
         AND status <> 'CONFIRMED'
      """)
  int cancelBatch(
      @Param("repriceNo") String repriceNo,
      @Param("finishedAt") LocalDateTime finishedAt);

  @Update("""
      UPDATE lp_monthly_reprice_batch
         SET status = 'RUNNING',
             failed_count = 0,
             finished_at = NULL,
             updated_at = #{updatedAt}
       WHERE reprice_no = #{repriceNo}
         AND status = 'FAILED'
      """)
  int retryFailedBatch(
      @Param("repriceNo") String repriceNo,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Select("""
      SELECT *
        FROM lp_monthly_reprice_batch
       WHERE pricing_month = #{pricingMonth}
         AND business_unit_type = #{businessUnitType}
         AND status = 'CONFIRMED'
       ORDER BY confirmed_at DESC, id DESC
       LIMIT 1
      """)
  MonthlyRepriceBatch selectLatestConfirmed(
      @Param("pricingMonth") String pricingMonth,
      @Param("businessUnitType") String businessUnitType);
}
