package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface QuoteTechTaskMapper extends BaseMapper<QuoteTechTask> {

  @Insert("""
      INSERT INTO lp_quote_tech_task (
        task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,business_unit_type,applicable_org_code,
        assignee_user_id,assignee_name,task_status,
        task_version,review_round,review_status,source_system,source_request_id,
        external_system,external_task_id,external_task_status,external_last_sync_at,
        external_last_error,due_at,active_flag,active_lock_key,created_by,updated_by)
      VALUES (
        #{task.taskNo},#{task.oaFormId},#{task.oaFormItemId},#{task.oaNo},#{task.accountingMonth},
        #{task.businessUnitType},#{task.applicableOrgCode},#{task.assigneeUserId},
        #{task.assigneeName},#{task.taskStatus},
        #{task.taskVersion},#{task.reviewRound},#{task.reviewStatus},#{task.sourceSystem},
        #{task.sourceRequestId},#{task.externalSystem},#{task.externalTaskId},
        #{task.externalTaskStatus},#{task.externalLastSyncAt},#{task.externalLastError},
        #{task.dueAt},#{task.activeFlag},#{task.activeLockKey},#{task.createdBy},#{task.updatedBy})
      ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)
      """)
  @Options(useGeneratedKeys = true, keyProperty = "task.id")
  int insertOrGetActive(@Param("task") QuoteTechTask task);

  @Select("""
      SELECT *
        FROM lp_quote_tech_task
       WHERE oa_form_item_id=#{oaFormItemId}
         AND accounting_month=#{accountingMonth}
         AND active_flag=1
       LIMIT 1
       FOR UPDATE
      """)
  QuoteTechTask selectActiveForUpdate(
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("accountingMonth") String accountingMonth);

  @Select("SELECT * FROM lp_quote_tech_task WHERE id=#{taskId} FOR UPDATE")
  QuoteTechTask selectByIdForUpdate(@Param("taskId") Long taskId);

  @Update("""
      UPDATE lp_quote_tech_task
         SET assignee_user_id=#{assigneeUserId},assignee_name=#{assigneeName},
             task_status='PENDING',source_request_id=#{sourceRequestId},due_at=#{dueAt},
             task_version=task_version+1,updated_by=#{actorId},updated_at=NOW(3)
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status='UNASSIGNED' AND assignee_user_id IS NULL
      """)
  int assignUnassigned(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") Integer expectedVersion,
      @Param("assigneeUserId") Long assigneeUserId,
      @Param("assigneeName") String assigneeName,
      @Param("sourceRequestId") String sourceRequestId,
      @Param("dueAt") LocalDateTime dueAt,
      @Param("actorId") Long actorId);

  @Update("""
      UPDATE lp_quote_tech_task SET task_version=task_version+1,updated_by=COALESCE(#{actorId},updated_by),updated_at=NOW(3)
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
      """)
  int recordLocalAssignment(@Param("taskId") Long taskId, @Param("expectedVersion") Integer expectedVersion,
      @Param("actorId") Long actorId);

  @Select("""
      SELECT * FROM lp_quote_tech_task
       WHERE external_system=#{externalSystem} AND external_task_id=#{externalTaskId}
         AND active_flag=1
       LIMIT 1 FOR UPDATE
      """)
  QuoteTechTask selectByExternalIdentityForUpdate(
      @Param("externalSystem") String externalSystem,
      @Param("externalTaskId") String externalTaskId);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status='IN_PROGRESS',task_version=task_version+1,
             updated_by=#{actorId},updated_at=#{updatedAt}
       WHERE id=#{taskId} AND active_flag=1 AND task_status='PENDING'
      """)
  int markInProgress(
      @Param("taskId") Long taskId,
      @Param("actorId") Long actorId,
      @Param("updatedAt") LocalDateTime updatedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET external_system=#{externalSystem},external_task_id=#{externalTaskId},
             external_task_status=#{externalTaskStatus},external_last_sync_at=#{changedAt},
             external_last_error=NULL,external_next_retry_at=NULL,
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
      """)
  int markExternalPublished(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("externalSystem") String externalSystem,
      @Param("externalTaskId") String externalTaskId,
      @Param("externalTaskStatus") String externalTaskStatus,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET external_task_status='SYNC_FAILED',external_last_error=#{error},
             external_retry_count=external_retry_count+1,
             external_next_retry_at=#{nextRetryAt},external_last_sync_at=#{changedAt},
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
      """)
  int markExternalFailed(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("error") String error,
      @Param("nextRetryAt") LocalDateTime nextRetryAt,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET external_task_status=#{externalTaskStatus},external_callback_seq=#{callbackSeq},
             external_last_event_id=#{eventId},external_last_sync_at=#{changedAt},
             external_last_error=NULL,updated_at=#{changedAt}
       WHERE id=#{taskId} AND external_system=#{externalSystem}
         AND external_task_id=#{externalTaskId} AND external_callback_seq < #{callbackSeq}
      """)
  int acceptExternalCallback(
      @Param("taskId") Long taskId,
      @Param("externalSystem") String externalSystem,
      @Param("externalTaskId") String externalTaskId,
      @Param("externalTaskStatus") String externalTaskStatus,
      @Param("callbackSeq") long callbackSeq,
      @Param("eventId") String eventId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET proxy_operator_user_id=#{actorId},proxy_operator_name=#{actorName},
             proxy_reason=#{reason},proxy_request_id=#{requestId},proxy_started_at=#{changedAt},
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status IN ('PENDING','IN_PROGRESS','PARTIALLY_RETURNED')
         AND proxy_operator_user_id IS NULL
      """)
  int startProxyEntry(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("actorId") Long actorId,
      @Param("actorName") String actorName,
      @Param("reason") String reason,
      @Param("requestId") String requestId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET proxy_operator_user_id=NULL,proxy_operator_name=NULL,proxy_reason=NULL,
             proxy_request_id=NULL,proxy_started_at=NULL,
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND proxy_operator_user_id IS NOT NULL
      """)
  int unlockDraft(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status='CANCELLED',active_flag=0,active_lock_key=NULL,
             cancelled_at=#{changedAt},proxy_operator_user_id=NULL,proxy_operator_name=NULL,
             proxy_reason=NULL,proxy_request_id=NULL,proxy_started_at=NULL,
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status NOT IN ('APPROVED','CANCELLED')
      """)
  int voidTask(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);
}
