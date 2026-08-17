package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteCollaborationProductTaskMapper
    extends BaseMapper<QuoteCollaborationProductTask> {

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE id = #{id} AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
      """)
  QuoteCollaborationProductTask selectScopedById(
      @Param("id") Long id,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE product_task_no = #{productTaskNo} AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
      """)
  QuoteCollaborationProductTask selectScopedByNo(
      @Param("productTaskNo") String productTaskNo,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE active_lock_key = #{activeLockKey} AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode} AND active_flag = 1
      """)
  QuoteCollaborationProductTask selectActiveByLockKey(
      @Param("activeLockKey") String activeLockKey,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      <script>
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE current_assignee_user_id = #{assigneeUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
          #{status}
        </foreach>
      ORDER BY updated_at DESC, id DESC
      </script>
      """)
  List<QuoteCollaborationProductTask> selectByAssigneeAndStatuses(
      @Param("assigneeUserId") Long assigneeUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("statuses") List<String> statuses);

  /** 本人任务同时保留已提交后的只读记录；不能由客户端传组织或人员范围。 */
  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE original_technician_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
      ORDER BY CASE task_status
        WHEN 'RETURNED_TO_TECH' THEN 0
        WHEN 'TECH_VALIDATION_FAILED' THEN 1
        WHEN 'WAIT_TECH' THEN 2
        WHEN 'BOM_IN_PROGRESS' THEN 3
        WHEN 'PACKAGE_IN_PROGRESS' THEN 3
        WHEN 'PRICE_IN_PROGRESS' THEN 3
        ELSE 9 END,
        updated_at DESC, id DESC
      LIMIT 200
      """)
  List<QuoteCollaborationProductTask> selectMineByTechnician(
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE id = #{id}
        AND original_technician_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
      LIMIT 1
      """)
  QuoteCollaborationProductTask selectMineById(
      @Param("id") Long id,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType);

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE product_code = #{productCode} AND accounting_month = #{accountingMonth}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
      ORDER BY active_flag DESC, id DESC
      """)
  List<QuoteCollaborationProductTask> selectByProductAndMonth(
      @Param("productCode") String productCode,
      @Param("accountingMonth") String accountingMonth,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode);

  @Select("""
      SELECT * FROM lp_quote_collaboration_product_task
      WHERE origin_collaboration_id = #{collaborationId}
        AND business_unit_type = #{businessUnitType}
      ORDER BY id
      """)
  List<QuoteCollaborationProductTask> selectByCollaboration(
      @Param("collaborationId") Long collaborationId,
      @Param("businessUnitType") String businessUnitType);

  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET task_status = #{nextStatus}, task_version = task_version + 1,
          current_assignee_user_id = #{assigneeUserId},
          current_assignee_name = #{assigneeName},
          active_flag = CASE
            WHEN #{nextStatus} IN ('READY_FOR_COSTING', 'COMPLETED', 'CANCELLED') THEN 0
            ELSE active_flag
          END,
          active_lock_key = CASE
            WHEN #{nextStatus} IN ('READY_FOR_COSTING', 'COMPLETED', 'CANCELLED') THEN NULL
            ELSE active_lock_key
          END,
          tech_submitted_at = CASE
            WHEN #{nextStatus} = 'TECH_SUBMITTED' THEN NOW()
            ELSE tech_submitted_at
          END,
          ready_at = CASE
            WHEN #{nextStatus} = 'READY_FOR_COSTING' THEN COALESCE(ready_at, NOW())
            ELSE ready_at
          END,
          electronic_bom_fingerprint = CASE
            WHEN #{nextStatus} = 'RETURNED_TO_TECH' AND need_bom = 1 THEN NULL
            ELSE electronic_bom_fingerprint
          END,
          last_validation_status = CASE
            WHEN #{nextStatus} = 'RETURNED_TO_TECH' THEN 'NOT_CHECKED'
            WHEN #{nextStatus} IN ('BOM_IN_PROGRESS', 'PACKAGE_IN_PROGRESS', 'PRICE_IN_PROGRESS')
              AND #{expectedStatus} IN ('WAIT_TECH', 'TECH_VALIDATION_FAILED') THEN 'NOT_CHECKED'
            ELSE last_validation_status
          END,
          last_validation_at = CASE
            WHEN #{nextStatus} = 'RETURNED_TO_TECH' THEN NULL
            ELSE last_validation_at
          END,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND task_status = #{expectedStatus}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
      """)
  int transitionStatusWithVersion(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("expectedStatus") String expectedStatus,
      @Param("nextStatus") String nextStatus,
      @Param("assigneeUserId") Long assigneeUserId,
      @Param("assigneeName") String assigneeName,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET last_validation_status = #{validationStatus},
          last_validation_at = NOW(),
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
      """)
  int updateValidationResult(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("validationStatus") String validationStatus,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET open_gap_count=0, need_price=0, updated_by=#{updatedBy},
          updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE id=#{id} AND business_unit_type=#{businessUnitType}
        AND applicable_org_code=#{orgCode} AND task_status='APPROVED_PUBLISHING'
      """)
  int clearPublishedPriceGaps(
      @Param("id") Long id, @Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode, @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET need_price=1, open_gap_count=#{openGapCount}, last_validation_status='FAILED',
          last_validation_at=NOW(), task_version=task_version+1,
          updated_by=#{updatedBy}, updated_by_name=#{updatedByName}, updated_at=NOW()
      WHERE id=#{id} AND business_unit_type=#{businessUnitType}
        AND applicable_org_code=#{orgCode} AND task_status='APPROVED_PUBLISHING'
      """)
  int reopenBusinessPriceGaps(
      @Param("id") Long id, @Param("openGapCount") Integer openGapCount,
      @Param("businessUnitType") String businessUnitType, @Param("orgCode") String orgCode,
      @Param("updatedBy") Long updatedBy, @Param("updatedByName") String updatedByName);

  /** 只允许当前技术负责人把独立 BOM 草稿挂到自己的产品任务。 */
  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET preparation_id = COALESCE(#{preparationId}, preparation_id),
          supplement_version_id = #{supplementVersionId},
          electronic_bom_fingerprint = NULL,
          last_validation_status = 'NOT_CHECKED', last_validation_at = NULL,
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN ('BOM_IN_PROGRESS', 'TECH_VALIDATION_FAILED', 'RETURNED_TO_TECH')
      """)
  int attachBomDraft(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("preparationId") Long preparationId,
      @Param("supplementVersionId") Long supplementVersionId,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  /** 电子图库真实回取成功后保存结构指纹；此时价格检查尚未完成，不能提前标记总校验通过。 */
  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET electronic_bom_fingerprint = #{fingerprint},
          last_validation_status = 'NOT_CHECKED', last_validation_at = NOW(),
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN ('BOM_IN_PROGRESS', 'RETURNED_TO_TECH')
      """)
  int attachVerifiedElectronicBom(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("fingerprint") String fingerprint,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  /** 把同一次真实价格检查汇总写回任务；缺价时保持待校验，零缺价时才允许总校验通过。 */
  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET need_price = #{needPrice}, open_gap_count = #{openGapCount},
          last_validation_status = #{validationStatus}, last_validation_at = NOW(),
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN ('BOM_IN_PROGRESS', 'RETURNED_TO_TECH')
        AND electronic_bom_fingerprint IS NOT NULL
      """)
  int applyElectronicBomPriceScan(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("needPrice") Integer needPrice,
      @Param("openGapCount") Integer openGapCount,
      @Param("validationStatus") String validationStatus,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  /** 裸品包装草稿只挂独立包装参考，不复制或改写 U9 本体 BOM。 */
  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET preparation_id = COALESCE(#{preparationId}, preparation_id),
          package_reference_id = #{packageReferenceId},
          need_package = 1,
          last_validation_status = 'NOT_CHECKED', last_validation_at = NULL,
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN ('PACKAGE_IN_PROGRESS', 'TECH_VALIDATION_FAILED', 'RETURNED_TO_TECH')
      """)
  int attachPackageDraft(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("preparationId") Long preparationId,
      @Param("packageReferenceId") Long packageReferenceId,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);

  /** 包装保存后按正式价格体系只读检查；零缺价也保留包装待财务审核。 */
  @Update("""
      UPDATE lp_quote_collaboration_product_task
      SET need_price = #{needPrice}, open_gap_count = #{openGapCount},
          last_validation_status = #{validationStatus}, last_validation_at = NOW(),
          task_version = task_version + 1,
          updated_by = #{updatedBy}, updated_by_name = #{updatedByName}, updated_at = NOW()
      WHERE id = #{id} AND task_version = #{expectedVersion}
        AND current_assignee_user_id = #{technicianUserId}
        AND business_unit_type = #{businessUnitType}
        AND applicable_org_code = #{applicableOrgCode}
        AND task_status IN ('PACKAGE_IN_PROGRESS', 'RETURNED_TO_TECH')
        AND package_reference_id IS NOT NULL
      """)
  int applyPackagePriceScan(
      @Param("id") Long id,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("needPrice") Integer needPrice,
      @Param("openGapCount") Integer openGapCount,
      @Param("validationStatus") String validationStatus,
      @Param("technicianUserId") Long technicianUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedByName") String updatedByName);
}
