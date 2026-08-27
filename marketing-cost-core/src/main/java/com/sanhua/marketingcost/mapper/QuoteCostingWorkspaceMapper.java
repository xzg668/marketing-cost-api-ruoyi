package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCostingWorkspaceMapper extends BaseMapper<QuoteCostingWorkspace> {

  @Insert("""
      INSERT IGNORE INTO lp_quote_costing_workspace (
        oa_no, oa_form_item_id, product_code, period_month, business_unit_type,
        workspace_status, current_step, source_revision, gap_count, carried_forward_price_count,
        data_quality_status, data_quality_warning_count,
        lock_version, created_at, updated_at
      ) VALUES (
        #{workspace.oaNo}, #{workspace.oaFormItemId}, #{workspace.productCode},
        #{workspace.periodMonth}, #{workspace.businessUnitType},
        #{workspace.workspaceStatus}, #{workspace.currentStep}, #{workspace.sourceRevision},
        #{workspace.gapCount}, #{workspace.carriedForwardPriceCount},
        COALESCE(#{workspace.dataQualityStatus}, 'UNKNOWN'),
        COALESCE(#{workspace.dataQualityWarningCount}, 0), #{workspace.lockVersion},
        #{workspace.createdAt}, #{workspace.updatedAt}
      )
      """)
  int insertIgnore(@Param("workspace") QuoteCostingWorkspace workspace);

  @Select("""
      SELECT *
        FROM lp_quote_costing_workspace
       WHERE oa_form_item_id = #{oaFormItemId}
         AND period_month = #{periodMonth}
       LIMIT 1
      """)
  QuoteCostingWorkspace selectByItemAndMonth(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("periodMonth") String periodMonth);

  @Select("""
      SELECT *
        FROM lp_quote_costing_workspace
       WHERE oa_form_item_id = #{oaFormItemId}
         AND period_month = #{periodMonth}
       LIMIT 1
       FOR UPDATE
      """)
  QuoteCostingWorkspace selectByItemAndMonthForUpdate(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("periodMonth") String periodMonth);

  @Select("""
      <script>
      SELECT *
        FROM lp_quote_costing_workspace
       WHERE period_month = #{periodMonth}
         AND oa_form_item_id IN
         <foreach collection="oaFormItemIds" item="itemId" open="(" separator="," close=")">
           #{itemId}
         </foreach>
      </script>
      """)
  List<QuoteCostingWorkspace> selectByItemsAndMonth(
      @Param("oaFormItemIds") Collection<Long> oaFormItemIds,
      @Param("periodMonth") String periodMonth);

  @Update("""
      UPDATE lp_quote_costing_workspace
         SET workspace_status = #{workspace.workspaceStatus},
             current_step = #{workspace.currentStep},
             input_fingerprint = #{workspace.inputFingerprint},
             source_revision = #{workspace.sourceRevision},
             last_success_input_fingerprint = #{workspace.lastSuccessInputFingerprint},
             last_success_source_revision = #{workspace.lastSuccessSourceRevision},
             bom_source_fingerprint = #{workspace.bomSourceFingerprint},
             bom_rule_fingerprint = #{workspace.bomRuleFingerprint},
             current_bom_build_batch_id = #{workspace.currentBomBuildBatchId},
             current_prepare_no = #{workspace.currentPrepareNo},
             current_cost_version_id = #{workspace.currentCostVersionId},
             gap_count = #{workspace.gapCount},
             carried_forward_price_count = #{workspace.carriedForwardPriceCount},
             data_quality_status = #{workspace.dataQualityStatus},
             data_quality_warning_count = #{workspace.dataQualityWarningCount},
             data_quality_summary = #{workspace.dataQualitySummary},
             stale_reason_code = #{workspace.staleReasonCode},
             last_error_step = #{workspace.lastErrorStep},
             last_error_code = #{workspace.lastErrorCode},
             last_error_message = #{workspace.lastErrorMessage},
             last_task_id = #{workspace.lastTaskId},
             last_checked_at = #{workspace.lastCheckedAt},
             lock_version = lock_version + 1,
             updated_at = #{updatedAt}
       WHERE id = #{workspace.id}
         AND lock_version = #{expectedLockVersion}
      """)
  int updateWithVersion(
      @Param("workspace") QuoteCostingWorkspace workspace,
      @Param("expectedLockVersion") int expectedLockVersion,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_costing_workspace
         SET workspace_status = 'STALE',
             current_step = 'QUOTE_BOM',
             stale_reason_code = #{reasonCode},
             last_error_step = NULL,
             last_error_code = NULL,
             last_error_message = NULL,
             lock_version = lock_version + 1,
             updated_at = #{updatedAt}
       WHERE oa_form_item_id = #{oaFormItemId}
         AND period_month = #{periodMonth}
      """)
  int markItemStale(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("periodMonth") String periodMonth,
      @Param("reasonCode") String reasonCode,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      <script>
      UPDATE lp_quote_costing_workspace
         SET workspace_status = 'STALE',
             current_step = 'QUOTE_BOM',
             stale_reason_code = #{reasonCode},
             last_error_step = NULL,
             last_error_code = NULL,
             last_error_message = NULL,
             lock_version = lock_version + 1,
             updated_at = #{updatedAt}
       WHERE current_bom_build_batch_id IS NOT NULL
         <if test="businessUnitType != null and businessUnitType != ''">
           AND business_unit_type = #{businessUnitType}
         </if>
      </script>
      """)
  int markBomRuleWorkspacesStale(
      @Param("businessUnitType") String businessUnitType,
      @Param("reasonCode") String reasonCode,
      @Param("updatedAt") LocalDateTime updatedAt);
}
