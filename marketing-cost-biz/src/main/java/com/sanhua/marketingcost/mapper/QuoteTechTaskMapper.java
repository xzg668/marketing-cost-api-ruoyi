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
        task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,
        assignee_user_id,assignee_name,reviewer_user_id,reviewer_name,task_status,
        task_version,review_round,review_status,source_system,source_request_id,
        external_system,external_task_id,external_task_status,external_last_sync_at,
        external_last_error,due_at,active_flag,active_lock_key,created_by,updated_by)
      VALUES (
        #{task.taskNo},#{task.oaFormId},#{task.oaNo},#{task.accountingMonth},
        #{task.businessUnitType},#{task.applicableOrgCode},#{task.assigneeUserId},
        #{task.assigneeName},#{task.reviewerUserId},#{task.reviewerName},#{task.taskStatus},
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
       WHERE oa_no=#{oaNo}
         AND accounting_month=#{accountingMonth}
         AND assignee_user_id=#{assigneeUserId}
         AND active_flag=1
       LIMIT 1
       FOR UPDATE
      """)
  QuoteTechTask selectActiveForUpdate(
      @Param("oaNo") String oaNo,
      @Param("accountingMonth") String accountingMonth,
      @Param("assigneeUserId") Long assigneeUserId);

  @Select("SELECT * FROM lp_quote_tech_task WHERE id=#{taskId} FOR UPDATE")
  QuoteTechTask selectByIdForUpdate(@Param("taskId") Long taskId);

  @Select("""
      SELECT * FROM lp_quote_tech_task
       WHERE external_system=#{externalSystem} AND external_task_id=#{externalTaskId}
         AND active_flag=1
       LIMIT 1 FOR UPDATE
      """)
  QuoteTechTask selectByExternalIdentityForUpdate(
      @Param("externalSystem") String externalSystem,
      @Param("externalTaskId") String externalTaskId);

  @Select({
      "<script>",
      "SELECT COUNT(*) FROM lp_quote_tech_task task",
      "WHERE 1=1",
      "<if test='accessMode != \"ALL\"'>AND task.active_flag=1</if>",
      "<if test='taskStatus != null and taskStatus != \"\"'>",
      "AND task.task_status=#{taskStatus}",
      "</if>",
      "<if test='accountingMonth != null and accountingMonth != \"\"'>",
      "AND task.accounting_month=#{accountingMonth}",
      "</if>",
      "<choose>",
      "<when test='accessMode == \"ALL\"'></when>",
      "<when test='accessMode == \"ASSIGNEE_OR_REVIEWER\"'>",
      "AND (task.assignee_user_id=#{userId} OR task.reviewer_user_id=#{userId})",
      "</when>",
      "<when test='accessMode == \"REVIEWER\"'>",
      "AND task.reviewer_user_id=#{userId}",
      "</when>",
      "<otherwise>AND task.assignee_user_id=#{userId}</otherwise>",
      "</choose>",
      "</script>"
  })
  long countAccessible(
      @Param("accessMode") String accessMode,
      @Param("userId") Long userId,
      @Param("taskStatus") String taskStatus,
      @Param("accountingMonth") String accountingMonth);

  @Select({
      "<script>",
      "SELECT task.* FROM lp_quote_tech_task task",
      "WHERE 1=1",
      "<if test='accessMode != \"ALL\"'>AND task.active_flag=1</if>",
      "<if test='taskStatus != null and taskStatus != \"\"'>",
      "AND task.task_status=#{taskStatus}",
      "</if>",
      "<if test='accountingMonth != null and accountingMonth != \"\"'>",
      "AND task.accounting_month=#{accountingMonth}",
      "</if>",
      "<choose>",
      "<when test='accessMode == \"ALL\"'></when>",
      "<when test='accessMode == \"ASSIGNEE_OR_REVIEWER\"'>",
      "AND (task.assignee_user_id=#{userId} OR task.reviewer_user_id=#{userId})",
      "</when>",
      "<when test='accessMode == \"REVIEWER\"'>",
      "AND task.reviewer_user_id=#{userId}",
      "</when>",
      "<otherwise>AND task.assignee_user_id=#{userId}</otherwise>",
      "</choose>",
      "ORDER BY task.updated_at DESC,task.id DESC",
      "LIMIT #{offset},#{size}",
      "</script>"
  })
  List<QuoteTechTask> selectAccessiblePage(
      @Param("accessMode") String accessMode,
      @Param("userId") Long userId,
      @Param("taskStatus") String taskStatus,
      @Param("accountingMonth") String accountingMonth,
      @Param("offset") int offset,
      @Param("size") int size);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status='CANCELLED',active_flag=0,active_lock_key=NULL,
             cancelled_at=#{changedAt},external_last_error=#{reason},
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND active_flag=1
      """)
  int deactivateForReplacement(
      @Param("taskId") Long taskId,
      @Param("reason") String reason,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status = #{taskStatus},
             review_round = #{reviewRound},
             review_status = #{reviewStatus},
             reviewer_user_id = #{reviewerUserId},
             reviewer_name = #{reviewerName},
             updated_by = #{updatedBy},
             task_version = task_version + 1,
             updated_at = #{updatedAt}
       WHERE id = #{id}
         AND task_version = #{expectedVersion}
         AND active_flag = 1
      """)
  int updateStateWithVersion(
      @Param("id") Long id,
      @Param("expectedVersion") int expectedVersion,
      @Param("taskStatus") String taskStatus,
      @Param("reviewRound") int reviewRound,
      @Param("reviewStatus") String reviewStatus,
      @Param("reviewerUserId") Long reviewerUserId,
      @Param("reviewerName") String reviewerName,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedAt") LocalDateTime updatedAt);

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
         SET task_status='SUBMITTED',review_round=#{reviewRound},review_status='PENDING',
             submission_idempotency_key=#{idempotencyKey},
             submission_fingerprint=#{submissionFingerprint},submitted_at=#{submittedAt},
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{submittedAt}
       WHERE id=#{taskId}
         AND task_version=#{expectedVersion}
         AND active_flag=1
         AND task_status IN ('PENDING','IN_PROGRESS','PARTIALLY_RETURNED')
      """)
  int submitWithVersion(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("reviewRound") int reviewRound,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("submissionFingerprint") String submissionFingerprint,
      @Param("actorId") Long actorId,
      @Param("submittedAt") LocalDateTime submittedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status='PARTIALLY_RETURNED',review_status='PARTIALLY_RETURNED',
             submission_idempotency_key=NULL,submission_fingerprint=NULL,
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status='SUBMITTED' AND review_round=#{reviewRound}
      """)
  int markReviewReturned(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("reviewRound") int reviewRound,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

  @Update("""
      UPDATE lp_quote_tech_task
         SET task_status='APPROVED',review_status='PASSED',approved_at=#{changedAt},
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status='SUBMITTED' AND review_round=#{reviewRound}
      """)
  int markReviewApproved(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("reviewRound") int reviewRound,
      @Param("actorId") Long actorId,
      @Param("changedAt") LocalDateTime changedAt);

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
         SET assignee_user_id=#{assigneeUserId},assignee_name=#{assigneeName},
             reviewer_user_id=#{reviewerUserId},reviewer_name=#{reviewerName},
             active_lock_key=CONCAT('OA:',oa_no,':MONTH:',accounting_month,
               ':ASSIGNEE:',#{assigneeUserId}),
             proxy_operator_user_id=NULL,proxy_operator_name=NULL,proxy_reason=NULL,
             proxy_request_id=NULL,proxy_started_at=NULL,
             updated_by=#{actorId},task_version=task_version+1,updated_at=#{changedAt}
       WHERE id=#{taskId} AND task_version=#{expectedVersion} AND active_flag=1
         AND task_status IN ('PENDING','IN_PROGRESS','PARTIALLY_RETURNED')
      """)
  int reassign(
      @Param("taskId") Long taskId,
      @Param("expectedVersion") int expectedVersion,
      @Param("assigneeUserId") Long assigneeUserId,
      @Param("assigneeName") String assigneeName,
      @Param("reviewerUserId") Long reviewerUserId,
      @Param("reviewerName") String reviewerName,
      @Param("actorId") Long actorId,
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
