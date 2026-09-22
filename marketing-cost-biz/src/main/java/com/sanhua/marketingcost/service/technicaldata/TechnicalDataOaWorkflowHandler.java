package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.oa.OaWorkflowEvent;
import com.sanhua.marketingcost.integration.oa.OaWorkflowNotReadyException;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 业务事件去重后，验证完整业务身份，再调用对应人员的状态迁移。由接收处理器包围事务。 */
@Service
public class TechnicalDataOaWorkflowHandler {
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaUserDirectory users;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechSubmissionMapper submissions;
  private final TechnicalDataOaSubmissionLifecycle lifecycle;
  private final OaMessageCodec codec;
  private final TechnicalDataOaRecipientRepository recipients;

  public TechnicalDataOaWorkflowHandler(TechnicalDataOaWorkflowRepository workflow, TechnicalDataOaUserDirectory users,
      QuoteTechTaskMapper tasks, QuoteTechSubmissionMapper submissions, TechnicalDataOaSubmissionLifecycle lifecycle,
      OaMessageCodec codec, TechnicalDataOaRecipientRepository recipients) {
    this.workflow = workflow; this.users = users; this.tasks = tasks; this.submissions = submissions;
    this.lifecycle = lifecycle; this.codec = codec; this.recipients = recipients;
  }

  public Object handle(OaMessageRepository.Message message, OaWorkflowEvent event) {
    var operator = users.actor(message.peer(), event.operatorExternalId());
    String prior = workflow.claimEvent(message.peer(), event.eventId(), codec.canonicalHash(event), message.id());
    if (prior != null) return Map.of("outcome", "DUPLICATE", "originalResult", codec.read(prior));
    Object result = "FINANCE_ENTERED".equals(event.eventType())
        ? finance(message, event, operator) : technical(message, event, operator);
    workflow.completeEvent(message.peer(), event.eventId(), codec.write(result));
    return result;
  }

  private Object finance(OaMessageRepository.Message message, OaWorkflowEvent event, TechnicalDataActor operator) {
    if (!operator.admin() && !operator.has("ingest:quote:cost-run:execute")) throw forbidden("财务节点人员没有报价核算权限");
    var flow = workflow.lockExternalFlow(message.peer(), event.externalFlowId());
    if (flow == null || !flow.documentId().equals(event.documentId())) throw conflict("OA_FINANCE_FLOW_MISMATCH", "财务节点不属于已绑定的单据流程");
    if (event.sequence() <= flow.financeSequence()) return Map.of("outcome", "IGNORED_OLD_FINANCE", "financeReady", flow.financeReady());
    if (!workflow.businessUnits(flow.id()).stream().allMatch(message.peer().businessUnits()::contains)) throw forbidden("调用方无权处理该单据业务单元");
    workflow.finance(flow, event.sequence(), message.id(), operator.userId());
    // 允许通知网络乱序到达，但财务序号必须晚于所有当前产品的审批，且全部产品通过。
    boolean ready = workflow.refreshFinance(flow.id());
    return Map.of("outcome", ready ? "FINANCE_READY" : "WAITING_TECHNICAL_TASKS", "financeReady", ready);
  }

  private Object technical(OaMessageRepository.Message message, OaWorkflowEvent event, TechnicalDataActor operator) {
    if (!operator.admin() && !operator.has("technical:data:oa:approve")) throw forbidden("OA 审批人员没有技术资料审批权限");
    var task = tasks.selectByIdForUpdate(event.taskId());
    if (task == null || task.getOaFlowId() == null || !Objects.equals(task.getActiveFlag(), 1)) throw conflict("OA_TASK_NOT_ACTIVE", "审批任务不存在或不是活动产品任务");
    if (!message.peer().businessUnits().contains(task.getBusinessUnitType())) throw forbidden("调用方无权处理该业务单元");
    var flow = workflow.lockFlow(task.getOaFlowId());
    if (!message.peer().sourceSystem().equals(flow.sourceSystem()) || !message.peer().environment().equals(flow.environment())
        || !flow.documentId().equals(event.documentId()) || !Objects.equals(flow.externalFlowId(), event.externalFlowId())
        || flow.oaFormId() != task.getOaFormId() || !flow.accountingMonth().equals(task.getAccountingMonth())) {
      throw conflict("OA_EVENT_SCOPE_MISMATCH", "审批流程与产品、单据或核算月份不一致");
    }
    var submission = submissions.selectById(event.submissionId());
    if (submission == null || !Objects.equals(submission.getTaskId(), task.getId())
        || !Objects.equals(submission.getTechnicalVersionId(), event.technicalVersionId())
        || submission.getSubmissionRound().longValue() != event.round()) throw conflict("OA_EVENT_VERSION_MISMATCH", "审批通知与提交版本、轮次不一致");
    if (submission.getRecipientId() == null || !Objects.equals(submission.getLeaderExternalId(), event.operatorExternalId())) {
      throw forbidden("审批人不是此次提交约定的部门领导");
    }
    var person = recipients.findById(submission.getRecipientId());
    if (person == null || !person.active() || !Objects.equals(person.latestSubmissionId(), submission.getId())
        || event.sequence() <= person.callbackSequence()) {
      return Map.of("outcome", "IGNORED_OLD_ROUND", "taskId", task.getId(), "submissionId", submission.getId());
    }
    if (Set.of("PREPARED", "SENDING", "UNKNOWN").contains(submission.getSubmissionStatus())) throw new OaWorkflowNotReadyException();
    String target = "TECH_APPROVED".equals(event.eventType()) ? "APPROVED" : "RETURNED";
    if (target.equals(submission.getSubmissionStatus())) return Map.of("outcome", "DUPLICATE_DECISION", "submissionId", submission.getId());
    if (!"SENT".equals(submission.getSubmissionStatus())) throw conflict("OA_DECISION_CONFLICT", "该提交未确认发送或已存在其他审批结论");
    if ("APPROVED".equals(target)) lifecycle.approve(task, submission, message.id(), operator);
    else lifecycle.returned(task, submission, message.id(), operator, event.reason());
    recipients.sequence(person.id(), event.sequence());
    workflow.acceptSequence(task.getId(), event.sequence(), event.eventId());
    boolean ready = workflow.refreshFinance(flow.id());
    return Map.of("outcome", target, "taskId", task.getId(), "submissionId", submission.getId(),
        "technicalVersionId", submission.getTechnicalVersionId(), "financeReady", ready);
  }

  private OaIntegrationException forbidden(String message) { return conflict("OA_OPERATOR_FORBIDDEN", message); }
  private OaIntegrationException conflict(String code, String message) { return OaIntegrationException.conflict(code, message); }
}
