package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionRepository;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataReturnService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 通知描述 OA 到达的办理节点；只有已有在审成本时，进入核算节点才同时退回该成本。 */
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
  private final TechnicalDataReturnService returns;
  private final com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaDispatchService dispatch;

  public OaWorkflowNotificationHandler(JdbcTemplate jdbc, OaMessageCodec codec, QuoteTechTaskMapper tasks,
      QuoteTechSubmissionMapper submissions, TechnicalDataOaRecipientRepository recipients,
      TechnicalDataOaSubmissionLifecycle lifecycle, TechnicalDataOaWorkflowRepository workflow,
      QuoteFinalSubmissionRepository results, OaWorkflowNotificationRepository notifications,
      com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalBatchRepository batches,
      TechnicalDataReturnService returns,
      com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaDispatchService dispatch) {
    this.jdbc=jdbc; this.codec=codec; this.tasks=tasks; this.submissions=submissions;
    this.recipients=recipients; this.lifecycle=lifecycle; this.workflow=workflow;
    this.results=results; this.notifications=notifications; this.batches=batches;
    this.returns=returns;
    this.dispatch=dispatch;
  }

  public Scope scope(OaWorkflowNotification event, OaWorkflowNotificationRepository.Document document) {
    if ("COSTING".equals(event.eventType())) return costingScope(document);
    if (!event.technical()) {
      var ids = jdbc.queryForList("SELECT id FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1", Long.class, document.id());
      if (ids.isEmpty()) throw conflict("RESULT_NOT_FOUND", "本单尚无成本提交记录，不能确认整单完成");
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
      if (person.latestSubmissionId() != null) submitted.add(person.latestSubmissionId());
    }
    if (!submitted.isEmpty() && submitted.size() != selected.size()) {
      throw conflict("TECH_STAGE_AMBIGUOUS", "本单技术员的产品办理阶段不一致，不能将首次补录和已提交资料一起退回");
    }
    return new Scope(List.copyOf(selected), List.copyOf(submitted), null, localReturn);
  }

  private Scope costingScope(OaWorkflowNotificationRepository.Document document) {
    var result = results.latest(document.id());
    // 同一原流程可以多次补录、重提；去重绑定当前分派及提交版本，不把下一轮同意当成旧通知。
    var rows = jdbc.queryForList("""
        SELECT r.id,r.latest_submission_id FROM lp_quote_tech_oa_recipient r
        JOIN lp_quote_tech_task t ON t.id=r.task_id
        WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1 ORDER BY t.id,r.id
        """, document.id());
    var recipientIds = new ArrayList<Long>();
    var submissionIds = new ArrayList<Long>();
    for (var row : rows) {
      recipientIds.add(((Number) row.get("id")).longValue());
      if (row.get("latest_submission_id") instanceof Number id) submissionIds.add(id.longValue());
    }
    return new Scope(List.copyOf(recipientIds), List.copyOf(submissionIds), result == null ? null : result.id(), false);
  }

  public OaWorkflowNotification resolveTechnicians(OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document) {
    if (!event.technical()) return event;
    confirmPendingDispatch(event, document);
    if (!event.employeeNos().isEmpty()) return event;
    var people = jdbc.queryForList("""
        SELECT DISTINCT r.assignee_user_id,r.external_user_id
        FROM lp_quote_tech_oa_recipient r JOIN lp_quote_tech_task t ON t.id=r.task_id
        WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1
        """, document.id());
    if (people.isEmpty()) throw conflict("TECHNICIAN_NOT_FOUND", "本单没有已生效的技术员分派，请先完成分派");
    if (people.size() != 1) throw conflict("TECHNICIAN_AMBIGUOUS", "本单存在多位技术员，无法自动确定本次通知的办理人");
    Object number = people.getFirst().get("external_user_id");
    if (!(number instanceof String employee) || employee.isBlank()) {
      throw conflict("TECHNICIAN_NOT_FOUND", "本单已分派技术员缺少工号");
    }
    return new OaWorkflowNotification(event.requestId(), event.eventType(), List.of(employee), event.reason());
  }

  private void confirmPendingDispatch(OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document) {
    var pending = jdbc.queryForList("""
        SELECT DISTINCT b.id FROM lp_oa_technical_batch b
        JOIN lp_oa_integration_message m ON m.technical_batch_id=b.id
        JOIN lp_quote_tech_oa_recipient r ON r.outbound_message_id=m.id
        JOIN lp_quote_tech_task t ON t.id=r.task_id
        WHERE b.oa_form_id=? AND b.operation='I02' AND b.status IN ('SENDING','UNKNOWN','OA_ACCEPTED')
          AND t.oa_form_id=b.oa_form_id AND t.active_flag=1 AND t.oa_assignment_version=r.assignment_version
          AND r.active_flag=0 AND r.todo_status='WAITING' AND r.action='ASSIGN'
        ORDER BY b.id
        """, String.class, document.id());
    if (pending.isEmpty()) return;
    if (pending.size() != 1) throw conflict("TECH_DISPATCH_AMBIGUOUS", "本单存在多批未确认分派，无法确定本次技术通知的范围");
    String batchId = pending.getFirst();
    // 与同步HTTP回执采用相同的批次→任务锁顺序；回执先完成时直接使用已激活分派。
    if ("SUCCESS".equals(batches.find(batchId, true).status())) return;
    var rows = batches.messageIds(batchId).stream().map(recipients::findByMessage).toList();
    var employees = rows.stream().map(TechnicalDataOaRecipientRepository.Recipient::externalUserId)
        .collect(java.util.stream.Collectors.toSet());
    if (event.employeeNos().isEmpty() && employees.size() != 1) {
      throw conflict("TECHNICIAN_AMBIGUOUS", "本次分派存在多位技术员，无法自动确定办理人");
    }
    if (!event.employeeNos().isEmpty() && !event.employeeNos().containsAll(employees)) {
      throw conflict("TECH_DISPATCH_SCOPE_MISMATCH", "技术通知未覆盖正在发送的分派人员");
    }
    for (var row : rows) {
      var task = tasks.selectByIdForUpdate(row.taskId());
      if (task.getOaFormId() != document.id() || !Objects.equals(task.getOaAssignmentVersion(), row.assignmentVersion())
          || !"ASSIGN".equals(row.action()) || row.latestSubmissionId() != null) {
        throw conflict("TECH_DISPATCH_SCOPE_MISMATCH", "本次分派范围已变化，不能由通知确认");
      }
    }
    dispatch.confirmFromNotification(batchId, event.requestId());
  }

  /** 首次通知去重绑定分派身份；后续资料提交不应把迟到重发解释成退回。 */
  public Scope initialScope(OaWorkflowNotification event, OaWorkflowNotificationRepository.Document document) {
    List<Long> ids = new ArrayList<>();
    for (String employee : event.employeeNos()) {
      ids.addAll(jdbc.queryForList("""
          SELECT r.id FROM lp_quote_tech_oa_recipient r JOIN lp_quote_tech_task t ON t.id=r.task_id
          WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1 AND r.external_user_id=? ORDER BY t.id,r.id
          """, Long.class, document.id(), employee));
    }
    return new Scope(List.copyOf(ids), List.of(), null, false);
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
    String state = switch (event.eventType()) {
      case "TECHNICAL" -> technical(message, event, document, flow, scope) ? "TECHNICAL" : null;
      case "COSTING" -> costing(message, event, document, flow, scope);
      case "COMPLETED" -> result(message, event, document, flow, scope) ? "COMPLETED" : null;
      default -> throw new IllegalStateException("通知类型未校验");
    };
    if (state == null) return false;
    OaWorkflowNotificationRepository.Quoter quoter = null;
    if (Set.of("COSTING", "RECOSTING").contains(state)) {
      quoter = notifications.quoter(document.id(), scope.resultId() == null ? null : results.find(scope.resultId()).operatorId());
    }
    notifications.applied(flow, document, state, event.reason(), quoter);
    for (long id : jdbc.queryForList("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?", Long.class, document.id())) {
      if (quoter != null) {
        workflow.refreshFinance(id);
      } else workflow.invalidateFinance(id);
    }
    if ("COMPLETED".equals(state)) close(document.id(), message);
    return true;
  }

  private String costing(OaMessageRepository.Message message, OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document, OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    var previous = scope.resultId() == null ? null : results.find(scope.resultId());
    // COSTING 是节点，不是审批动作；未提交过成本（含明确发送失败）也可以进入待核算。
    // 此处不审批技术资料，资料是否齐全仍由既有核算检查判断。
    if (previous == null || Set.of("NOT_SENT", "REJECTED").contains(previous.status())) return "COSTING";
    if ("RETURNED".equals(previous.status())) {
      // 已退回后又经历技术补录，只恢复核算节点，保留原成本退回原因、序号和冻结快照。
      return "RECOSTING".equals(flow.state()) ? null : "RECOSTING";
    }
    result(message, event, document, flow, scope);
    return "RECOSTING";
  }

  private boolean technical(OaMessageRepository.Message message, OaWorkflowNotification event,
      OaWorkflowNotificationRepository.Document document, OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    if (scope.submissionIds().isEmpty()) return enterTechnical(flow, scope);
    if (!scope.localReturn() && deferUntilSubmissionReceipt(message, scope)) return false;
    boolean changed = false;
    boolean waitingForReturnReceipt = false;
    var operator = new TechnicalDataActor(0L, "OA状态通知", Set.of());
    for (long recipientId : scope.recipientIds()) {
      var person = recipients.findById(recipientId);
      var task = tasks.selectByIdForUpdate(person.taskId());
      var submission = submissions.selectById(person.latestSubmissionId());
      lifecycle.requireCurrent(task, submission);
      requireVersion(submission.getId(), document.sourceVersion(), "lp_quote_tech_submission");
      if ("OPEN".equals(person.todoStatus()) && ("RETURNED".equals(submission.getSubmissionStatus()) || pendingLocalReturn(person.id()))) continue;
      if (pendingLocalReturn(person.id())) {
        var batchId = jdbc.queryForObject("SELECT technical_batch_id FROM lp_oa_integration_message WHERE id=?",
            String.class, person.returnMessageId());
        if (returns.confirmFromNotification(batchId, event, message.id())) changed = true;
        else waitingForReturnReceipt = true;
        continue;
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
      long sequence = flow.appliedVersion() + 1;
      recipients.sequence(person.id(), sequence);
      workflow.acceptSequence(task.getId(), sequence, message.requestId());
      confirmOutbound(submission.getOutboundMessageId(), message.id());
      changed = true;
    }
    if (waitingForReturnReceipt) return false;
    // I05 已恢复精确范围时，通知只同步流程状态，不能再恢复整个人员的板块。
    return changed || scope.localReturn() && !"TECHNICAL".equals(flow.state());
  }

  /** 节点通知不是 I03 提交回执；先保存通知，实际提交成功后才应用退回。 */
  private boolean deferUntilSubmissionReceipt(OaMessageRepository.Message message, Scope scope) {
    for (long submissionId : scope.submissionIds()) {
      var ids = jdbc.queryForList("""
          SELECT b.id FROM lp_quote_tech_submission s
          JOIN lp_oa_integration_message m ON m.id=s.outbound_message_id
          JOIN lp_oa_technical_batch b ON b.id=m.technical_batch_id
          WHERE s.id=? AND b.operation='I03'
          """, String.class, submissionId);
      for (String id : ids) {
        var batch = batches.find(id, true);
        if ("SUCCESS".equals(batch.status())) continue;
        boolean failed = Set.of("REJECTED", "NOT_SENT").contains(batch.status());
        jdbc.update("""
            UPDATE lp_oa_integration_message SET technical_batch_id=?,status=?,error_stage='DELIVERY',
              error_code=?,error_message=? WHERE id=? AND direction='INBOUND'
            """, id, failed ? "REJECTED" : "WAITING_HANDLER",
            failed ? "OA_SUBMISSION_FAILED" : "OA_SUBMISSION_PENDING",
            failed ? "提交未成功，节点通知不作为退回依据" : "通知已接收，等待提交接口实际回执", message.id());
        return true;
      }
    }
    return false;
  }

  private boolean enterTechnical(OaWorkflowNotificationRepository.Flow flow, Scope scope) {
    for (long recipientId : scope.recipientIds()) {
      var person = recipients.findById(recipientId);
      if (!"CONFIRMED".equals(person.dispatchStatus())) {
        throw conflict("TECH_DISPATCH_NOT_CONFIRMED", "技术员分派尚未确认，请先完成分派");
      }
      if (!"OPEN".equals(person.todoStatus()) || person.latestSubmissionId() != null) {
        throw conflict("TECH_ASSIGNMENT_STATE_CONFLICT", "技术员当前不是首次补录待办，不能按首次进入处理");
      }
      tasks.selectByIdForUpdate(person.taskId());
      setEditable(person.taskId(), person.userId(), true);
      recipients.sequence(person.id(), flow.appliedVersion() + 1);
    }
    return !scope.recipientIds().isEmpty();
  }

  private void setEditable(long taskId, long userId, boolean allowed) {
    jdbc.update("""
        UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
        SET m.oa_edit_allowed=? WHERE p.task_id=? AND p.active_flag=1 AND m.assignee_user_id=?
        """, allowed ? 1 : 0, taskId, userId);
  }
  private void requireSent(String status) {
    if (!Set.of("SENT", "SENDING", "UNKNOWN").contains(status)) {
      throw conflict("TECH_SUBMISSION_STATE_CONFLICT", "技术资料当前不是已发送状态，不能使用此通知改变草稿或旧提交记录");
    }
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
          m.error_message='OA流程已结束，停止旧办理'
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
        UPDATE lp_oa_integration_message SET status='PROCESSED',processed_at=NOW(3),
          error_code=NULL,error_message=NULL,result_json=? WHERE id=? AND direction='OUTBOUND' AND status<>'PROCESSED'
        """, codec.write(java.util.Map.of("confirmedByNotificationId", notificationId)), id);
  }
  private static OaIntegrationException conflict(String code, String message) {
    return OaIntegrationException.conflict(code,message);
  }
}
