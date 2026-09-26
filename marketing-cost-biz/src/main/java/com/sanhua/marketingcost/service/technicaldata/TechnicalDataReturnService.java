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

/** 一张 OA 单的一次定向退回：选板块、关联原负责人、单次发送、成功后恢复编辑。 */
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
      Result lastReturn) {}

  public record Result(
      String batchId, String requestKey, String status, OaWorkflowResult oaResult) {}

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
  private final OaTechnicalBatchDelivery delivery;
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
      OaTechnicalBatchDelivery delivery,
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
              && "MATERIAL_REVIEW".equals(view.state())
              && !access.materialConfirmed(formId);
      List<Module> choices = new ArrayList<>();
      if (allowed)
        for (var person : recipients.current(id)) {
          if (!approved(person)) continue;
          for (var module : modules.selectByTaskId(id)) {
            if (Objects.equals(module.getAssigneeUserId(), person.userId())
                && "APPROVED".equals(module.getModuleStatus())) {
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
              last.isEmpty() ? null : result(batches.find(last.getFirst(), false))));
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
    var node = access.materialNode(document.formId());
    if (node.actorId() != actor.userId()) throw invalid("当前账号与 OA 报价员身份不一致");
    int confirming =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM lp_oa_material_confirmation WHERE oa_form_id=? AND form_version=?
              AND material_work_item_id=? AND status IN ('PREPARED','SENDING','SUCCESS','UNKNOWN')
            """,
            Integer.class,
            node.formId(),
            node.formVersion(),
            node.workItemId());
    if (confirming > 0) throw invalid("本轮资料已确认或正在确认，不能同时退回，请核实 OA 当前节点");
    String id = UUID.randomUUID().toString();
    batches.insert(id, "I05", document.formId(), actor.userId(), request.requestKey(), fingerprint);
    List<OaTechnicalReturnClient.Target> commands = new ArrayList<>();
    for (var target :
        request.targets().stream().sorted(Comparator.comparingLong(Target::taskId)).toList()) {
      commands.addAll(
          prepareProductReturn(target, id, request.requestKey(), document, node, actor));
    }
    batches.prepared(
        id,
        client.preview(
            new OaTechnicalReturnClient.Request(
                document.requestId(),
                document.processCode(),
                node.employeeNo(),
                commands,
                context.workbenchUrl(document.formId()))));
    return id;
  }

  /** 一个产品可选多个板块；按原负责人归组后保存各自的退回范围。 */
  private List<OaTechnicalReturnClient.Target> prepareProductReturn(
      Target target,
      String batchId,
      String requestKey,
      TechnicalDataOaContext.Document document,
      OaWorkflowAccessPolicy.MaterialNode node,
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
      if (!"APPROVED".equals(module.getModuleStatus())
          || module.getAssigneeUserId() == null
          || !Integer.valueOf(1).equals(module.getRequiredFlag()))
        throw invalid("只能退回已批准的补录板块：" + code);
      groups.computeIfAbsent(module.getAssigneeUserId(), ignored -> new ArrayList<>()).add(code);
    }
    var product = products.lockActiveProducts(task.getId()).getFirst();
    for (var group : groups.entrySet()) {
      var person =
          recipients.current(task.getId()).stream()
              .filter(row -> row.userId() == group.getKey())
              .findFirst()
              .orElseThrow();
      if (!approved(person)) throw invalid("原技术员任务未批准，或已有退回等待 OA 确认");
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
    if (!"PREPARED".equals(batches.find(batch.id(), false).status())) return;
    var node = access.materialNode(batch.formId());
    for (long message : batches.messageIds(batch.id())) {
      var scope = payload(message);
      if (node.actorId() != batch.actorId()
          || node.formVersion() != scope.path("formVersion").asLong()
          || !node.workItemId().equals(scope.path("materialWorkItemId").asText())) {
        throw invalid("OA 资料节点或需求版本已变化，本次退回未发送");
      }
      var person = recipients.findById(scope.path("recipientId").asLong());
      if (person == null
          || !person.active()
          || !"RETURN_PENDING".equals(person.todoStatus())
          || !Objects.equals(person.returnMessageId(), message)) throw invalid("原技术任务已变化，本次退回未发送");
    }
  }

  private void requireApplicableReceipt(OaTechnicalBatchRepository.Batch batch, JsonNode scope) {
    var view = access.view(batch.formId());
    long version =
        jdbc.queryForObject(
            "SELECT source_version FROM lp_oa_quote_document WHERE oa_form_id=?",
            Long.class,
            batch.formId());
    boolean sameMaterialNode =
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                """
SELECT EXISTS(SELECT 1 FROM lp_oa_quote_document d JOIN lp_oa_workflow_state s
  ON s.source_system=d.source_system AND s.environment=d.environment
  AND s.workflow_request_id=d.external_document_id,
  JSON_TABLE(s.active_work_items_json,'$[*]' COLUMNS(
    work_item VARCHAR(128) PATH '$.workItemId', role_name VARCHAR(32) PATH '$.nodeRole')) w
WHERE d.oa_form_id=? AND w.role_name='MATERIAL' AND w.work_item=?)
""",
                Boolean.class,
                batch.formId(),
                scope.path("materialWorkItemId").asText()));
    if (view == null
        || view.syncError() != null
        || !Set.of("MATERIAL_REVIEW", "TECHNICAL").contains(view.state())
        || version != scope.path("formVersion").asLong()
        || "MATERIAL_REVIEW".equals(view.state()) && !sameMaterialNode) {
      throw invalid("OA 已接收退回，但当前流程或需求版本已变化，尚未开放编辑，请核实原请求");
    }
  }

  private Result complete(String id) {
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
        requireApplicableReceipt(batch, scope);
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
        recipients.state(person.id(), person.latestSubmissionId(), "RETURN_PENDING", "DONE", null);
        recipients.refreshTask(task.getId());
        workflow.refreshFinance(task.getOaFlowId());
      }
    }
    batches.finishMessages(batch);
    if (accepted) batches.completed(id);
    return result(batches.find(id, false));
  }

  private boolean approved(Recipient person) {
    if (!"DONE".equals(person.todoStatus()) || person.latestSubmissionId() == null) return false;
    var submission = submissions.selectById(person.latestSubmissionId());
    return submission != null && "APPROVED".equals(submission.getSubmissionStatus());
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
    return new Result(batch.id(), batch.requestKey(), batch.status(), batch.result());
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
