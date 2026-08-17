package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationReviewMapper extends BaseMapper<QuoteCollaborationReview> {

  @Select("""
      SELECT COALESCE(MAX(review_round), 0) FROM lp_quote_collaboration_review
      WHERE collaboration_id = #{collaborationId}
      """)
  Integer selectMaxRound(@Param("collaborationId") Long collaborationId);

  @Update("""
      UPDATE lp_quote_collaboration_review r
      SET passed_item_count=(SELECT COUNT(*) FROM lp_quote_collaboration_review_item i
                              WHERE i.review_id=r.id AND i.decision='PASSED'),
          rejected_item_count=(SELECT COUNT(*) FROM lp_quote_collaboration_review_item i
                                WHERE i.review_id=r.id AND i.decision='REJECTED'),
          updated_by=#{updatedBy}, updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE r.id=#{reviewId} AND r.reviewer_user_id=#{reviewerUserId}
      """)
  int refreshDecisionCounts(
      @Param("reviewId") Long reviewId, @Param("reviewerUserId") Long reviewerUserId,
      @Param("updatedBy") Long updatedBy, @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_review SET publish_batch_no=#{batchNo},
          updated_by=#{updatedBy}, updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE id=#{reviewId} AND publish_batch_no IS NULL AND review_status='PUBLISHING'
      """)
  int attachPublishBatch(
      @Param("reviewId") Long reviewId, @Param("batchNo") String batchNo,
      @Param("updatedBy") Long updatedBy, @Param("updatedByName") String updatedByName);

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_review r
      JOIN lp_quote_collaboration_task t ON t.id = r.collaboration_id
      WHERE r.id = #{id} AND t.business_unit_type = #{businessUnitType}
      """)
  QuoteCollaborationReview selectScopedById(
      @Param("id") Long id, @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_review r
      JOIN lp_quote_collaboration_task t ON t.id = r.collaboration_id
      WHERE r.review_no = #{reviewNo} AND t.business_unit_type = #{businessUnitType}
      """)
  QuoteCollaborationReview selectScopedByNo(
      @Param("reviewNo") String reviewNo,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      <script>
      SELECT r.* FROM lp_quote_collaboration_review r
      JOIN lp_quote_collaboration_task t ON t.id = r.collaboration_id
      WHERE r.reviewer_user_id = #{reviewerUserId}
        AND t.business_unit_type = #{businessUnitType}
        AND r.review_status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
          #{status}
        </foreach>
      ORDER BY r.updated_at DESC, r.id DESC
      </script>
      """)
  List<QuoteCollaborationReview> selectByReviewerAndStatuses(
      @Param("reviewerUserId") Long reviewerUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("statuses") List<String> statuses);

  @Update("""
      UPDATE lp_quote_collaboration_review r
      JOIN lp_quote_collaboration_task t ON t.id = r.collaboration_id
      SET r.review_status = #{nextStatus},
          r.reviewed_at = CASE
            WHEN #{nextStatus} IN ('APPROVED', 'REJECTED')
              THEN COALESCE(r.reviewed_at, NOW())
            ELSE r.reviewed_at
          END,
          r.effective_at = CASE
            WHEN #{nextStatus} = 'EFFECTIVE' THEN COALESCE(r.effective_at, NOW())
            ELSE r.effective_at
          END,
          r.updated_by = #{updatedBy}, r.updated_by_name = #{updatedByName},
          r.updated_at = NOW()
      WHERE r.id = #{id} AND r.review_status = #{expectedStatus}
        AND r.source_task_version = #{expectedSourceTaskVersion}
        AND t.business_unit_type = #{businessUnitType}
      """)
  int transitionStatus(
      @Param("id") Long id,
      @Param("expectedSourceTaskVersion") Integer expectedSourceTaskVersion,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("businessUnitType") String businessUnitType,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
