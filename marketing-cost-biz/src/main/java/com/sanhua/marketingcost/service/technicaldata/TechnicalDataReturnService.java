package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 一张 OA 单的一次定向退回：关联原负责人，两步 OA 调用都确认成功后恢复所选板块编辑。 */
@Service
public class TechnicalDataReturnService {
  public record Target(long taskId, int expectedTaskVersion, List<String> modules, String reason) {
    @JsonAnySetter
    public void rejectUnknown(String key, JsonNode value) {
      throw invalid("未定义字段：" + key);
    }
  }

  public record Request(String requestKey, List<Target> targets) {
    @JsonAnySetter
    public void rejectUnknown(String key, JsonNode value) {
      throw invalid("未定义字段：" + key);
    }
  }

  public record Module(String moduleType, String label, String assigneeName, String employeeNo) {}

  public record Candidate(
      long taskId,
      long formId,
      String oaNo,
      String productNo,
      int taskVersion,
      List<Module> modules,
      Result lastReturn,
      String returnReason) {}

  public record Result(
      String batchId, String requestKey, String status, OaWorkflowResult oaResult,
      String step, int attempt, boolean canRetry, long actorId) {}

  private final JdbcTemplate jdbc;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final QuoteTechSubmissionMapper submissions;
  private final QuoteTechnicalDataRepository products;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaSubmissionLifecycle lifecycle;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaContext context;
  private final OaWorkflowAccessPolicy access;
  private final OaTechnicalReturnClient client;
  private final OaTechnicalBatchRepository batches;
  private final OaTechnicalReturnDelivery delivery;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataAuditLogService audit;
  private final TransactionTemplate transaction;

  public TechnicalDataReturnService(
      JdbcTemplate jdbc,
      QuoteTechTaskMapper tasks,
      QuoteTechModuleMapper modules,
      QuoteTechSubmissionMapper submissions,
      QuoteTechnicalDataRepository products,
      TechnicalDataOaRecipientRepository recipients,
      TechnicalDataOaSubmissionLifecycle lifecycle,
      TechnicalDataOaWorkflowRepository workflow,
      TechnicalDataOaContext context,
      OaWorkflowAccessPolicy access,
      OaTechnicalReturnClient client,
      OaTechnicalBatchRepository batches,
      OaTechnicalReturnDelivery delivery,
      OaMessageRepository messages,
      OaMessageCodec codec,
      TechnicalDataAuditLogService audit,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.modules = modules;
    this.submissions = submissions;
    this.products = products;
    this.recipients = recipients;
    this.lifecycle = lifecycle;
    this.workflow = workflow;
    this.context = context;
    this.access = access;
    this.client = client;
    this.batches = batches;
    this.delivery = delivery;
    this.messages = messages;
    this.codec = codec;
    this.audit = audit;
    this.transaction = new TransactionTemplate(manager);
  }

