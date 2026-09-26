package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 一张原 OA 单据的一次分派：本地准备、单次原生 I02、确认后开放填写。 */
@Service
public class TechnicalDataOaDispatchService {

  private final OaTechnicalBatchRepository batches;
  private final OaTechnicalBatchDelivery delivery;
  private final TechnicalDataOaContext context;
  private final OaTechnicalDispatchRequestBuilder builder;
  private final TechnicalDataAssigneeResolver assignees;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final TechnicalDataSharedModules sharedModules;
  private final TechnicalDataPriceApplicationService prices;
  private final TransactionTemplate transaction;
  private final TechnicalDataTaskRepository products;

  public TechnicalDataOaDispatchService(
    OaTechnicalBatchRepository batches,
    OaTechnicalBatchDelivery delivery,
    TechnicalDataOaContext context,
    OaTechnicalDispatchRequestBuilder builder,
    TechnicalDataAssigneeResolver assignees,
    TechnicalDataOaRecipientRepository recipients,
    TechnicalDataOaWorkflowRepository workflow,
    QuoteTechTaskMapper tasks,
    QuoteTechModuleMapper modules,
    TechnicalDataSharedModules sharedModules,
    TechnicalDataPriceApplicationService prices,
    PlatformTransactionManager manager,
    TechnicalDataTaskRepository products
  ) {
    this.batches = batches;
    this.delivery = delivery;
    this.context = context;
    this.builder = builder;
    this.assignees = assignees;
    this.recipients = recipients;
    this.workflow = workflow;
    this.tasks = tasks;
    this.modules = modules;
    this.products = products;
    this.sharedModules = sharedModules;
    this.prices = prices;
    this.transaction = new TransactionTemplate(manager);
  }

  public OaTechnicalBatchRepository.Batch replay(String key, String fingerprint, TechnicalDataActor actor) {
    context.lockActor(actor);
    var previous = batches.findRequest("I02", actor.userId(), key);
    if (previous != null && !previous.inputFingerprint().equals(fingerprint)) throw conflict(
      "同一分派编号不能更换产品或人员"
    );
    return previous;
  }

