package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuotePriceDraftMapper extends BaseMapper<QuotePriceDraft> {

  @Select("""
      SELECT * FROM lp_quote_price_draft
      WHERE id = #{id} AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      """)
  QuotePriceDraft selectScopedById(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Select("""
      SELECT * FROM lp_quote_price_draft
      WHERE draft_no = #{draftNo} AND business_unit_type = #{businessUnitType}
        AND org_code = #{orgCode}
      """)
  QuotePriceDraft selectScopedByNo(
      @Param("draftNo") String draftNo,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Select("""
      SELECT * FROM lp_quote_price_draft
      WHERE product_task_id = #{productTaskId} AND business_unit_type = #{businessUnitType}
        AND org_code = #{orgCode}
      ORDER BY id
      """)
  List<QuotePriceDraft> selectByProductTask(
      @Param("productTaskId") Long productTaskId,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Select("""
      SELECT * FROM lp_quote_price_draft
      WHERE published_source_table = #{sourceTable} AND published_source_id = #{sourceId}
        AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      ORDER BY published_at DESC, id DESC
      """)
  List<QuotePriceDraft> selectByPublishedSource(
      @Param("sourceTable") String sourceTable,
      @Param("sourceId") Long sourceId,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Update("""
      UPDATE lp_quote_price_draft
      SET supplier_code = #{draft.supplierCode}, supplier_name = #{draft.supplierName},
          unit = #{draft.unit}, tax_included = #{draft.taxIncluded}, tax_rate = #{draft.taxRate},
          effective_from = #{draft.effectiveFrom}, effective_to = #{draft.effectiveTo},
          draft_status = 'EDITING',
          validation_status = 'NOT_CHECKED', validation_message = NULL,
          draft_fingerprint = #{draft.draftFingerprint}, draft_version = draft_version + 1,
          updated_by = #{draft.updatedBy}, updated_by_name = #{draft.updatedByName}, updated_at = NOW()
      WHERE id = #{draft.id} AND draft_version = #{expectedVersion}
        AND draft_status IN ('EDITING', 'VALIDATED', 'REJECTED')
        AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      """)
  int updateEditableContent(
      @Param("draft") QuotePriceDraft draft,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Update("""
      UPDATE lp_quote_price_draft
      SET price_type = #{draft.priceType}, source_mode = 'COPY',
          reference_source_type = #{draft.referenceSourceType},
          reference_source_id = #{draft.referenceSourceId},
          reference_version_text = #{draft.referenceVersionText},
          target_source_type = #{draft.targetSourceType},
          supplier_code = #{draft.supplierCode}, supplier_name = #{draft.supplierName},
          unit = #{draft.unit}, tax_included = #{draft.taxIncluded}, tax_rate = #{draft.taxRate},
          effective_from = #{draft.effectiveFrom}, effective_to = #{draft.effectiveTo},
          draft_status = 'EDITING',
          validation_status = 'NOT_CHECKED', validation_message = NULL,
          draft_fingerprint = NULL, draft_version = draft_version + 1,
          updated_by = #{draft.updatedBy}, updated_by_name = #{draft.updatedByName}, updated_at = NOW()
      WHERE id = #{draft.id} AND draft_version = #{expectedVersion}
        AND draft_status IN ('EDITING', 'VALIDATED', 'REJECTED')
        AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      """)
  int changeReference(
      @Param("draft") QuotePriceDraft draft,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);

  @Update("""
      UPDATE lp_quote_price_draft
      SET draft_status = CASE WHEN #{validationStatus} = 'PASSED' THEN 'VALIDATED' ELSE 'EDITING' END,
          validation_status = #{validationStatus}, validation_message = #{validationMessage},
          draft_version = draft_version + 1, updated_by = #{updatedBy},
          updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND draft_version = #{expectedVersion}
        AND draft_status IN ('EDITING', 'VALIDATED', 'REJECTED')
        AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      """)
  int updateValidationResult(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("validationStatus") String validationStatus,
      @Param("validationMessage") String validationMessage,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_price_draft
      SET draft_status = #{nextStatus}, draft_version = draft_version + 1,
          submitted_at = CASE
            WHEN #{nextStatus} = 'SUBMITTED' THEN COALESCE(submitted_at, NOW())
            ELSE submitted_at
          END,
          published_at = CASE
            WHEN #{nextStatus} = 'PUBLISHED' THEN COALESCE(published_at, NOW())
            ELSE published_at
          END,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND draft_version = #{expectedVersion}
        AND draft_status = #{expectedStatus}
        AND business_unit_type = #{businessUnitType} AND org_code = #{orgCode}
      """)
  int transitionStatusWithVersion(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_price_draft
      SET draft_status='PUBLISHED', published_source_table=#{sourceTable},
          published_source_id=#{sourceId}, publish_batch_no=#{batchNo}, published_at=NOW(),
          draft_version=draft_version+1, updated_by=#{updatedBy},
          updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE id=#{id} AND draft_version=#{expectedVersion} AND draft_status='APPROVED'
        AND validation_status='PASSED' AND business_unit_type=#{businessUnitType} AND org_code=#{orgCode}
      """)
  int markPublished(
      @Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
      @Param("sourceTable") String sourceTable, @Param("sourceId") Long sourceId,
      @Param("batchNo") String batchNo, @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode, @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
