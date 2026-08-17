package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationReviewItemMapper
    extends BaseMapper<QuoteCollaborationReviewItem> {

  @Select("""
      SELECT i.* FROM lp_quote_collaboration_review_item i
      JOIN lp_quote_collaboration_product_task p ON p.id = i.product_task_id
      WHERE i.review_id = #{reviewId} AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY i.id
      """)
  List<QuoteCollaborationReviewItem> selectByReview(
      @Param("reviewId") Long reviewId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT i.* FROM lp_quote_collaboration_review_item i
      JOIN lp_quote_collaboration_review r ON r.id=i.review_id
      JOIN lp_quote_collaboration_task t ON t.id=r.collaboration_id
      WHERE i.review_id=#{reviewId} AND r.reviewer_user_id=#{reviewerUserId}
        AND t.business_unit_type=#{businessUnitType}
      ORDER BY i.product_task_id,i.id
      """)
  List<QuoteCollaborationReviewItem> selectFinanceItems(
      @Param("reviewId") Long reviewId, @Param("reviewerUserId") Long reviewerUserId,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT i.* FROM lp_quote_collaboration_review_item i
      JOIN lp_quote_collaboration_review r ON r.id=i.review_id
      JOIN lp_quote_collaboration_task t ON t.id=r.collaboration_id
      WHERE i.id=#{itemId} AND i.review_id=#{reviewId} AND r.reviewer_user_id=#{reviewerUserId}
        AND t.business_unit_type=#{businessUnitType}
      """)
  QuoteCollaborationReviewItem selectFinanceItem(
      @Param("reviewId") Long reviewId, @Param("itemId") Long itemId,
      @Param("reviewerUserId") Long reviewerUserId,
      @Param("businessUnitType") String businessUnitType);

  /** 技术退回页只展示最近一轮被财务明确退回的字段级问题。 */
  @Select("""
      SELECT i.* FROM lp_quote_collaboration_review_item i
      JOIN lp_quote_collaboration_review r ON r.id=i.review_id
      JOIN lp_quote_collaboration_product_task p ON p.id=i.product_task_id
      WHERE i.product_task_id=#{productTaskId} AND i.decision='REJECTED'
        AND p.business_unit_type=#{businessUnitType}
        AND r.id=(
          SELECT MAX(i2.review_id) FROM lp_quote_collaboration_review_item i2
          WHERE i2.product_task_id=#{productTaskId} AND i2.decision='REJECTED'
        )
      ORDER BY i.id
      """)
  List<QuoteCollaborationReviewItem> selectLatestRejectedByProductTask(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType);

  @Update("""
      UPDATE lp_quote_collaboration_review_item i
      JOIN lp_quote_collaboration_review r ON r.id=i.review_id
      SET i.decision=#{decision}, i.decision_reason=#{reason}, i.decided_by=#{reviewerUserId},
          i.decided_by_name=#{reviewerName}, i.decided_at=NOW(), i.updated_at=NOW()
      WHERE i.id=#{itemId} AND i.review_id=#{reviewId} AND i.decision='PENDING'
        AND r.reviewer_user_id=#{reviewerUserId} AND r.review_status IN ('PENDING','PARTIAL')
      """)
  int decide(
      @Param("reviewId") Long reviewId, @Param("itemId") Long itemId,
      @Param("decision") String decision, @Param("reason") String reason,
      @Param("reviewerUserId") Long reviewerUserId, @Param("reviewerName") String reviewerName);
}
