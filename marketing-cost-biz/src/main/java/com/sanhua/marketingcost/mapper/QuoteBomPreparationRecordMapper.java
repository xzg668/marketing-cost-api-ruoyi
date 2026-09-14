package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteBomPreparationRecordMapper extends BaseMapper<QuoteBomPreparationRecord> {

  /** 串行化同一报价产品的电子图库版本判重与编号。 */
  @Select("""
      SELECT * FROM lp_quote_bom_preparation_record
      WHERE id = #{id}
      LIMIT 1
      FOR UPDATE
      """)
  QuoteBomPreparationRecord selectForElectronicDrawingImport(@Param("id") Long id);

  @Update("""
      UPDATE lp_quote_bom_preparation_record
         SET electronic_workflow_stage=#{stage},
             electronic_assignee_user_id=#{assigneeUserId},
             electronic_assignee_name=#{assigneeName},
             electronic_workflow_version=electronic_workflow_version+1,
             updated_at=#{updatedAt}
       WHERE id=#{preparationId}
         AND electronic_workflow_version=#{expectedVersion}
         AND active_flag=1
      """)
  int updateElectronicStage(
      @Param("preparationId") Long preparationId,
      @Param("expectedVersion") int expectedVersion,
      @Param("stage") String stage,
      @Param("assigneeUserId") Long assigneeUserId,
      @Param("assigneeName") String assigneeName,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_bom_preparation_record
         SET electronic_source_version_id=#{sourceVersionId},
             electronic_workflow_version=electronic_workflow_version+1,
             updated_at=#{updatedAt}
       WHERE id=#{preparationId}
         AND electronic_workflow_version=#{expectedVersion}
         AND active_flag=1
      """)
  int attachElectronicSourceVersion(
      @Param("preparationId") Long preparationId,
      @Param("expectedVersion") int expectedVersion,
      @Param("sourceVersionId") Long sourceVersionId,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_bom_preparation_record
         SET electronic_source_version_id=#{sourceVersionId},
             electronic_workflow_version=electronic_workflow_version+1,
             updated_at=#{updatedAt}
       WHERE id=#{preparationId}
         AND electronic_workflow_version=#{expectedVersion}
         AND active_flag=1
      """)
  int touchElectronicWorkflow(
      @Param("preparationId") Long preparationId,
      @Param("expectedVersion") int expectedVersion,
      @Param("sourceVersionId") Long sourceVersionId,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_bom_preparation_record
         SET preparation_status='READY',review_status='APPROVED',
             electronic_workflow_stage='PUBLISHED',
             electronic_composition_fingerprint=#{fingerprint},
             electronic_workflow_version=electronic_workflow_version+1,
             error_message=NULL,updated_at=#{updatedAt}
       WHERE id=#{preparationId}
         AND electronic_workflow_version=#{expectedVersion}
         AND active_flag=1
      """)
  int completeElectronicPublication(
      @Param("preparationId") Long preparationId,
      @Param("expectedVersion") int expectedVersion,
      @Param("fingerprint") String fingerprint,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
