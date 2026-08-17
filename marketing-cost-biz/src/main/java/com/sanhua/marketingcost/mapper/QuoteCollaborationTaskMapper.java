package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationTaskMapper extends BaseMapper<QuoteCollaborationTask> {

  @Select("""
      SELECT * FROM lp_quote_collaboration_task
      WHERE id = #{id} AND business_unit_type = #{businessUnitType}
      """)
  QuoteCollaborationTask selectScopedById(
      @Param("id") Long id, @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT * FROM lp_quote_collaboration_task
      WHERE id = #{id} AND business_unit_type = #{businessUnitType}
      FOR UPDATE
      """)
  QuoteCollaborationTask selectScopedForUpdate(
      @Param("id") Long id, @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT * FROM lp_quote_collaboration_task
      WHERE collaboration_no = #{collaborationNo}
        AND business_unit_type = #{businessUnitType}
      """)
  QuoteCollaborationTask selectScopedByNo(
      @Param("collaborationNo") String collaborationNo,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT * FROM lp_quote_collaboration_task
      WHERE oa_form_id = #{oaFormId} AND business_unit_type = #{businessUnitType}
      ORDER BY round_no DESC
      LIMIT 1
      """)
  QuoteCollaborationTask selectLatestByForm(
      @Param("oaFormId") Long oaFormId,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      <script>
      SELECT * FROM lp_quote_collaboration_task
      WHERE finance_reviewer_user_id = #{reviewerUserId}
        AND business_unit_type = #{businessUnitType}
        AND master_status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
          #{status}
        </foreach>
      ORDER BY updated_at DESC, id DESC
      </script>
      """)
  List<QuoteCollaborationTask> selectByReviewerAndStatuses(
      @Param("reviewerUserId") Long reviewerUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("statuses") List<String> statuses);

  @Update("""
      UPDATE lp_quote_collaboration_task
      SET master_status = #{nextStatus}, task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND master_status = #{expectedStatus}
        AND business_unit_type = #{businessUnitType}
      """)
  int transitionStatusWithVersion(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_task
      SET owned_product_count = owned_product_count + 1,
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND business_unit_type = #{businessUnitType}
      """)
  int incrementOwnedProductCount(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_task
      SET current_review_id = #{reviewId}, tech_submitted_count = #{submittedCount},
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND business_unit_type = #{businessUnitType}
        AND master_status = 'WAIT_FINANCE' AND current_review_id IS NULL
      """)
  int attachCurrentReview(
      @Param("id") Long id,
      @Param("reviewId") Long reviewId,
      @Param("submittedCount") Integer submittedCount,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_task
      SET current_review_id=NULL, returned_product_count=#{returnedCount},
          updated_by=#{updatedBy}, updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE id=#{id} AND business_unit_type=#{businessUnitType}
        AND master_status='PARTIAL_RETURN' AND current_review_id=#{reviewId}
      """)
  int detachRejectedReview(
      @Param("id") Long id, @Param("reviewId") Long reviewId,
      @Param("returnedCount") Integer returnedCount,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy, @Param("updatedByName") String updatedByName);

  /** 刷新主任务的数据准备完成数；这是产品状态的派生投影，不额外推进任务版本。 */
  @Update("""
      UPDATE lp_quote_collaboration_task master
      SET ready_product_count = (
            SELECT COUNT(*) FROM lp_quote_collaboration_product_task product
            WHERE product.origin_collaboration_id = master.id
              AND product.business_unit_type = master.business_unit_type
              AND product.task_status IN ('READY_FOR_COSTING', 'COSTING', 'COMPLETED')
          ),
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE master.id = #{id} AND master.business_unit_type = #{businessUnitType}
      """)
  int refreshReadyProductCount(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
