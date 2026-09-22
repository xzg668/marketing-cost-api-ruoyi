package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.technicaldata.OaDeliveryUnknownException;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本地事务只保存分工提案和待发送报文；全部 OA 待办确认后才切换当前办理人。 */
@Service
public class TechnicalDataOaIntegrationServiceImpl implements TechnicalDataOaIntegrationService {
  private final TechnicalDataSharedModules sharedModules;
  private final TechnicalDataPriceApplicationService prices;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaUserDirectory users;
  private final TechnicalDataAssigneeResolver assignees;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaRecipientRepository recipients;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final TechnicalDataAuditLogService audit;

  public TechnicalDataOaIntegrationServiceImpl(TechnicalDataOaGateway gateway, TechnicalDataOaUserDirectory users,
      OaMessageRepository messages, OaMessageCodec codec, TechnicalDataOaWorkflowRepository workflow,
      QuoteTechTaskMapper tasks, TechnicalDataAuditLogService audit, TechnicalDataAssigneeResolver assignees,
      TechnicalDataOaRecipientRepository recipients, QuoteTechModuleMapper modules, TechnicalDataSharedModules sharedModules, TechnicalDataPriceApplicationService prices) {
    this.sharedModules = sharedModules;
    this.prices = prices;
    this.gateway = gateway; this.users = users; this.messages = messages; this.codec = codec;
    this.workflow = workflow; this.tasks = tasks; this.audit = audit; this.assignees = assignees;
    this.recipients = recipients; this.modules = modules;
  }

  @Override public boolean enabled() { return gateway.enabled(); }

  @Override @Transactional
  public void queueDispatch(QuoteTechTask task, Long defaultAssigneeId, Map<String, Long> moduleAssignees, TechnicalDataActor actor) {
    requirePublisher(actor);
    queue(task, defaultAssigneeId, moduleAssignees, "ASSIGN", actor);
  }

  @Override @Transactional
  public void queueCancellation(QuoteTechTask task, TechnicalDataActor actor) {
    requirePublisher(actor);
    var current = recipients.current(task.getId());
    Map<String, Long> scope = new LinkedHashMap<>();
    current.forEach(row -> row.modules().forEach(type -> scope.put(type, row.userId())));
    queue(task, task.getAssigneeUserId(), scope, "CANCEL", actor);
  }

