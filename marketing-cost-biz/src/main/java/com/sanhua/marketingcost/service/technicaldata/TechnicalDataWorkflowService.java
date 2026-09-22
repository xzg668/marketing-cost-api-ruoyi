package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.technicaldata.OaDeliveryUnknownException;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository.Flow;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务操作仍在现有 OA 核算节点；这里只确认资料版本和请求定向退回。 */
@Service
public class TechnicalDataWorkflowService {
  public record Status(List<Person> participants, boolean financeReady, boolean financeConfirmed,
      boolean canFinanceReview, String approvalFingerprint, List<String> editableModules,
      List<String> assignedModules, boolean canAdminister, boolean canViewSupplementOverview,
      List<TechnicalDataDependencies.Issue> dependencyIssues) {}
  public record Person(long recipientId, long assigneeUserId, String assigneeName, List<String> moduleTypes,
      String departmentName, String leaderName, String status, int round, Long submissionId,
      String returnReason, boolean canSubmit, boolean canRetry) {}

  private com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy oaWorkflowAccess;
  @org.springframework.beans.factory.annotation.Autowired
  public void setOaWorkflowAccess(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy policy) { this.oaWorkflowAccess=policy; }
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final QuoteTechSubmissionMapper submissions;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaUserDirectory users;
  private final TechnicalDataOaSubmissionLifecycle lifecycle;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataAuditLogService audit;
  private final TechnicalDataDependencies dependencies;

  public TechnicalDataWorkflowService(QuoteTechTaskMapper tasks, QuoteTechModuleMapper modules, QuoteTechSubmissionMapper submissions,
      TechnicalDataOaRecipientRepository recipients, TechnicalDataOaWorkflowRepository workflow, TechnicalDataOaGateway gateway,
      TechnicalDataOaUserDirectory users, TechnicalDataOaSubmissionLifecycle lifecycle, OaMessageRepository messages,
      OaMessageCodec codec, TechnicalDataAuditLogService audit, TechnicalDataDependencies dependencies) {
    this.tasks = tasks; this.modules = modules; this.submissions = submissions; this.recipients = recipients;
    this.workflow = workflow; this.gateway = gateway; this.users = users; this.lifecycle = lifecycle;
    this.messages = messages; this.codec = codec; this.audit = audit;
    this.dependencies = dependencies;
  }

  @Transactional(readOnly = true)
  public Status status(long taskId, TechnicalDataActor actor) {
    var task = tasks.selectById(taskId);
    if (actor == null || !actor.canReadTask(task, modules.selectByTaskId(taskId))) throw forbidden("无权读取此产品流程");
    var flow = task.getOaFlowId() == null ? null : workflow.findFlow(task.getOaFlowId());
    var people = recipients.current(taskId).stream().map(person -> new Person(person.id(), person.userId(), person.name(),
        person.modules(), person.departmentName(), person.leaderName(), person.todoStatus(), person.submissionRound(),
        person.latestSubmissionId(), person.returnReason(), actor.canEdit() && "OPEN".equals(person.todoStatus())
            && modules.selectByTaskId(taskId).stream().filter(m -> person.modules().contains(m.getModuleType()))
                .allMatch(m -> !Integer.valueOf(0).equals(m.getOaEditAllowed()))
            && "PUBLISHED".equals(task.getExternalTaskStatus()) && (actor.admin() || Objects.equals(actor.userId(), person.userId())),
        canRetry(person, actor, flow))).toList();
    String basis = flow == null ? null : fingerprint(flow.id());
    boolean ready = flow != null && flow.financeReady();
    return new Status(people, ready, ready && Objects.equals(basis, flow.confirmedFingerprint()),
        canFinance(actor, flow), basis, modules.selectByTaskId(taskId).stream()
            .filter(module -> actor.canEditModule(task, module)).map(module -> module.getModuleType()).toList(),
        actor.assignedModules(task, modules.selectByTaskId(taskId)), actor.admin(), actor.canViewSupplementOverview(),
        dependencies.approvedIssues(modules.selectByTaskId(taskId)));
  }

  @Transactional
  public Status confirm(long taskId, String expectedFingerprint, TechnicalDataActor actor) {
    var task = tasks.selectByIdForUpdate(taskId);
    var flow = requireFinance(task, actor);
    if (!workflow.refreshFinance(flow.id())) throw conflict("各部门审批和 OA 财务节点尚未全部确认");
    String current = fingerprint(flow.id());
    if (expectedFingerprint == null || !current.equals(expectedFingerprint)) throw conflict("已批准资料发生变化，请刷新并重新检查");
    // The flow row is locked: retries of the same confirmation keep its original operator and audit.
    if (current.equals(flow.confirmedFingerprint())) return status(taskId, actor);
    workflow.confirmFinance(flow.id(), current, actor.userId());
    // A withdrawn/rejected return can require reconfirming unchanged approved content.
    // The content fingerprint identifies the data, not this new confirmation event.
    String confirmationId = UUID.randomUUID().toString();
    audit.record(task, null, null, "FINANCE_DATA_CONFIRMED", null, current,
        "财务确认当前各人批准资料，可发起核算", actor, "finance:" + confirmationId,
        "TD-FINANCE-CONFIRMED:" + flow.id() + ":" + confirmationId);
    return status(taskId, actor);
  }

