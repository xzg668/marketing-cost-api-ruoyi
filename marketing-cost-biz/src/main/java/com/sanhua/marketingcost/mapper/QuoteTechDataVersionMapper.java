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
               WHEN #{targetStatus} = 'SUBMITTED'
                 THEN COALESCE(#{referenceSnapshotJson}, reference_snapshot_json)
               ELSE reference_snapshot_json
             END,
             submitted_by = CASE WHEN #{targetStatus} = 'SUBMITTED' THEN #{actorId} ELSE submitted_by END,
             submitted_at = CASE WHEN #{targetStatus} = 'SUBMITTED' THEN #{changedAt} ELSE submitted_at END,
             approved_by = CASE WHEN #{targetStatus} = 'APPROVED' THEN #{actorId} ELSE approved_by END,
             approved_at = CASE WHEN #{targetStatus} = 'APPROVED' THEN #{changedAt} ELSE approved_at END,
             updated_by = #{actorId},
             row_version = row_version + 1,
             updated_at = #{changedAt}
       WHERE id = #{id}
         AND version_status = #{expectedStatus}
         AND row_version = #{expectedVersion}
         AND ((#{expectedStatus} = 'DRAFT'
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