  /** 仅同步系统复查所得的缺口；沿用已授权分工，不接受外部传入人员。 */
  @Override @Transactional
  public void queueSourceRefresh(QuoteTechTask task) {
    Map<String, Long> scope = new LinkedHashMap<>();
    var required = modules.selectByTaskId(task.getId()).stream()
        .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())).toList();
    required.stream().filter(module -> module.getAssigneeUserId() != null)
        .forEach(module -> scope.put(module.getModuleType(), module.getAssigneeUserId()));
    queue(task, task.getAssigneeUserId(), scope, required.isEmpty() ? "CANCEL" : "ASSIGN", null);
  }

  private void requirePublisher(TechnicalDataActor actor) {
    if (actor == null || !actor.canPublish() || actor.shortSession()) throw forbidden("当前用户不能分派 OA 待办");
  }

  private void queue(QuoteTechTask task, Long defaultAssigneeId, Map<String, Long> overrides,
      String action, TechnicalDataActor actor) {
    if (!Integer.valueOf(1).equals(task.getActiveFlag())) throw conflict("历史任务不能调整分工");
    Map<String, Long> changes = overrides == null ? Map.of() : overrides;
    if (!TechnicalDataModuleType.codes().containsAll(changes.keySet())
        || changes.values().stream().anyMatch(id -> id == null || id <= 0)
        || defaultAssigneeId == null || defaultAssigneeId <= 0) throw conflict("模块类型或办理人无效");

    Map<Long, List<String>> groups = new TreeMap<>();
    if ("ASSIGN".equals(action)) {
      for (var module : modules.selectByTaskId(task.getId())) {
        if (Integer.valueOf(1).equals(module.getRequiredFlag())) {
          if ("PRICE".equals(module.getModuleType())) prices.claimForDispatch(module.getProductId());
          else sharedModules.requireOwnership(module.getProductId(), module.getModuleType());
          groups.computeIfAbsent(changes.getOrDefault(module.getModuleType(), defaultAssigneeId), id -> new ArrayList<>())
              .add(module.getModuleType());
        }
      }
      if (groups.isEmpty()) throw conflict("此产品没有可分派的补录模块");
    }
    groups.values().forEach(list -> list.sort(java.util.Comparator.comparingInt(TechnicalDataModuleType::orderOf)));
    Map<Long, String> names = new TreeMap<>();
    for (Long id : groups.keySet()) {
      var user = assignees.resolve(id);
      names.put(id, user.getNickName() == null || user.getNickName().isBlank() ? user.getUserName() : user.getNickName());
    }
    if (!enabled()) {
      if (task.getOaFlowId() != null || !"ASSIGN".equals(action)) throw conflict("已有 OA 待办，恢复 OA 通道后才能调整分工");
      if (modules.selectByTaskId(task.getId()).stream().anyMatch(module -> java.util.Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(java.util.Objects.toString(module.getModuleStatus(), "")))) {
        throw conflict("已提交模块不能直接调整分工");
      }
      boolean assignmentChanged = modules.selectByTaskId(task.getId()).stream()
          .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag()))
          .anyMatch(module -> !Objects.equals(module.getAssigneeUserId(), changes.getOrDefault(module.getModuleType(), defaultAssigneeId)));
      if (!assignmentChanged) return;
      recipients.assignModules(task.getId(), groups, names);
      if (tasks.recordLocalAssignment(task.getId(), task.getTaskVersion(), actor == null ? null : actor.userId()) != 1) {
        throw conflict("本地分工版本已变化，请重新检查");
      }
      return;
    }
    var peer = gateway.peer();
    if (!peer.businessUnits().contains(task.getBusinessUnitType())) throw forbidden("当前 OA 通道无权办理此业务单元");
    int previousVersion = task.getOaAssignmentVersion() == null ? 0 : task.getOaAssignmentVersion();
    var previousBatch = recipients.find(task.getId(), previousVersion);
    if (previousBatch.stream().anyMatch(row -> !Set.of("PROCESSED", "REJECTED")
        .contains(messages.findById(row.messageId()).status()))) throw conflict("上次分派尚未确认，请先核实原请求");

    Map<Long, List<String>> changed = new TreeMap<>(groups);
    var current = recipients.current(task.getId());
    for (var row : current) {
      if (Objects.equals(row.modules(), groups.get(row.userId()))) {
        changed.remove(row.userId());
      } else {
        if (!"OPEN".equals(row.todoStatus())) throw conflict(row.name() + "的模块尚在审批或已通过，请先在 OA 定向退回后调整分工");
        if (!groups.containsKey(row.userId())) changed.put(row.userId(), List.of());
        names.putIfAbsent(row.userId(), row.name());
      }
    }
    if (changed.isEmpty()) return;
    var flow = workflow.bindFlow(task, peer);
    int version = previousVersion + 1;
    List<Map<String, Object>> recipientPayloads = new ArrayList<>();
    for (var group : groups.entrySet()) recipientPayloads.add(Map.of(
        "assigneeExternalId", users.externalId(peer, group.getKey()), "moduleTypes", group.getValue()));
    Long firstMessage = null;
    for (var group : changed.entrySet()) {
      Long userId = group.getKey();
      String externalId = users.externalId(peer, userId);
      String personAction = group.getValue().isEmpty() ? "CANCEL" : "ASSIGN";
      String requestId = "TD-DISPATCH:" + task.getId() + ":" + version + ":" + userId;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("taskId", task.getId()); payload.put("oaFormItemId", task.getOaFormItemId());
      payload.put("documentId", flow.documentId()); payload.put("accountingMonth", task.getAccountingMonth());
      payload.put("assigneeExternalId", externalId); payload.put("moduleTypes", group.getValue());
      payload.put("assignmentVersion", version); payload.put("action", personAction);
      payload.put("recipients", recipientPayloads); payload.put("accessUrl", gateway.taskAccessUrl(task.getId()));
      payload.put("taskNo", task.getTaskNo()); payload.put("oaNo", task.getOaNo());
      if (task.getDueAt() != null) payload.put("dueAt", task.getDueAt().toString());
      String raw = codec.write(Map.of("schemaVersion", 1, "sourceSystem", peer.sourceSystem(), "environment", peer.environment(),
          "requestId", requestId, "occurredAt", OffsetDateTime.now().toString(), "payload", payload));
      var type = OaMessageCodec.InterfaceType.TASK_DISPATCH;
      var message = messages.enqueue(peer, type, codec.decode(raw, peer, type));
      recipients.insert(task.getId(), version, userId, names.get(userId), externalId, personAction, group.getValue(), message.id());
      if (firstMessage == null) firstMessage = message.id();
    }
    workflow.bindDispatch(task, flow, version, Objects.requireNonNull(firstMessage));
    String reason = action + "：" + changed.size() + " 人的分工变更等待 OA 确认";
    String requestId = "TD-DISPATCH:" + task.getId() + ":" + version;
    if (actor == null) audit.recordSystem(task, "SOURCE_RECHECK_DISPATCH", task.getExternalTaskStatus(), "SYNC_PENDING",
        reason, requestId, "TD-DISPATCH-QUEUED:" + firstMessage);
    else audit.record(task, null, null, "OA_DISPATCH_QUEUED", task.getExternalTaskStatus(), "SYNC_PENDING",
        reason, actor, requestId, "TD-DISPATCH-QUEUED:" + firstMessage);
  }

  @Override @Transactional
  public void retry(Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    if (actor == null || !actor.admin() || actor.shortSession()) throw forbidden("仅管理员可核实未确认的 OA 分派");
    if (request == null || !request.getUnknownFields().isEmpty() || request.getReason() == null || request.getReason().isBlank()
        || request.getReason().length() > 500 || request.getRequestId() == null || !request.getRequestId().matches("[A-Za-z0-9._:-]{1,128}")) {
      throw conflict("请提供重试编号和原因");
    }
    var task = tasks.selectByIdForUpdate(taskId);
    if (task == null || !Objects.equals(task.getTaskVersion(), request.getExpectedTaskVersion())) throw conflict("任务不存在或版本已变化");
    var current = recipients.find(taskId, task.getOaAssignmentVersion() == null ? 0 : task.getOaAssignmentVersion());
    if (current.isEmpty()) { queueDispatch(task, task.getAssigneeUserId(), Map.of(), actor); return; }
    boolean retried = false;
    for (var row : current) {
      if ("FAILED".equals(messages.findById(row.messageId()).status())) { messages.retryOutgoing(row.messageId()); retried = true; }
    }
    if (!retried && current.stream().anyMatch(row -> "REJECTED".equals(row.dispatchStatus()))) {
      Map<String, Long> scope = new LinkedHashMap<>();
      recipients.current(taskId).forEach(row -> row.modules().forEach(type -> scope.put(type, row.userId())));
      current.forEach(row -> row.modules().forEach(type -> scope.put(type, row.userId())));
      queue(task, task.getAssigneeUserId(), scope, current.stream().allMatch(row -> "CANCEL".equals(row.action())) ? "CANCEL" : "ASSIGN", actor);
      retried = true;
    }
    if (!retried) throw conflict("待办正在处理或已确认，无需重试");
    audit.record(task, null, null, "OA_DISPATCH_RECHECK", task.getExternalTaskStatus(), "SYNC_PENDING",
        request.getReason(), actor, request.getRequestId(), "TD-DISPATCH-RECHECK:" + taskId + ":" + request.getRequestId());
  }

  @Override public void markUnconfirmed(OaMessageRepository.Message message, boolean rejected, String reason) {
    var row = recipients.findByMessage(message.id());
    if (row == null) throw conflict("分派消息缺少人员关联");
    var task = tasks.selectByIdForUpdate(row.taskId());
    recipients.unconfirmed(message.id(), rejected, reason);
    if (task != null && Objects.equals(task.getOaAssignmentVersion(), row.assignmentVersion())) {
      boolean anyRejected = recipients.find(row.taskId(), row.assignmentVersion()).stream().anyMatch(r -> "REJECTED".equals(r.dispatchStatus()));
      workflow.dispatchUnconfirmed(row.taskId(), anyRejected, reason);
    }
  }

  @Override public void acceptReceipt(OaMessageRepository.Message message, TechnicalDataOaGateway.Receipt receipt) {
    var row = recipients.findByMessage(message.id());
    if (row == null) throw conflict("分派回执缺少人员关联");
    var task = tasks.selectByIdForUpdate(row.taskId());
    if (task == null) throw conflict("分派任务不存在");
    if (!receipt.accepted()) { markUnconfirmed(message, true, receipt.errorCode()); return; }
    var command = codec.read(message.rawPayload()).path("payload");
    var result = receipt.result();
    for (String field : List.of("taskId", "assignmentVersion", "assigneeExternalId", "documentId", "accountingMonth", "moduleTypes", "action")) {
      if (!command.path(field).equals(result.path(field))) throw new OaDeliveryUnknownException("OA 分派回执身份或模块不匹配：" + field);
    }
    if (!result.path("externalTaskId").isTextual() || result.path("externalTaskId").asText().isBlank()
        || !result.path("externalFlowId").isTextual() || result.path("externalFlowId").asText().isBlank()) {
      throw new OaDeliveryUnknownException("OA 分派回执缺少待办或流程身份");
    }
    if ("ASSIGN".equals(row.action())) {
      for (String field : List.of("departmentName", "leaderExternalId", "leaderName")) {
        if (!result.path(field).isTextual() || result.path(field).asText().isBlank()) {
          throw new OaDeliveryUnknownException("OA 分派回执缺少部门审批人：" + field);
        }
      }
      if (row.externalUserId().equals(result.path("leaderExternalId").asText())) throw new OaDeliveryUnknownException("补录人不能审批自己的提交");
    }
    recipients.confirm(message.id(), result.path("externalTaskId").asText(), result.path("departmentName").asText(null),
        result.path("leaderExternalId").asText(null), result.path("leaderName").asText(null));
    if (!Objects.equals(task.getOaAssignmentVersion(), row.assignmentVersion())) return;
    workflow.bindExternalFlow(task, result.path("externalFlowId").asText());
    boolean complete = recipients.find(task.getId(), row.assignmentVersion()).stream().allMatch(r -> "CONFIRMED".equals(r.dispatchStatus()));
    if (complete) {
      recipients.activate(task.getId(), row.assignmentVersion());
      recipients.refreshTask(task.getId());
    }
    audit.recordSystem(task, "OA_DISPATCH_CONFIRMED", task.getExternalTaskStatus(), complete ? "PUBLISHED" : "SYNC_PENDING",
        "OA 已确认人员待办：" + row.userId(), message.requestId(), "TD-DISPATCH-CONFIRMED:" + message.id());
  }

  private OaIntegrationException conflict(String message) { return OaIntegrationException.conflict("OA_DISPATCH_CONFLICT", message); }
  private TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
}