  public String prepare(
    List<QuoteTechTask> selected,
    long defaultUser,
    Map<String, Long> overrides,
    String key,
    String fingerprint,
    TechnicalDataActor actor
  ) {
    if (selected.isEmpty()) throw conflict("请选择待分派产品");
    long formId = selected.getFirst().getOaFormId();
    if (selected.stream().anyMatch(task -> task.getOaFormId() != formId)) throw conflict(
      "请按同一张 OA 单据分派产品"
    );
    var document = context.document(formId);
    String operator = context.operatorEmployeeNo(actor.userId());
    String url = context.workbenchUrl(formId);
    String batchId = UUID.randomUUID().toString();
    batches.insert(batchId, "I02", formId, actor.userId(), key, fingerprint);
    List<OaTechnicalDispatchRequest.Product> productCommands = new ArrayList<>();
    Map<String, Long> changes = overrides == null ? Map.of() : overrides;
    if (
      !TechnicalDataModuleType.codes().containsAll(changes.keySet()) ||
      changes
        .values()
        .stream()
        .anyMatch(id -> id == null || id <= 0)
    ) throw conflict("模块分工无效");
    for (var task : selected) {
      int previous = Objects.requireNonNullElse(task.getOaAssignmentVersion(), 0);
      var last = recipients.find(task.getId(), previous);
      if (!recipients.current(task.getId()).isEmpty()) throw conflict(
        "此产品已分派，请在原任务查看进度；调整分工需另行办理"
      );
      if (last.stream().anyMatch(row -> !"REJECTED".equals(row.dispatchStatus()))) throw conflict(
        "上次分派尚未确认，请核实原请求"
      );
      Map<Long, List<String>> groups = new TreeMap<>();
      for (var module : modules.selectByTaskId(task.getId())) {
        if (!Integer.valueOf(1).equals(module.getRequiredFlag())) continue;
        if ("PRICE".equals(module.getModuleType())) prices.claimForDispatch(module.getProductId());
        else sharedModules.requireOwnership(module.getProductId(), module.getModuleType());
        groups
          .computeIfAbsent(changes.getOrDefault(module.getModuleType(), defaultUser), ignored ->
            new ArrayList<>()
          )
          .add(module.getModuleType());
      }
      if (groups.isEmpty()) throw conflict("产品没有待分派模块");
      List<OaTechnicalDispatchRequest.Assignment> assignments = new ArrayList<>();
      Long firstMessage = null;
      int version = previous + 1;
      for (var group : groups.entrySet()) {
        var user = assignees.resolve(group.getKey());
        String name =
          user.getNickName() == null || user.getNickName().isBlank()
            ? user.getUserName()
            : user.getNickName();
        String employee = context.technicianEmployeeNo(group.getKey());
        group.getValue().sort(Comparator.comparingInt(TechnicalDataModuleType::orderOf));
        assignments.add(new OaTechnicalDispatchRequest.Assignment(employee, name, group.getValue()));
        long message = context.linkMessage(
          document,
          batchId,
          OaMessageCodec.InterfaceType.TASK_DISPATCH,
          task.getId() + ":" + user.getUserId(),
          Map.of("taskId", task.getId(), "assigneeUserId", user.getUserId())
        );
        recipients.insert(
          task.getId(),
          version,
          user.getUserId(),
          name,
          employee,
          "ASSIGN",
          group.getValue(),
          message,
          "T-" + UUID.randomUUID()
        );
        if (firstMessage == null) firstMessage = message;
      }
      var flow = workflow.bindFlow(task, document.peer());
      workflow.bindDispatch(task, flow, version, Objects.requireNonNull(firstMessage));
      var product = products.findProducts(task.getId()).getFirst();
      // 产品料号来自任务源记录，模块负责人来自本次已校验的分工。
      productCommands.add(
        new OaTechnicalDispatchRequest.Product(
          task.getOaFormItemId().toString(),
          product.getMaterialNo(),
          assignments
        )
      );
    }
    var body = builder.build(
      new OaTechnicalDispatchRequest(document.requestId(), document.processCode(), null, productCommands),
      operator
    );
    body.put("remark", body.path("remark").asText() + "\n补录地址：" + url);
    OaWorkflowClient.validateRemark(body.path("remark").asText());
    batches.prepared(batchId, body);
    return batchId;
  }

  public List<Long> taskIds(String batchId) {
    return batches
      .messageIds(batchId)
      .stream()
      .map(recipients::findByMessage)
      .map(row -> row.taskId())
      .distinct()
      .sorted()
      .toList();
  }

  public OaTechnicalBatchRepository.Batch deliver(String batchId) {
    delivery.send(batchId);
    return transaction.execute(status -> {
      var batch = batches.find(batchId, true);
      if (batch.result() == null || "SUCCESS".equals(batch.status())) return batch;
      var rows = batches.messageIds(batchId).stream().map(recipients::findByMessage).toList();
      boolean accepted = "OA_ACCEPTED".equals(batch.status());
      for (var row : rows) {
        if (accepted) recipients.confirm(row.messageId(), null, null, null, null);
        else recipients.unconfirmed(
          row.messageId(),
          Set.of("REJECTED", "NOT_SENT").contains(batch.status()),
          batch.result().message()
        );
      }
      for (long taskId : rows
        .stream()
        .map(row -> row.taskId())
        .distinct()
        .sorted()
        .toList()) {
        var task = tasks.selectByIdForUpdate(taskId);
        if (accepted) {
          workflow.bindExternalFlow(task, batch.request().path("requestId").asText());
          recipients.activate(taskId, task.getOaAssignmentVersion());
          recipients.refreshTask(taskId);
        } else if (
          !Set.of("SYNC_FAILED", "UNKNOWN").contains(Objects.toString(task.getExternalTaskStatus(), ""))
        ) {
          workflow.dispatchUnconfirmed(
            taskId,
            Set.of("REJECTED", "NOT_SENT").contains(batch.status()),
            batch.result().message()
          );
        }
      }
      batches.finishMessages(batch);
      if (accepted) batches.completed(batchId);
      return batches.find(batchId, false);
    });
  }

  private IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }
}
