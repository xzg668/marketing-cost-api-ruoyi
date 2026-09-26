package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionRepository;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 将 OA 的人员/整单通知落到当前提交；复用既有冻结版本的审批、退回规则。 */
@Service
public class OaWorkflowNotificationHandler {
  public record Scope(List<Long> recipientIds, List<Long> submissionIds, Long resultId, boolean localReturn) {}
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechSubmissionMapper submissions;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaSubmissionLifecycle lifecycle;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final QuoteFinalSubmissionRepository results;
  private final OaWorkflowNotificationRepository notifications;
  private final com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalBatchRepository batches;

  public OaWorkflowNotificationHandler(JdbcTemplate jdbc, OaMessageCodec codec, QuoteTechTaskMapper tasks,
      QuoteTechSubmissionMapper submissions, TechnicalDataOaRecipientRepository recipients,
      TechnicalDataOaSubmissionLifecycle lifecycle, TechnicalDataOaWorkflowRepository workflow,
      QuoteFinalSubmissionRepository results, OaWorkflowNotificationRepository notifications,
      com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalBatchRepository batches) {
    this.jdbc=jdbc; this.codec=codec; this.tasks=tasks; this.submissions=submissions;
    this.recipients=recipients; this.lifecycle=lifecycle; this.workflow=workflow;
    this.results=results; this.notifications=notifications; this.batches=batches;
  }

  public Scope scope(OaWorkflowNotification event, OaWorkflowNotificationRepository.Document document) {
    if (!event.technical()) {
      var ids = jdbc.queryForList("SELECT id FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1", Long.class, document.id());
      if (ids.isEmpty()) throw conflict("RESULT_NOT_FOUND", "本单尚无成本提交记录，不能退回核算或确认整单完成");
      return new Scope(List.of(), List.of(), ids.getFirst(), false);
    }
    List<Long> selected = new ArrayList<>();
    boolean localReturn = false;
    for (String employee : event.employeeNos()) {
      var ids = jdbc.queryForList("""
          SELECT r.id FROM lp_quote_tech_oa_recipient r JOIN lp_quote_tech_task t ON t.id=r.task_id
          WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1 AND r.external_user_id=? ORDER BY t.id,r.id
          """, Long.class, document.id(), employee);
      if (ids.isEmpty()) throw conflict("TECHNICIAN_NOT_FOUND", "技术员工号不属于本单的当前分派：" + employee);
      if ("TECHNICAL".equals(event.eventType())) {
        // 报价系统发起 I05 时已有精确产品/模块范围，后续人员级通知不得扩大该范围。
        var returned = ids.stream().filter(this::pendingLocalReturn).toList();
        if (!returned.isEmpty()) { ids = returned; localReturn = true; }
      }
      selected.addAll(ids);
    }
    List<Long> submitted = new ArrayList<>();
    for (long id : selected) {
      var person = recipients.findById(id);
      if (person.latestSubmissionId() == null) throw conflict("TECH_SUBMISSION_NOT_FOUND", "技术员尚未提交本单任务，不能审批或退回：" + person.externalUserId());
      submitted.add(person.latestSubmissionId());
    }
    return new Scope(List.copyOf(selected), List.copyOf(submitted), null, localReturn);
  }

