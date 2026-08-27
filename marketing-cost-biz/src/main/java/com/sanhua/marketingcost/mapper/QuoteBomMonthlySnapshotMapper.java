package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteBomMonthlySnapshotMapper extends BaseMapper<QuoteBomMonthlySnapshot> {

  @Insert("""
      INSERT IGNORE INTO lp_quote_bom_monthly_snapshot (
        product_code, price_org_code, business_unit_type, material_organization_code,
        snapshot_identity_key, customer_code, package_method, cost_period_month,
        bom_source, bom_purpose, sync_type, sync_status, sync_by, source_oa_no,
        source_oa_form_item_id, active_flag, line_count, created_at, updated_at
      ) VALUES (
        #{snapshot.productCode}, #{snapshot.priceOrgCode}, #{snapshot.businessUnitType},
        #{snapshot.materialOrganizationCode}, #{snapshot.snapshotIdentityKey},
        #{snapshot.customerCode}, #{snapshot.packageMethod}, #{snapshot.costPeriodMonth},
        #{snapshot.bomSource}, #{snapshot.bomPurpose}, #{snapshot.syncType},
        #{snapshot.syncStatus}, #{snapshot.syncBy}, #{snapshot.sourceOaNo},
        #{snapshot.sourceOaFormItemId}, #{snapshot.activeFlag}, #{snapshot.lineCount},
        #{snapshot.createdAt}, #{snapshot.updatedAt}
      )
      """)
  @Options(useGeneratedKeys = true, keyProperty = "snapshot.id", keyColumn = "id")
  int insertU9MonthlyClaim(@Param("snapshot") QuoteBomMonthlySnapshot snapshot);

  @Select("""
      SELECT *
        FROM lp_quote_bom_monthly_snapshot
       WHERE snapshot_identity_key = #{identityKey}
       LIMIT 1
      """)
  QuoteBomMonthlySnapshot selectU9MonthlyByIdentity(
      @Param("identityKey") String identityKey);

  @Select("""
      SELECT *
        FROM lp_quote_bom_monthly_snapshot
       WHERE snapshot_identity_key = #{identityKey}
       LIMIT 1
       FOR UPDATE
      """)
  QuoteBomMonthlySnapshot selectU9MonthlyByIdentityForUpdate(
      @Param("identityKey") String identityKey);

  @Update("""
      UPDATE lp_quote_bom_monthly_snapshot
         SET bom_source = COALESCE(#{result.source}, bom_source),
             bom_version = #{result.bomVersion},
             bom_batch_id = #{result.syncBatchId},
             structure_fingerprint = #{result.structureFingerprint},
             line_count = #{result.lineCount},
             sync_status = #{syncStatus},
             sync_at = #{syncAt},
             error_message = #{result.message},
             updated_at = #{syncAt}
       WHERE id = #{id}
         AND sync_status = 'SYNCING'
      """)
  int completeU9MonthlyClaim(
      @Param("id") Long id,
      @Param("syncStatus") String syncStatus,
      @Param("syncAt") java.time.LocalDateTime syncAt,
      @Param("result")
          com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult result);

  @Delete("""
      DELETE FROM lp_quote_bom_monthly_snapshot
       WHERE id = #{id}
         AND sync_status = 'SYNCING'
      """)
  int deleteU9MonthlyClaim(@Param("id") Long id);
}