  @Transactional(readOnly = true)
  public List<Candidate> candidates(List<Long> ids, TechnicalDataActor actor) {
    if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull) || ids.size() > 100)
      throw invalid("请选择 1 至 100 个产品");
    List<Candidate> result = new ArrayList<>();
    Long formId = null;
    for (long id : new TreeSet<>(ids)) {
      var task = requireTask(id, actor, false);
      if (formId != null && !formId.equals(task.getOaFormId())) throw invalid("一次只能退回同一张 OA 单的资料");
      formId = task.getOaFormId();
      var view = access.view(formId);
      boolean allowed =
          view != null
              && view.canCost()
              && Set.of("COSTING", "RECOSTING", "TECHNICAL").contains(view.state());
      List<Module> choices = new ArrayList<>();
      String returnReason = null;
      var currentPeople = recipients.current(id);
      boolean pending = currentPeople.stream().anyMatch(person -> "RETURN_PENDING".equals(person.todoStatus()));
      for (var person : currentPeople) {
        Set<String> pendingModules = new HashSet<>();
        if (pending) {
          if (!"RETURN_PENDING".equals(person.todoStatus()) || person.returnMessageId() == null) continue;
          var scope = payload(person.returnMessageId());
          scope.path("moduleTypes").forEach(type -> pendingModules.add(type.asText()));
          returnReason = scope.path("reason").asText();
        } else if (!allowed || !submitted(person)) continue;
        for (var module : modules.selectByTaskId(id)) {
          if (Objects.equals(module.getAssigneeUserId(), person.userId())
              && (pending ? pendingModules.contains(module.getModuleType()) : TechnicalDataSubmissionState.submitted(module.getModuleStatus()))) {
            choices.add(
                new Module(
                    module.getModuleType(),
                    TechnicalDataModuleType.valueOf(module.getModuleType()).displayName(),
                    person.name(),
                    person.externalUserId()));
          }
        }
      }
      var product =
          products
              .findActiveProduct(task.getOaFormItemId(), task.getAccountingMonth())
              .orElseThrow();
      var last =
          jdbc.queryForList(
              """
SELECT m.technical_batch_id FROM lp_quote_tech_oa_recipient r
JOIN lp_oa_integration_message m ON m.id=r.return_message_id
WHERE r.task_id=? AND r.active_flag=1 AND m.technical_batch_id IS NOT NULL ORDER BY m.id DESC LIMIT 1
""",
              String.class,
              id);
      result.add(
          new Candidate(
              id,
              formId,
              task.getOaNo(),
              product.getMaterialNo(),
              task.getTaskVersion(),
              choices,
              last.isEmpty() ? null : result(batches.find(last.getFirst(), false)),
              returnReason));
    }
    return result;
  }

  public Result submit(Request request, TechnicalDataActor actor) {
    validate(request);
    String id = Objects.requireNonNull(transaction.execute(status -> prepare(request, actor)));
    delivery.send(id, this::validateBeforeSending);
    return Objects.requireNonNull(transaction.execute(status -> complete(id)));
  }

  public Result status(String id, TechnicalDataActor actor) {
    return transaction.execute(
        status -> {
          var batch = batches.find(id, false);
          if (batch == null || !"I05".equals(batch.operation())) throw invalid("退回记录不存在");
          var linked = batches.messageIds(id);
          if (linked.isEmpty()) throw invalid("退回记录缺少任务范围");
          for (long message : linked)
            requireTask(payload(message).path("taskId").asLong(), actor, false);
          return complete(id);
        });
  }

  public Result retryRejection(String id, int expectedAttempt, TechnicalDataActor actor) {
    if (expectedAttempt < 1) throw invalid("退回重试次数无效");
    transaction.executeWithoutResult(tx -> {
      var batch = batches.find(id, false);
      if (batch == null || !"I05".equals(batch.operation()) || batch.actorId() != actor.userId())
        throw invalid("只能由原报价员继续本次退回");
      for (long message : batches.messageIds(id)) requireTask(payload(message).path("taskId").asLong(), actor, false);
    });
    delivery.retryRejection(id, expectedAttempt, this::validateBeforeSending);
    return Objects.requireNonNull(transaction.execute(tx -> complete(id)));
  }

  /** OA 没有人员修改结果查询接口；只有原报价员实际核实成功后，才能续办第二步。 */
  public Result confirmPeople(String id, int expectedAttempt, String note, TechnicalDataActor actor) {
    if (expectedAttempt < 1 || note == null || note.isBlank() || note.trim().length() > 500)
      throw invalid("请填写核实 OA 人员更新成功的依据（1—500字）");
    boolean confirmed = Boolean.TRUE.equals(transaction.execute(tx -> {
      var original = batches.find(id, false);
      if (original == null || !"I05".equals(original.operation()) || actor == null || original.actorId() != actor.userId())
        throw invalid("只能由原报价员核实本次人员更新结果");
      jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, original.formId());
      var batch = batches.find(id, true);
      var linked = batches.messageIds(id);
      if (linked.isEmpty()) throw invalid("退回记录缺少任务范围");
      for (long message : linked) requireTask(payload(message).path("taskId").asLong(), actor, false);
      var previousConfirmation = batch.peopleResult() == null || batch.peopleResult().response() == null
          ? null : batch.peopleResult().response().get("manualConfirmation");
      if (previousConfirmation != null && previousConfirmation.path("attempt").asInt() == expectedAttempt) return false;
      if (!"UNKNOWN".equals(batch.status()) || !"PEOPLE".equals(batch.returnStep()) || batch.returnAttempt() != expectedAttempt)
        throw invalid("当前并非本次人员更新结果未知，请刷新退回结果");
      validateBeforeSending(batch);
      var evidence = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      evidence.set("originalResult", codec.read(codec.write(batch.peopleResult())));
      evidence.putObject("manualConfirmation").put("actorId", actor.userId()).put("actorName", actor.name())
          .put("attempt", expectedAttempt).put("note", note.trim())
          .put("confirmedAt", java.time.Instant.now().toString());
      var result = new OaWorkflowResult("MANUAL:" + id, OaWorkflowResult.Status.SUCCESS, null,
          "MANUAL_CONFIRMED", "原报价员核实 OA 人员更新成功", batch.request().path("requestId").asText(), evidence, 0);
      batches.confirmPeopleManually(batch, result);
      for (long message : linked) {
        var scope = payload(message);
        var task = requireTask(scope.path("taskId").asLong(), actor, false);
        audit.record(task, products.lockActiveProducts(task.getId()).getFirst(), scope.path("submissionId").asLong(),
            "RETURN_PEOPLE_MANUALLY_CONFIRMED", "UNKNOWN", "REJECT_READY", note.trim(), actor, id,
            "I05-CONFIRM-PEOPLE:" + message);
      }
      return true;
    }));
    if (confirmed) delivery.retryRejection(id, expectedAttempt, this::validateBeforeSending);
    return Objects.requireNonNull(transaction.execute(tx -> complete(id)));
  }

  /** OA技术节点通知只确认已发送但回执未知的第二步，并沿用原退回产品/板块范围。 */
  @Transactional
  public void confirmFromNotification(String id, OaWorkflowNotification event, long notificationId) {
    var original = batches.find(id, false);
    if (original == null) throw invalid("退回记录不存在");
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, original.formId());
    var batch = batches.find(id, true);
    if (!event.technical() || !"I05".equals(batch.operation())
        || !event.requestId().equals(batch.request().path("requestId").asText())
        || !"UNKNOWN".equals(batch.status()) || !"REJECT".equals(batch.returnStep())
        || batch.peopleResult() == null || batch.peopleResult().status() != OaWorkflowResult.Status.SUCCESS) {
      throw invalid("退回人员尚未确认成功，或节点退回并非结果未知，不能用此通知开放编辑");
    }
    Set<String> expectedEmployees = new HashSet<>();
    for (long message : batches.messageIds(id)) {
      var scope = payload(message);
      var person = recipients.findById(scope.path("recipientId").asLong());
      if (person == null || !person.active() || !"RETURN_PENDING".equals(person.todoStatus())
          || !Objects.equals(person.returnMessageId(), message)
          || !Objects.equals(person.latestSubmissionId(), scope.path("submissionId").asLong())) {
        throw invalid("原退回任务已变化，不能用通知确认其他轮次");
      }
      expectedEmployees.add(person.externalUserId());
    }
    if (expectedEmployees.isEmpty() || !new HashSet<>(event.employeeNos()).containsAll(expectedEmployees)) {
      throw invalid("通知未覆盖本次退回的全部技术员，不能确认整批退回");
    }
    batches.confirmReturnNotification(batch, notificationId);
    complete(id, true);
  }

  private String prepare(Request request, TechnicalDataActor actor) {
    context.lockActor(actor);
    String fingerprint =
        codec.canonicalHash(
            request.targets().stream()
                .sorted(Comparator.comparingLong(Target::taskId))
                .map(
                    target ->
                        new Target(
                            target.taskId(),
                            target.expectedTaskVersion(),
                            target.modules().stream().sorted().toList(),
                            target.reason().trim()))
                .toList());
    var previous = batches.findRequest("I05", actor.userId(), request.requestKey());
    if (previous != null) {
      if (!previous.inputFingerprint().equals(fingerprint)) throw invalid("同一退回编号不能更换产品、板块或原因");
      for (var target : request.targets()) requireTask(target.taskId(), actor, false);
      return previous.id();
    }
    var first = requireTask(request.targets().getFirst().taskId(), actor, false);
    var document = context.document(first.getOaFormId());
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, document.formId());
    if (Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I05'
          AND status IN ('PREPARED','SENDING','REJECT_READY','RETURN_FAILED','OA_ACCEPTED','UNKNOWN'))
        """, Boolean.class, document.formId()))) {
      throw invalid("本单已有退回等待 OA 完成，请先处理原退回");
    }
    var node = access.quoterNode(document.formId());
    if (node.actorId() != actor.userId()) throw invalid("当前账号与 OA 报价员身份不一致");
    var rejection = client.previewRejection(document.requestId(), node.employeeNo(), document.rejectToNodeId());
    String id = UUID.randomUUID().toString();
    batches.insert(id, "I05", document.formId(), actor.userId(), request.requestKey(), fingerprint);
    List<OaTechnicalReturnClient.Target> commands = new ArrayList<>();
    for (var target :
        request.targets().stream().sorted(Comparator.comparingLong(Target::taskId)).toList()) {
      commands.addAll(
          prepareProductReturn(target, id, request.requestKey(), document, node, actor));
    }
    batches.prepareReturn(
        id,
        client.preview(
            new OaTechnicalReturnClient.Request(
                document.requestId(),
                document.processCode(),
                node.employeeNo(),
                commands,
                context.workbenchUrl(document.formId()))), rejection);
    return id;
  }

  /** 一个产品可选多个板块；按原负责人归组后保存各自的退回范围。 */
  private List<OaTechnicalReturnClient.Target> prepareProductReturn(
      Target target,
      String batchId,
      String requestKey,
      TechnicalDataOaContext.Document document,
      OaWorkflowAccessPolicy.QuoterNode node,
      TechnicalDataActor actor) {
    List<OaTechnicalReturnClient.Target> commands = new ArrayList<>();
    var task = requireTask(target.taskId(), actor, true);
    if (!Objects.equals(task.getOaFormId(), document.formId())) throw invalid("一次只能退回同一张 OA 单的资料");
    if (!Objects.equals(task.getTaskVersion(), target.expectedTaskVersion()))
      throw invalid("产品任务已变化，请刷新后再退回");
    var all = modules.selectByTaskId(task.getId());
    Map<Long, List<String>> groups = new TreeMap<>();
    for (String code : target.modules()) {
      var module =
          all.stream()
              .filter(value -> code.equals(value.getModuleType()))
              .findFirst()
              .orElseThrow();
      if (!TechnicalDataSubmissionState.submitted(module.getModuleStatus())
          || module.getAssigneeUserId() == null
          || !Integer.valueOf(1).equals(module.getRequiredFlag()))
        throw invalid("只能退回已提交的补录板块：" + code);
      groups.computeIfAbsent(module.getAssigneeUserId(), ignored -> new ArrayList<>()).add(code);
    }
    var product = products.lockActiveProducts(task.getId()).getFirst();
    for (var group : groups.entrySet()) {
      var person =
          recipients.current(task.getId()).stream()
              .filter(row -> row.userId() == group.getKey())
              .findFirst()
              .orElseThrow();
      if (!submitted(person)) throw invalid("原技术员任务尚未成功提交，或已有退回等待 OA 确认");
      var selected =
          group.getValue().stream()
              .sorted(Comparator.comparingInt(TechnicalDataModuleType::orderOf))
              .toList();
      String employee = context.technicianEmployeeNo(person.userId());
      if (!employee.equals(person.externalUserId())) throw invalid("原负责人身份已变化，请核实工号关联");
      var submission = submissions.selectById(person.latestSubmissionId());
      commands.add(
          new OaTechnicalReturnClient.Target(
              product.getMaterialNo(), selected, employee, person.name(), target.reason().trim()));
      long message =
          context.linkMessage(
              document,
              batchId,
              OaMessageCodec.InterfaceType.TECH_RETURN,
              Long.toString(person.id()),
              Map.of(
                  "taskId",
                  task.getId(),
                  "recipientId",
                  person.id(),
                  "submissionId",
                  submission.getId(),
                  "moduleTypes",
                  selected,
                  "reason",
                  target.reason().trim(),
                  "materialWorkItemId",
                  node.workItemId(),
                  "formVersion",
                  node.formVersion()));
      recipients.requestReturn(
          person.id(), submission.getId(), message, actor.userId(), target.reason().trim());
      audit.record(
          task,
          product,
          submission.getId(),
          "MATERIAL_RETURN_REQUESTED",
          "DONE",
          "RETURN_PENDING",
          person.name() + "：" + target.reason().trim(),
          actor,
          requestKey,
          "I05-REQUEST:" + message);
    }
    recipients.refreshTask(task.getId());
    workflow.invalidateFinance(task.getOaFlowId());
    return commands;
  }

  private void validateBeforeSending(OaTechnicalBatchRepository.Batch batch) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, batch.formId());
    var node = access.quoterNode(batch.formId());
    for (long message : batches.messageIds(batch.id())) {
      var scope = payload(message);
      if (node.actorId() != batch.actorId()
          || node.formVersion() != scope.path("formVersion").asLong()
          || !node.workItemId().equals(scope.path("materialWorkItemId").asText())) {
        throw invalid("OA 报价员办理状态或需求版本已变化，本次退回未发送");
      }
      var person = recipients.findById(scope.path("recipientId").asLong());
      if (person == null
          || !person.active()
          || !"RETURN_PENDING".equals(person.todoStatus())
          || !Objects.equals(person.returnMessageId(), message)
          || !Objects.equals(person.latestSubmissionId(), scope.path("submissionId").asLong()))
        throw invalid("原技术任务已变化，本次退回未发送");
    }
  }

  private void requireApplicableReceipt(OaTechnicalBatchRepository.Batch batch, JsonNode scope, boolean notifiedByOa) {
    var view = access.view(batch.formId());
    long version =
        jdbc.queryForObject(
            "SELECT source_version FROM lp_oa_quote_document WHERE oa_form_id=?",
            Long.class,
            batch.formId());
    // OA 通知已核验原流程和退回范围，回调身份无需具备报价员的浏览器办理权限。
    boolean sameQuoterNode = notifiedByOa || view != null && view.canCost()
        && access.quoterNode(batch.formId()).workItemId().equals(scope.path("materialWorkItemId").asText());
    if (view == null
        || view.syncError() != null
        || !Set.of("COSTING", "RECOSTING", "TECHNICAL").contains(view.state())
        || version != scope.path("formVersion").asLong()
        || Set.of("COSTING", "RECOSTING").contains(view.state()) && !sameQuoterNode) {
      throw invalid("OA 已接收退回，但当前流程或需求版本已变化，尚未开放编辑，请核实原请求");
    }
  }

  private Result complete(String id) {
    return complete(id, false);
  }

  private Result complete(String id, boolean notifiedByOa) {
    var original = batches.find(id, false);
    jdbc.queryForObject(
        "SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, original.formId());
    var batch = batches.find(id, true);
    if (batch.result() == null
        || "SUCCESS".equals(batch.status())
        || "UNKNOWN".equals(batch.status())) return result(batch);
    boolean accepted = "OA_ACCEPTED".equals(batch.status());
    boolean rejected = Set.of("REJECTED", "NOT_SENT").contains(batch.status());
    if (!accepted && !rejected) return result(batch);
    for (long message : batches.messageIds(id)) {
      var scope = payload(message);
      var task = tasks.selectByIdForUpdate(scope.path("taskId").asLong());
      var person = recipients.findById(scope.path("recipientId").asLong());
      if (person == null) throw invalid("原退回负责人不存在，请核实原任务");
      if (!Objects.equals(person.returnMessageId(), message)
          || !"RETURN_PENDING".equals(person.todoStatus())) continue;
      if (!person.active()
          || !Objects.equals(person.latestSubmissionId(), scope.path("submissionId").asLong())) {
        throw invalid("原退回任务已变化，请核实 OA 回执，不能开放其他任务");
      }
      if (accepted) {
        if (!"REJECT".equals(batch.returnStep()) || batch.peopleResult() == null
            || batch.peopleResult().status() != OaWorkflowResult.Status.SUCCESS)
          throw invalid("退回两步尚未全部确认，不能开放编辑");
        requireApplicableReceipt(batch, scope, notifiedByOa);
        List<String> selected = new ArrayList<>();
        scope.path("moduleTypes").forEach(value -> selected.add(value.asText()));
        recipients.revisionScope(person.id(), selected);
        for (String type : selected)
          jdbc.update(
              """
UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
SET m.oa_edit_allowed=1 WHERE p.task_id=? AND p.active_flag=1 AND m.module_type=? AND m.assignee_user_id=?
""",
              task.getId(),
              type,
              person.userId());
        lifecycle.financeReturned(
            task,
            submissions.selectById(person.latestSubmissionId()),
            batch.actorId(),
            scope.path("reason").asText());
        audit.recordSystem(
            task,
            "MATERIAL_RETURN_CONFIRMED",
            "RETURN_PENDING",
            "OPEN",
            person.name() + "：" + scope.path("reason").asText(),
            batch.requestKey(),
            "I05-CONFIRMED:" + message);
      } else {
        var submission = submissions.selectById(person.latestSubmissionId());
        recipients.state(person.id(), person.latestSubmissionId(), "RETURN_PENDING",
            "APPROVED".equals(submission.getSubmissionStatus()) ? "DONE" : "SUBMITTED", null);
        recipients.refreshTask(task.getId());
        workflow.refreshFinance(task.getOaFlowId());
      }
    }
    batches.finishMessages(batch);
    if (accepted) batches.completed(id);
    return result(batches.find(id, false));
  }

  private boolean submitted(Recipient person) {
    if (!TechnicalDataSubmissionState.submittedTodo(person.todoStatus()) || person.latestSubmissionId() == null) return false;
    var submission = submissions.selectById(person.latestSubmissionId());
    return submission != null && TechnicalDataSubmissionState.acceptedByOa(submission.getSubmissionStatus());
  }

  private QuoteTechTask requireTask(long id, TechnicalDataActor actor, boolean lock) {
    var task = lock ? tasks.selectByIdForUpdate(id) : tasks.selectById(id);
    if (actor == null
        || !actor.canCoordinateTask(task)
        || !Integer.valueOf(1).equals(task.getActiveFlag())
        || task.getOaFlowId() == null) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "无权办理此补录任务");
    }
    return task;
  }

  private JsonNode payload(long message) {
    return codec.read(messages.findById(message).rawPayload()).path("payload");
  }

  private Result result(OaTechnicalBatchRepository.Batch batch) {
    return new Result(batch.id(), batch.requestKey(), batch.status(), batch.result(),
        batch.returnStep(), batch.returnAttempt(), "REJECT".equals(batch.returnStep())
            && Set.of("RETURN_FAILED", "REJECT_READY").contains(batch.status()), batch.actorId());
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private void validate(Request request) {
    if (request == null
        || request.requestKey() == null
        || !request.requestKey().matches("[A-Za-z0-9._:-]{1,128}")
        || request.targets() == null
        || request.targets().isEmpty()
        || request.targets().size() > 100) throw invalid("退回请求编号及产品不能为空");
    var ids = new HashSet<Long>();
    for (var target : request.targets()) {
      if (target == null
          || target.taskId() <= 0
          || !ids.add(target.taskId())
          || target.expectedTaskVersion() < 0
          || target.modules() == null
          || target.modules().isEmpty()
          || target.modules().stream().anyMatch(Objects::isNull)
          || new HashSet<>(target.modules()).size() != target.modules().size()
          || !TechnicalDataModuleType.codes().containsAll(target.modules())
          || target.reason() == null
          || target.reason().isBlank()
          || target.reason().length() > 500) throw invalid("请核实退回产品、板块和原因");
    }
  }
}