  private boolean pendingLocalReturn(long id) {
    var person = recipients.findById(id);
    if (!Set.of("OPEN", "RETURN_PENDING").contains(person.todoStatus()) || person.returnMessageId() == null) return false;
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_oa_integration_message m JOIN lp_oa_technical_batch b ON b.id=m.technical_batch_id
          WHERE m.id=? AND b.operation='I05' AND JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.payload.submissionId'))=?)
        """, Boolean.class, person.returnMessageId(), Objects.toString(person.latestSubmissionId(), "")));
  }

  public boolean apply(OaMessageRepository.Message message, OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document, OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    boolean changed = event.technical()
        ? technical(message, event, document, flow, scope)
        : result(message, event, document, flow, scope);
    if (!changed) return false;
    String state = switch (event.eventType()) {
      case "TECHNICAL" -> "TECHNICAL";
      case "TECH_APPROVED" -> allApproved(document.id()) ? "MATERIAL_REVIEW" : "TECHNICAL";
      case "COSTING" -> "RECOSTING";
      case "COMPLETED" -> "COMPLETED";
      default -> throw new IllegalStateException("通知类型未校验");
    };
    OaWorkflowNotificationRepository.Quoter quoter = null;
    if (Set.of("MATERIAL_REVIEW", "RECOSTING").contains(state)) {
      quoter = notifications.quoter(document.id(), scope.resultId() == null ? null : results.find(scope.resultId()).operatorId());
    }
    notifications.applied(flow, document, state, event.reason(), quoter);
    for (long id : jdbc.queryForList("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?", Long.class, document.id())) {
      if (quoter != null) {
        workflow.finance(workflow.lockFlow(id), flow.appliedVersion() + 1, message.id(), quoter.userId());
        workflow.refreshFinance(id);
      } else workflow.invalidateFinance(id);
    }
    if ("COMPLETED".equals(state)) close(document.id(), message);
    return true;
  }

  private boolean technical(OaMessageRepository.Message message, OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document, OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    boolean changed = false;
    var operator = new TechnicalDataActor(0L, "OA状态通知", Set.of());
    for (long recipientId : scope.recipientIds()) {
      var person = recipients.findById(recipientId);
      var task = tasks.selectByIdForUpdate(person.taskId());
      var submission = submissions.selectById(person.latestSubmissionId());
      lifecycle.requireCurrent(task, submission);
      requireVersion(submission.getId(), document.sourceVersion(), "lp_quote_tech_submission");
      if ("TECH_APPROVED".equals(event.eventType())) {
        if ("DONE".equals(person.todoStatus()) && "APPROVED".equals(submission.getSubmissionStatus())) continue;
        requireSent(submission.getSubmissionStatus());
        lifecycle.confirmFromNotification(task, submission);
        lifecycle.approve(task, submissions.selectById(submission.getId()), message.id(), operator);
        setEditable(person.taskId(), person.userId(), false);
      } else {
        if ("OPEN".equals(person.todoStatus()) && ("RETURNED".equals(submission.getSubmissionStatus()) || pendingLocalReturn(person.id()))) continue;
        if (pendingLocalReturn(person.id())) {
          throw conflict("RETURN_RECEIPT_PENDING", "报价系统定向退回尚未完成回执处理，请稍后重试原通知");
        }
        if ("APPROVED".equals(submission.getSubmissionStatus()) && "DONE".equals(person.todoStatus())) {
          recipients.revisionScope(person.id(), person.modules());
          recipients.requestReturn(person.id(), submission.getId(), message.id(), 0, event.reason());
          lifecycle.financeReturned(task, submission, 0, event.reason());
        } else {
          requireSent(submission.getSubmissionStatus());
          lifecycle.confirmFromNotification(task, submission);
          lifecycle.returned(task, submissions.selectById(submission.getId()), message.id(), operator, event.reason());
        }
        setEditable(person.taskId(), person.userId(), true);
      }
      long sequence = flow.appliedVersion() + 1;
      recipients.sequence(person.id(), sequence);
      workflow.acceptSequence(task.getId(), sequence, message.requestId());
      confirmOutbound(submission.getOutboundMessageId(), message.id());
      changed = true;
    }
    for (long submissionId : scope.submissionIds()) {
      var batchIds = jdbc.queryForList("""
          SELECT m.technical_batch_id FROM lp_quote_tech_submission s JOIN lp_oa_integration_message m ON m.id=s.outbound_message_id
          WHERE s.id=? AND m.technical_batch_id IS NOT NULL
          """, String.class, submissionId);
      for (String batchId : batchIds) batches.confirmSubmissionNotification(batchId, message.id());
    }
    // I05 已恢复精确范围时，通知只同步流程状态，不能再恢复整个人员的板块。
    return changed || scope.localReturn() && !"TECHNICAL".equals(flow.state());
  }

  private void setEditable(long taskId, long userId, boolean allowed) {
    jdbc.update("""
        UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
        SET m.oa_edit_allowed=? WHERE p.task_id=? AND p.active_flag=1 AND m.assignee_user_id=?
        """, allowed ? 1 : 0, taskId, userId);
  }
  private void requireSent(String status) {
    if (!Set.of("SENT", "SENDING", "UNKNOWN").contains(status)) {
      throw conflict("TECH_SUBMISSION_STATE_CONFLICT", "技术资料当前不是已发送待审批状态，不能使用此通知改变草稿或旧审批结论");
    }
  }
  private boolean allApproved(long formId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT NOT EXISTS(SELECT 1 FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id
          LEFT JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
          WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1
            AND (r.todo_status<>'DONE' OR COALESCE(s.submission_status,'')<>'APPROVED'))
        """, Boolean.class, formId));
  }
  private boolean result(OaMessageRepository.Message message, OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document, OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    var result = results.lock(scope.resultId());
    if (!Objects.equals(result.documentId(), event.requestId()) || (!result.peer().sourceSystem().equals(message.peer().sourceSystem()) || !result.peer().environment().equals(message.peer().environment()))) {
      throw conflict("RESULT_SCOPE_MISMATCH", "成本提交与通知的原 OA 流程或来源不一致");
    }
    requireVersion(result.id(), document.sourceVersion(), "lp_quote_final_submission");
    if (result.messageId() == null || !codec.read(result.snapshotJson()).isArray()
        || codec.read(result.snapshotJson()).isEmpty()) throw conflict("RESULT_NOT_SUBMITTED", "本单尚无已冻结并发送的成本提交");
    if ("COSTING".equals(event.eventType()) && "RETURNED".equals(result.status())) return false;
    if (!Set.of("PENDING", "UNKNOWN", "SUBMITTED").contains(result.status())) {
      throw conflict("RESULT_STATE_CONFLICT", "本轮成本提交已经退回或结束，须重新提交后再审批");
    }
    if ("COSTING".equals(event.eventType())) results.returned(result.id(), flow.appliedVersion()+1, event.reason());
    else results.state(result.id(), "COMPLETED", null);
    confirmOutbound(result.messageId(), message.id());
    return true;
  }
  private void close(long formId, OaMessageRepository.Message message) {
    jdbc.update("""
        UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
          JOIN lp_quote_tech_task t ON t.id=p.task_id SET m.oa_edit_allowed=0 WHERE t.oa_form_id=?
        """, formId);
    jdbc.update("""
        UPDATE lp_oa_integration_message m SET m.status='REJECTED',m.error_code='FLOW_CLOSED',
          m.error_message='OA流程已结束，停止旧办理',m.lease_token=NULL,m.lease_until=NULL
        WHERE m.direction='OUTBOUND' AND m.source_system=? AND m.environment=?
          AND m.status IN ('RECEIVED','PROCESSING','WAITING_HANDLER','FAILED')
          AND (JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.payload.documentId'))=?
            OR m.technical_batch_id IN (SELECT id FROM lp_oa_technical_batch WHERE oa_form_id=?))
        """, message.peer().sourceSystem(), message.peer().environment(),
        codec.read(message.rawPayload()).path("requestId").asText(), formId);
  }
  private void requireVersion(long id, long expected, String table) {
    Long version=jdbc.queryForObject("SELECT source_form_version FROM "+table+" WHERE id=?",Long.class,id);
    if (version == null || version != expected) throw conflict("SUBMISSION_FORM_VERSION_MISMATCH", "提交采用的需求版本与本单不一致");
  }
  private void confirmOutbound(Long id, long notificationId) {
    if (id == null) return;
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='PROCESSED',lease_token=NULL,lease_until=NULL,processed_at=NOW(3),
          error_code=NULL,error_message=NULL,result_json=? WHERE id=? AND direction='OUTBOUND' AND status<>'PROCESSED'
        """, codec.write(java.util.Map.of("confirmedByNotificationId", notificationId)), id);
  }
  private static OaIntegrationException conflict(String code, String message) {
    return OaIntegrationException.conflict(code,message);
  }
}
