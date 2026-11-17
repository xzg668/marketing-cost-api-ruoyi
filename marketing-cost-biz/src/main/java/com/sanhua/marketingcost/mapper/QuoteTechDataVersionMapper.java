package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechDataVersionMapper extends BaseMapper<QuoteTechDataVersion> {

  @Select("""
      SELECT approved.* FROM lp_quote_tech_data_version draft
      JOIN lp_quote_tech_product p ON p.id=draft.product_id AND p.active_flag=1
      JOIN lp_quote_tech_module m ON m.product_id=p.id AND m.module_type='MANUFACTURING'
      JOIN lp_quote_tech_data_version approved ON approved.id=m.current_version_id
        AND approved.product_id=p.id
       WHERE draft.id=#{draftId} AND draft.version_status='DRAFT'
         AND p.oa_form_item_id=#{sourceItemId} AND p.accounting_month=#{sourceMonth}
         AND m.module_status IN ('SUBMITTED','APPROVED')
         AND approved.version_status IN ('SUBMITTED','APPROVED')
      """)
  QuoteTechDataVersion selectSubmittedManufacturingForDraft(@Param("draftId") Long draftId,
      @Param("sourceItemId") Long sourceItemId, @Param("sourceMonth") String sourceMonth);

  @Select("""
      SELECT id FROM lp_quote_tech_data_version
       WHERE product_id=#{productId}
         AND version_status IN ('FROZEN','SUBMITTED','APPROVED','RETURNED')
         AND content_fingerprint IS NOT NULL
         AND JSON_CONTAINS(reference_snapshot_json, JSON_OBJECT('moduleType',#{moduleType}), '$.modules')
       ORDER BY version_no DESC LIMIT 1
      """)
  Long selectLatestSubmittedModuleVersion(@Param("productId") Long productId, @Param("moduleType") String moduleType);

  @Select("SELECT * FROM lp_quote_tech_data_version WHERE id=#{id} FOR UPDATE")
  QuoteTechDataVersion selectByIdForUpdate(@Param("id") Long id);

  @Select("""
      SELECT COALESCE(MAX(version_no),0)
        FROM lp_quote_tech_data_version
       WHERE product_id=#{productId}
      """)
  int selectMaxVersionNo(@Param("productId") Long productId);

  @Update("""
      UPDATE lp_quote_tech_data_version
         SET product_model = #{version.productModel},
             product_property = #{version.productProperty},
             new_product_flag = #{version.newProductFlag},
             content_schema_version = #{version.contentSchemaVersion},
             product_fees_json = #{version.productFeesJson},
             plate_material_json = #{version.plateMaterialJson},
             drawing_bom_json = #{version.drawingBomJson},
             technical_bom_json = #{version.technicalBomJson},
             manufacturing_json = #{version.manufacturingJson},
             packaging_json = #{version.packagingJson},
             solder_items_json = #{version.solderItemsJson},
             net_loss_json = #{version.netLossJson},
             price_items_json = #{version.priceItemsJson},
             source_facts_json = #{version.sourceFactsJson},
             package_total_amount = #{version.packageTotalAmount},
             auxiliary_total_amount = #{version.auxiliaryTotalAmount},
             salary_total_amount = #{version.salaryTotalAmount},
             reference_snapshot_json = #{version.referenceSnapshotJson},
             updated_by = #{version.updatedBy},
             row_version = row_version + 1,
             updated_at = #{updatedAt}
       WHERE id = #{version.id}
         AND version_status = 'DRAFT'
         AND row_version = #{expectedVersion}
      """)
  int updateDraftWithVersion(
      @Param("version") QuoteTechDataVersion version,
      @Param("expectedVersion") int expectedVersion,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_tech_data_version
         SET version_status = #{targetStatus},
             content_fingerprint = #{contentFingerprint},
             reference_snapshot_json = CASE
               WHEN #{targetStatus} IN ('FROZEN','SUBMITTED')
                 THEN COALESCE(#{referenceSnapshotJson}, reference_snapshot_json)
               ELSE reference_snapshot_json
             END,
             submitted_by = CASE WHEN #{expectedStatus} = 'DRAFT' AND #{targetStatus} IN ('FROZEN','SUBMITTED') THEN #{actorId} ELSE submitted_by END,
             submitted_at = CASE WHEN #{expectedStatus} = 'DRAFT' AND #{targetStatus} IN ('FROZEN','SUBMITTED') THEN #{changedAt} ELSE submitted_at END,
             approved_by = CASE WHEN #{targetStatus} = 'APPROVED' THEN #{actorId} ELSE approved_by END,
             approved_at = CASE WHEN #{targetStatus} = 'APPROVED' THEN #{changedAt} ELSE approved_at END,
             updated_by = #{actorId},
             row_version = row_version + 1,
             updated_at = #{changedAt}
       WHERE id = #{id}
         AND version_status = #{expectedStatus}
         AND row_version = #{expectedVersion}
         AND ((#{expectedStatus} = 'DRAFT'
               AND #{targetStatus} IN ('FROZEN', 'SUBMITTED', 'VOIDED'))
           OR (#{expectedStatus} = 'FROZEN'
               AND #{targetStatus} IN ('SUBMITTED', 'VOIDED'))
           OR (#{expectedStatus} = 'SUBMITTED'
               AND #{targetStatus} IN ('APPROVED', 'RETURNED', 'VOIDED')))
      """)
  int transitionStatus(
      @Param("id") Long id,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus,
      @Param("expectedVersion") int expectedVersion,
      @Param("contentFingerprint") String contentFingerprint,
      @Param("referenceSnapshotJson") String referenceSnapshotJson,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);
}
