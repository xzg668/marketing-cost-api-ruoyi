package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationApprovedResultMapper
    extends BaseMapper<QuoteCollaborationApprovedResult> {

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_approved_result r
      JOIN lp_quote_collaboration_product_task p ON p.id = r.source_product_task_id
      WHERE r.product_code = #{productCode} AND r.applicable_org_code = #{applicableOrgCode}
        AND r.result_type = #{resultType} AND r.result_status = 'ACTIVE'
        AND r.valid_from <= #{effectiveAt} AND r.valid_until > #{effectiveAt}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY r.valid_from DESC, r.id DESC
      """)
  List<QuoteCollaborationApprovedResult> selectValidResults(
      @Param("productCode") String productCode,
      @Param("resultType") String resultType,
      @Param("effectiveAt") LocalDateTime effectiveAt,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_approved_result r
      JOIN lp_quote_collaboration_product_task p ON p.id = r.source_product_task_id
      WHERE r.id = #{id} AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  QuoteCollaborationApprovedResult selectScopedById(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_approved_result r
      JOIN lp_quote_collaboration_product_task p ON p.id = r.source_product_task_id
      WHERE r.source_product_task_id = #{sourceProductTaskId}
        AND r.source_review_id = #{sourceReviewId} AND r.result_type = #{resultType}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY r.id DESC LIMIT 1
      """)
  QuoteCollaborationApprovedResult selectBySource(
      @Param("sourceProductTaskId") Long sourceProductTaskId,
      @Param("sourceReviewId") Long sourceReviewId,
      @Param("resultType") String resultType,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT r.* FROM lp_quote_collaboration_approved_result r
      JOIN lp_quote_collaboration_product_task p ON p.id = r.source_product_task_id
      WHERE r.product_code = #{productCode} AND r.applicable_org_code = #{applicableOrgCode}
        AND r.result_type = #{resultType} AND r.result_status IN ('ACTIVE', 'EXPIRED')
        AND r.valid_from <= #{effectiveAt} AND r.valid_until <= #{effectiveAt}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY r.valid_until DESC, r.id DESC LIMIT 1
      """)
  QuoteCollaborationApprovedResult selectLatestExpiredReference(
      @Param("productCode") String productCode,
      @Param("resultType") String resultType,
      @Param("effectiveAt") LocalDateTime effectiveAt,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Update("""
      UPDATE lp_quote_collaboration_approved_result r
      JOIN lp_quote_collaboration_product_task p ON p.id = r.source_product_task_id
      SET r.result_status = 'INVALIDATED', r.invalid_reason = #{reason},
          r.invalidated_at = #{invalidatedAt}, r.updated_by = #{updatedBy},
          r.updated_by_name = #{updatedByName}, r.updated_at = #{invalidatedAt}
      WHERE r.id = #{id} AND r.result_status = #{expectedStatus}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  int invalidate(
      @Param("id") Long id,
      @Param("expectedStatus") String expectedStatus,
      @Param("reason") String reason,
      @Param("invalidatedAt") LocalDateTime invalidatedAt,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
