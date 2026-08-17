package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationGapMapper extends BaseMapper<QuoteCollaborationGap> {

  @Select("""
      SELECT g.* FROM lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      WHERE g.product_task_id = #{productTaskId}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      ORDER BY g.id
      """)
  List<QuoteCollaborationGap> selectByProductTask(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT g.* FROM lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      WHERE g.product_task_id = #{productTaskId} AND g.gap_fingerprint = #{fingerprint}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      FOR UPDATE
      """)
  QuoteCollaborationGap selectForUpdateByFingerprint(
      @Param("productTaskId") Long productTaskId,
      @Param("fingerprint") String fingerprint,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT g.* FROM lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      WHERE g.id = #{id} AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      FOR UPDATE
      """)
  QuoteCollaborationGap selectScopedForUpdateById(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Update("""
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      SET g.current_price_draft_id = #{draftId}, g.gap_status = 'OPEN',
          g.updated_by = #{updatedBy}, g.updated_by_name = #{updatedByName}, g.updated_at = NOW()
      WHERE g.id = #{id} AND g.current_price_draft_id IS NULL
        AND g.gap_status IN ('OPEN', 'DRAFT_READY')
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  int bindCurrentPriceDraft(
      @Param("id") Long id,
      @Param("draftId") Long draftId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      SET g.gap_status = #{gapStatus}, g.updated_by = #{updatedBy},
          g.updated_by_name = #{updatedByName}, g.updated_at = NOW()
      WHERE g.id = #{id} AND g.current_price_draft_id = #{draftId}
        AND g.gap_status IN ('OPEN', 'DRAFT_READY')
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  int updatePriceDraftValidationStatus(
      @Param("id") Long id,
      @Param("draftId") Long draftId,
      @Param("gapStatus") String gapStatus,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      SET g.gap_category = #{gap.gapCategory}, g.gap_type = #{gap.gapType},
          g.source_type = #{gap.sourceType}, g.source_id = #{gap.sourceId},
          g.bom_node_key = #{gap.bomNodeKey}, g.bom_path = #{gap.bomPath},
          g.bom_quantity = #{gap.bomQuantity}, g.bom_unit = #{gap.bomUnit},
          g.accounting_month = #{gap.accountingMonth},
          g.applicable_org_code = #{gap.applicableOrgCode},
          g.material_code = #{gap.materialCode}, g.material_name = #{gap.materialName},
          g.material_spec = #{gap.materialSpec}, g.material_model = #{gap.materialModel},
          g.material_role = #{gap.materialRole},
          g.suggested_price_type = #{gap.suggestedPriceType},
          g.reason_code = #{gap.reasonCode}, g.reason_message = #{gap.reasonMessage},
          g.gap_status = 'OPEN', g.resolved_at = NULL, g.resolved_by = NULL,
          g.updated_by = #{gap.updatedBy}, g.updated_by_name = #{gap.updatedByName},
          g.updated_at = NOW()
      WHERE g.id = #{gap.id} AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
      """)
  int updateFromScan(
      @Param("gap") QuoteCollaborationGap gap,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Update("""
      <script>
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id = g.product_task_id
      SET g.gap_status = 'OBSOLETE', g.updated_at = #{updatedAt}
      WHERE g.product_task_id = #{productTaskId}
        AND p.business_unit_type = #{businessUnitType}
        AND p.applicable_org_code = #{applicableOrgCode}
        AND g.gap_status NOT IN ('OBSOLETE', 'RESOLVED', 'WAIVED')
        <if test="fingerprints != null and !fingerprints.isEmpty()">
          AND g.gap_fingerprint NOT IN
          <foreach collection="fingerprints" item="fingerprint" open="(" separator="," close=")">
            #{fingerprint}
          </foreach>
        </if>
      </script>
      """)
  int markMissingAsObsolete(
      @Param("productTaskId") Long productTaskId,
      @Param("fingerprints") List<String> fingerprints,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_collaboration_product_task p ON p.id=g.product_task_id
      SET g.gap_status='RESOLVED', g.resolved_at=NOW(), g.resolved_by=#{resolvedBy},
          g.updated_by=#{resolvedBy}, g.updated_by_name=#{resolvedByName}, g.updated_at=NOW()
      WHERE g.product_task_id=#{productTaskId} AND g.gap_category='PRICE'
        AND g.current_price_draft_id IS NOT NULL AND g.gap_status='DRAFT_READY'
        AND p.business_unit_type=#{businessUnitType} AND p.applicable_org_code=#{orgCode}
      """)
  int resolvePublishedPriceGaps(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType, @Param("orgCode") String orgCode,
      @Param("resolvedBy") Long resolvedBy, @Param("resolvedByName") String resolvedByName);

  @Update("""
      UPDATE lp_quote_collaboration_gap g
      JOIN lp_quote_price_draft d ON d.id=g.current_price_draft_id
      JOIN lp_quote_collaboration_product_task p ON p.id=g.product_task_id
      SET g.current_price_draft_id=NULL, g.gap_status='OPEN', g.resolved_at=NULL,
          g.resolved_by=NULL, g.updated_by=#{updatedBy}, g.updated_by_name=#{updatedByName},
          g.updated_at=NOW()
      WHERE g.product_task_id=#{productTaskId} AND g.gap_category='PRICE'
        AND g.gap_status='OPEN' AND d.draft_status='PUBLISHED'
        AND p.business_unit_type=#{businessUnitType} AND p.applicable_org_code=#{orgCode}
      """)
  int clearPublishedDraftFromReopenedGaps(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType, @Param("orgCode") String orgCode,
      @Param("updatedBy") Long updatedBy, @Param("updatedByName") String updatedByName);
}