  @Transactional
  public Status requestReturn(long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    if (request == null || !request.getUnknownFields().isEmpty() || request.getAssigneeUserId() == null
        || request.getRequestId() == null || !request.getRequestId().matches("[A-Za-z0-9._:-]{1,128}")
        || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 500) throw conflict("请选择退回人员，并填写原因和请求编号");
    var task = tasks.selectByIdForUpdate(taskId);
    var flow = requireFinance(task, actor);
    var person = recipients.current(taskId).stream().filter(row -> row.userId() == request.getAssigneeUserId()).findFirst()
        .orElseThrow(() -> conflict("退回人员不属于当前产品"));
    String requestId = "TD-RETURN:" + taskId + ":" + codec.canonicalHash(request.getRequestId());
    if (person.returnMessageId() != null) {
      var previous = messages.findById(person.returnMessageId());
      if (previous.requestId().equals(requestId) && List.of("PROCESSED", "REJECTED").contains(previous.status())) {
        var original = codec.read(previous.rawPayload()).path("payload");
        if (!original.path("reason").asText().equals(request.getReason())) throw conflict("同一退回编号不能更换原因");
        return status(taskId, actor);
      }
    }
    if ("RETURN_PENDING".equals(person.todoStatus())) {
      var pending = messages.findById(person.returnMessageId());
      if (!pending.requestId().equals(requestId) || !Objects.equals(person.returnReason(), request.getReason())) throw conflict("已有定向退回请求等待 OA 确认");
      if ("FAILED".equals(pending.status())) messages.retryOutgoing(pending.id());
      return status(taskId, actor);
    }
    if (!Objects.equals(task.getTaskVersion(), request.getExpectedTaskVersion())) throw conflict("任务版本已变化，请刷新后退回");
    if (!"DONE".equals(person.todoStatus()) || flow.financeSequence() <= person.callbackSequence()) throw conflict("此人的审批结果尚未进入当前财务节点");
    var submission = submissions.selectById(person.latestSubmissionId());
    var submittedMessage = messages.findById(submission.getOutboundMessageId());
    if (submittedMessage == null || person.integrationTaskId() == null || person.integrationTaskId().isBlank()) {
      throw conflict("原资料提交缺少对外任务或提交编号，请先核实发送记录");
    }
    var peer = gateway.peer();
    if (!peer.sourceSystem().equals(flow.sourceSystem()) || !peer.environment().equals(flow.environment())
        || !peer.businessUnits().contains(task.getBusinessUnitType())) throw forbidden("当前 OA 通道不能办理此单据");
    String raw = codec.write(Map.of("schemaVersion", 1, "sourceSystem", peer.sourceSystem(), "environment", peer.environment(),
        "requestId", requestId, "occurredAt", OffsetDateTime.now().toString(), "payload", Map.ofEntries(
            Map.entry("taskId", person.integrationTaskId()), Map.entry("quoteTaskId", taskId),
            Map.entry("recipientId", person.id()), Map.entry("submissionId", submittedMessage.requestId()),
            Map.entry("quoteSubmissionId", submission.getId()),
            Map.entry("technicalVersionId", submission.getTechnicalVersionId()), Map.entry("round", submission.getSubmissionRound()),
            Map.entry("externalTaskId", person.externalTaskId()), Map.entry("externalFlowId", flow.externalFlowId()),
            Map.entry("documentId", flow.documentId()), Map.entry("assigneeExternalId", person.externalUserId()),
            Map.entry("operatorExternalId", users.externalId(peer, actor.userId())), Map.entry("moduleTypes", person.modules()),
            Map.entry("reason", request.getReason()), Map.entry("technicalDataReady", false))));
    var type = OaMessageCodec.InterfaceType.TECH_RETURN;
    var message = messages.enqueue(peer, type, codec.decode(raw, peer, type));
    if (!"RECEIVED".equals(message.status())) throw conflict("该退回编号已使用，请使用新的操作编号");
    recipients.requestReturn(person.id(), submission.getId(), message.id(), actor.userId(), request.getReason());
    workflow.invalidateFinance(flow.id());
    audit.record(task, null, submission.getId(), "FINANCE_RETURN_REQUESTED", "DONE", "RETURN_PENDING",
        person.name() + "：" + request.getReason(), actor, request.getRequestId(), "TD-FINANCE-RETURN:" + message.id());
    return status(taskId, actor);
  }

