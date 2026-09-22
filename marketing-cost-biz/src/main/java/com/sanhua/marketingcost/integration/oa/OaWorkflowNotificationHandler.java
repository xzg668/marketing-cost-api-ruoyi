package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.integration.technicaldata.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 将通知的人员任务映射到现有补录版本；审批历史由既有生命周期服务保留。 */
@Service
public class OaWorkflowNotificationHandler {
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechSubmissionMapper submissions;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaSubmissionLifecycle lifecycle;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaUserDirectory users;
  private final QuoteFinalSubmissionRepository results;
  private final TechnicalDataAuditLogService audit;
  public OaWorkflowNotificationHandler(JdbcTemplate jdbc,OaMessageCodec codec,QuoteTechTaskMapper tasks,
      QuoteTechSubmissionMapper submissions,TechnicalDataOaRecipientRepository recipients,
      TechnicalDataOaSubmissionLifecycle lifecycle,TechnicalDataOaWorkflowRepository workflow,
      TechnicalDataOaUserDirectory users,QuoteFinalSubmissionRepository results,TechnicalDataAuditLogService audit) {
    this.jdbc=jdbc;this.codec=codec;this.tasks=tasks;this.submissions=submissions;this.recipients=recipients;
    this.lifecycle=lifecycle;this.workflow=workflow;this.users=users;this.results=results;this.audit=audit;
  }
  public void apply(OaMessageRepository.Message message,OaWorkflowNotification event,long formId) {
    // 整单锁与报价最终确认使用相同锁，避免审批到达时并发修改原结果。
    if(event.taskId()!=null) technical(message,event,formId);
    if(event.resultSubmissionId()!=null) result(message,event,formId);
    if(Set.of("COSTING","RECOSTING").contains(event.state())) {
      Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_task t JOIN lp_quote_tech_product p ON p.task_id=t.id AND p.active_flag=1 WHERE t.oa_form_id=? AND t.active_flag=1 AND (t.task_status<>'APPROVED' OR p.effective_version_id IS NULL OR EXISTS(SELECT 1 FROM lp_quote_tech_oa_recipient r LEFT JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id WHERE r.task_id=t.id AND r.active_flag=1 AND COALESCE(s.source_form_version,0)<>?))",Long.class,formId,event.formVersion());
      if(pending!=null && pending>0) throw conflict("TECH_APPROVAL_INCOMPLETE", "仍有必要技术任务未获批或获批资料未匹配，不能进入核算");
    }
    // 全量替换后的权限由模块标志及成本入口共同控制，不把领导工号当作技术负责人工号。
    jdbc.update("UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id JOIN lp_quote_tech_task t ON t.id=p.task_id SET m.oa_edit_allowed=0 WHERE t.oa_form_id=?",formId);
    if(event.state().equals("TECHNICAL")) for(var item:event.activeWorkItems()) {
      if(!item.nodeRole().equals("TECHNICIAN")) continue;
      var person=person(item.taskId(),formId);
      var actor=users.actor(message.peer(),item.employeeNo());
      if(!person.active() || person.userId()!=actor.userId()) throw conflict("TASK_ASSIGNEE_MISMATCH","待办工号与技术任务负责人不一致："+item.taskId());
      jdbc.update("UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id SET m.oa_edit_allowed=1 WHERE p.task_id=? AND m.assignee_user_id=? AND p.active_flag=1",person.taskId(),person.userId());
      recipients.returnedTodo(person.id(),item.workItemId());
    }
    var flowIds=jdbc.queryForList("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?",Long.class,formId);
    for(long id:flowIds) {
      if(Set.of("COSTING","RECOSTING").contains(event.state())) {
        var item=event.activeWorkItems().stream().filter(w->w.nodeRole().equals("COSTING")).findFirst().orElseThrow();
        var actor=users.actor(message.peer(),item.employeeNo());
        workflow.finance(workflow.lockFlow(id),event.version(),message.id(),actor.userId());
        workflow.refreshFinance(id);
      } else workflow.invalidateFinance(id);
    }
    if(OaWorkflowNotification.CLOSED.contains(event.state()) || event.state().equals("WITHDRAWN")) {
      // 已结束或撤回的业务请求不再由旧发送箱继续推进节点；原文和已完成记录保留。
      jdbc.update("""
          UPDATE lp_oa_integration_message m SET m.status='REJECTED',m.error_code='FLOW_CLOSED',
            m.error_message='OA流程已结束或撤回，停止旧办理',m.lease_token=NULL,m.lease_until=NULL
          WHERE m.direction='OUTBOUND' AND m.source_system=? AND m.environment=?
            AND m.status IN ('RECEIVED','PROCESSING','WAITING_HANDLER','FAILED')
            AND (JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.workflowRequestId'))=?
              OR JSON_UNQUOTE(JSON_EXTRACT(m.raw_payload,'$.payload.documentId'))=?)
          """,message.peer().sourceSystem(),message.peer().environment(),event.workflowRequestId(),event.workflowRequestId());
    }
  }
  private TechnicalDataOaRecipientRepository.Recipient person(String key,long formId) {
    var ids=jdbc.queryForList("SELECT r.id FROM lp_quote_tech_oa_recipient r JOIN lp_quote_tech_task t ON t.id=r.task_id WHERE r.integration_task_id=?",Long.class,key);
    if(ids.isEmpty()) throw conflict("TASK_NOT_FOUND", "未找到本地技术任务，请核对taskId：" + key);
    var person=recipients.findById(ids.getFirst());
    var task=tasks.selectByIdForUpdate(person.taskId());
    if(task==null || task.getOaFormId()!=formId) throw conflict("TASK_SCOPE_MISMATCH","技术任务不属于本报价流程："+key);
    return person;
  }
  private void technical(OaMessageRepository.Message message,OaWorkflowNotification event,long formId) {
    var person=person(event.taskId(),formId);
    var task=tasks.selectByIdForUpdate(person.taskId());
    if(!person.active() && !"WAITING".equals(person.todoStatus()) && !"CANCELLED".equals(event.taskState())) throw conflict("TASK_INACTIVE","技术任务已失效");
    com.sanhua.marketingcost.entity.QuoteTechSubmission submission=null;
    if(event.submissionId()!=null) {
      var ids=jdbc.queryForList("SELECT s.id FROM lp_quote_tech_submission s JOIN lp_oa_integration_message m ON m.id=s.outbound_message_id WHERE m.source_system=? AND m.environment=? AND m.direction='OUTBOUND' AND m.request_id=?",Long.class,message.peer().sourceSystem(),message.peer().environment(),event.submissionId());
      if(ids.isEmpty()) throw conflict("TECH_SUBMISSION_NOT_FOUND", "未找到本地冻结资料提交，请核对submissionId：" + event.submissionId());
      submission=submissions.selectById(ids.getFirst());
      if(event.eventType().startsWith("TECH_") || "IN_REVIEW".equals(event.taskState())) requireSourceVersion("lp_quote_tech_submission",submission.getId(),event.formVersion());
      if(!Objects.equals(submission.getTaskId(),task.getId()) || !Objects.equals(submission.getRecipientId(),person.id()) || !Objects.equals(person.latestSubmissionId(),submission.getId())) throw conflict("SUBMISSION_SCOPE_MISMATCH","通知不是该任务的当前资料提交");
    }
    if(event.eventType().startsWith("TECH_")) {
      lifecycle.confirmFromNotification(task,submission);
      // 通知不提供实际审批人；使用明确的系统审计身份，不能伪造领导身份。
      var system=new TechnicalDataActor(0L,"OA状态通知",Set.of());
      if(event.eventType().equals("TECH_APPROVED")) lifecycle.approve(task,submission,message.id(),system);
      else {
        lifecycle.returned(task,submission,message.id(),system,event.reason());
        var next=event.activeWorkItems().stream().filter(w->w.nodeRole().equals("TECHNICIAN") && event.taskId().equals(w.taskId())).findFirst().orElseThrow();
        reassign(person,event.taskId(),users.actor(message.peer(),next.employeeNo()),next.employeeNo());
      }
      recipients.sequence(person.id(),event.version());
      workflow.acceptSequence(task.getId(),event.version(),event.requestId());
      confirmOutbound(submission.getOutboundMessageId(),message.id());
    } else if(event.eventType().equals("TASK_CHANGED")) {
      var actor=users.actor(message.peer(),event.employeeNo());
      switch(event.taskState()) {
        case "IN_REVIEW" -> {
          if(person.userId()!=actor.userId()) throw conflict("TASK_ASSIGNEE_MISMATCH","审批中任务的技术员工号不一致");
          lifecycle.confirmFromNotification(task,submission);
        }
        case "EDITABLE", "RETURNED" -> {
          if(submission!=null && "APPROVED".equals(submission.getSubmissionStatus())) {
            recipients.requestReturn(person.id(),submission.getId(),message.id(),0,event.reason());
            lifecycle.financeReturned(task,submission,0,event.reason());
          } else if(!Set.of("OPEN","WAITING").contains(person.todoStatus())) throw conflict("TASK_STATE_CONFLICT","本次任务变化不能越过未确认的资料提交");
          reassign(person,event.taskId(),actor,event.employeeNo());
          jdbc.update("UPDATE lp_quote_tech_oa_recipient SET dispatch_status='CONFIRMED',todo_status='OPEN',active_flag=1 WHERE integration_task_id=?",event.taskId());
          jdbc.update("UPDATE lp_quote_tech_task SET external_task_status='PUBLISHED' WHERE id=?",task.getId());
          recipients.refreshTask(task.getId());
        }
        case "CANCELLED" -> {
          if(!person.active() || person.userId()!=actor.userId()) throw conflict("TASK_ASSIGNEE_MISMATCH","取消任务工号与原负责人不一致");
          jdbc.update("UPDATE lp_quote_tech_oa_recipient SET active_flag=0,todo_status='CANCELLED',return_reason=? WHERE id=?",event.reason(),person.id());
          jdbc.update("UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id SET m.oa_edit_allowed=0 WHERE p.task_id=? AND m.assignee_user_id=?",person.taskId(),person.userId());
          if(recipients.current(task.getId()).isEmpty()) {
            jdbc.update("UPDATE lp_quote_tech_product SET active_flag=0,active_lock_key=NULL WHERE task_id=?",task.getId());
            jdbc.update("UPDATE lp_quote_tech_task SET active_flag=0,active_lock_key=NULL,task_status='CANCELLED',external_task_status='CANCELLED' WHERE id=?",task.getId());
          } else recipients.refreshTask(task.getId());
        }
        default -> throw new IllegalStateException("已校验的任务状态不存在");
      }
      if ("IN_REVIEW".equals(event.taskState())) confirmOutbound(submission.getOutboundMessageId(),message.id());
      if (person.messageId()>0 && "WAITING".equals(person.todoStatus())) confirmOutbound(person.messageId(),message.id());
      audit.recordSystem(task,"OA_TASK_CHANGED",person.todoStatus(),event.taskState(),event.reason(),event.requestId(),"OA-NOTIFY:"+message.id());
    }
  }
  private void reassign(TechnicalDataOaRecipientRepository.Recipient person,String key,TechnicalDataActor actor,String employeeNo) {
    if(person.userId()==actor.userId()) return;
    // 更换人员创建新分支，原提交仍关联原负责人；保留稳定的对外任务编号和模块范围。
    jdbc.update("UPDATE lp_quote_tech_oa_recipient SET active_flag=0,todo_status='SUPERSEDED',integration_task_id=NULL WHERE id=?",person.id());
    int version=jdbc.queryForObject("SELECT MAX(assignment_version)+1 FROM lp_quote_tech_oa_recipient WHERE task_id=?",Integer.class,person.taskId());
    jdbc.update("""
      INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,
        action,module_types_json,outbound_message_id,dispatch_status,todo_status,active_flag,integration_task_id)
      SELECT task_id,?,?,?,?,'ASSIGN',module_types_json,NULL, 'CONFIRMED','OPEN',1,? FROM lp_quote_tech_oa_recipient WHERE id=?
      """,version,actor.userId(),actor.name(),employeeNo,key,person.id());
    jdbc.update("UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id SET m.assignee_user_id=?,m.assignee_name=?,m.row_version=m.row_version+1 WHERE p.task_id=? AND p.active_flag=1 AND m.assignee_user_id=?",actor.userId(),actor.name(),person.taskId(),person.userId());
  }
  private void result(OaMessageRepository.Message message,OaWorkflowNotification event,long formId) {
    var ids=jdbc.queryForList("SELECT s.id FROM lp_quote_final_submission s JOIN lp_oa_integration_message m ON m.id=s.outbound_message_id WHERE m.direction='OUTBOUND' AND m.source_system=? AND m.environment=? AND m.request_id=?",Long.class,message.peer().sourceSystem(),message.peer().environment(),event.resultSubmissionId());
    if(ids.isEmpty()) throw conflict("RESULT_NOT_FOUND", "未找到本地成本提交记录，请核对resultSubmissionId：" + event.resultSubmissionId());
    var result=results.lock(ids.getFirst());
    requireSourceVersion("lp_quote_final_submission",result.id(),event.formVersion());
    if(result.formId()!=formId || !Objects.equals(result.documentId(),event.workflowRequestId())) throw conflict("RESULT_SCOPE_MISMATCH","成本提交不属于本报价流程");
    if(!"QUOTE_COST_SUBMIT".equals(result.step()) || !codec.read(result.snapshotJson()).isArray() || codec.read(result.snapshotJson()).isEmpty()) throw conflict("RESULT_NOT_SUBMITTED","通知没有匹配已冻结并发送的成本提交");
    var latest=results.latest(formId,result.month());
    if(latest.id()!=result.id()) throw conflict("STALE_RESULT","通知对应旧成本提交，不能覆盖本轮结果");
    if (!Set.of("PENDING", "UNKNOWN", "SUBMITTED").contains(result.status())) {
      throw conflict("RESULT_STATE_CONFLICT", "本次成本提交已退回或结束；重新提交后须使用新的resultSubmissionId");
    }
    if(event.eventType().equals("RESULT_RETURNED")) results.returned(result.id(),event.version(),event.reason());
    else if(event.eventType().equals("PROCESS_COMPLETED")) results.state(result.id(),"COMPLETED",null);
    else results.state(result.id(),"SUBMITTED",null);
    confirmOutbound(result.messageId(),message.id());
  }
  private void requireSourceVersion(String table,long id,long expected) {
    Long version=jdbc.queryForObject("SELECT source_form_version FROM "+table+" WHERE id=?",Long.class,id);
    if(version==null) throw conflict("SUBMISSION_FORM_VERSION_MISSING", "提交缺少原需求版本记录，请核对本地冻结提交");
    if(version!=expected) throw conflict("SUBMISSION_FORM_VERSION_MISMATCH","审批通知需求版本与本地冻结提交采用的版本不一致");
  }
  private void confirmOutbound(Long id,long notificationId) {
    if(id==null) return;
    jdbc.update("UPDATE lp_oa_integration_message SET status='PROCESSED',lease_token=NULL,lease_until=NULL,processed_at=NOW(3),error_code=NULL,error_message=NULL,result_json=? WHERE id=? AND direction='OUTBOUND' AND status<>'PROCESSED'",
        codec.write(Map.of("confirmedByNotificationId",notificationId)),id);
  }
  private static OaIntegrationException conflict(String code,String message) { return OaIntegrationException.conflict(code,message); }
}