  /** 人员和冻结内容不变，只核实原请求；不会生成新的提交轮次或退回请求。 */
  @Transactional
  public Status retry(long taskId, long recipientId, TechnicalDataActor actor) {
    var task = tasks.selectByIdForUpdate(taskId);
    if (task == null || !Integer.valueOf(1).equals(task.getActiveFlag()) || task.getOaFlowId() == null) throw conflict("活动任务不存在");
    var person = recipients.findById(recipientId);
    if (person == null || !person.active() || person.taskId() != taskId) throw forbidden("无权核实此人的请求");
    var flow = workflow.lockFlow(task.getOaFlowId());
    if (!canRetry(person, actor, flow)) throw conflict("当前请求正在处理，或无权重试此人的请求");
    var message = pendingMessage(person);
    messages.retryOutgoing(message.id());
    audit.record(task, null, person.latestSubmissionId(), "PERSON_DELIVERY_RETRY", message.status(), "RECEIVED",
        "核实原请求 " + message.requestId(), actor, message.requestId(), "TD-RETRY:" + message.id() + ":" + message.attemptCount());
    return status(taskId, actor);
  }

  private OaMessageRepository.Message pendingMessage(Recipient person) {
    Long messageId = person.returnMessageId();
    if ("PREPARED".equals(person.todoStatus())) {
      var submission = person.latestSubmissionId() == null ? null : submissions.selectById(person.latestSubmissionId());
      messageId = submission == null ? null : submission.getOutboundMessageId();
    } else if (!"RETURN_PENDING".equals(person.todoStatus())) return null;
    return messageId == null ? null : messages.findById(messageId);
  }

  private boolean canRetry(Recipient person, TechnicalDataActor actor, Flow flow) {
    if (actor == null || !actor.canAccessTask(person.taskId())) return false;
    boolean permitted = "RETURN_PENDING".equals(person.todoStatus()) ? canFinance(actor, flow)
        : actor.canEdit() && (actor.admin() || Objects.equals(actor.userId(), person.userId()));
    var message = permitted ? pendingMessage(person) : null;
    return message != null && "FAILED".equals(message.status());
  }

  /** 只有来自已认证 OA 通道且身份匹配的明确成功回执，才能重新开放此人的模块。 */
  public void acceptReturn(OaMessageRepository.Message message, TechnicalDataOaGateway.Receipt receipt) {
    var expected = codec.read(message.rawPayload()).path("payload");
    var person = recipients.findById(expected.path("recipientId").longValue());
    var task = person == null ? null : tasks.selectByIdForUpdate(person.taskId());
    if (task == null || person == null || !person.active() || !Objects.equals(person.returnMessageId(), message.id())
        || !"RETURN_PENDING".equals(person.todoStatus())) throw conflict("退回回执不是此人的当前请求");
    if (!receipt.accepted()) {
      recipients.state(person.id(), person.latestSubmissionId(), "RETURN_PENDING", "DONE", "OA 未同意退回：" + receipt.errorCode());
      workflow.refreshFinance(task.getOaFlowId());
      return;
    }
    var result = receipt.result();
    for (String field : List.of("taskId", "quoteTaskId", "recipientId", "submissionId", "quoteSubmissionId", "technicalVersionId", "round", "externalFlowId",
        "assigneeExternalId", "operatorExternalId", "moduleTypes", "technicalDataReady", "documentId", "reason")) {
      if (!expected.path(field).equals(result.path(field))) throw new OaDeliveryUnknownException("OA 退回回执身份不一致：" + field);
    }
    if (!result.path("externalTaskId").isTextual() || result.path("externalTaskId").asText().isBlank()) throw new OaDeliveryUnknownException("OA 退回回执缺少当前待办身份");
    var submission = submissions.selectById(person.latestSubmissionId());
    lifecycle.financeReturned(task, submission, person.returnRequestedBy(), person.returnReason());
    recipients.returnedTodo(person.id(), result.path("externalTaskId").asText());
    audit.recordSystem(task, "FINANCE_RETURN_CONFIRMED", "RETURN_PENDING", "OPEN",
        person.name() + "：OA 已确认定向退回，其他人批准资料保留", message.requestId(), "TD-FINANCE-RETURNED:" + message.id());
  }

  private Flow requireFinance(QuoteTechTask task, TechnicalDataActor actor) {
    if (task == null || !Integer.valueOf(1).equals(task.getActiveFlag()) || task.getOaFlowId() == null) throw conflict("产品尚未关联活动 OA 流程");
    oaWorkflowAccess.requireCosting(task.getOaFormId());
    var flow = workflow.lockFlow(task.getOaFlowId());
    if (!canFinance(actor, flow) || flow.financeSequence() == 0) throw forbidden("仅当前 OA 财务核算节点的报价员或管理员可操作");
    return flow;
  }

  private boolean canFinance(TechnicalDataActor actor, Flow flow) {
    return actor != null && !actor.shortSession() && flow != null
        && (actor.admin() || actor.has("ingest:quote:cost-run:execute") && Objects.equals(actor.userId(), flow.financeUserId()));
  }

  private String fingerprint(long flowId) { return codec.canonicalHash(workflow.approvalBasis(flowId)); }
  private TechnicalDataTaskException conflict(String text) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, text); }
  private TechnicalDataTaskException forbidden(String text) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, text); }
}
