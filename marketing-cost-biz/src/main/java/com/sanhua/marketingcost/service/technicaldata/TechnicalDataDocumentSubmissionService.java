package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 同一 OA 单据按当前技术员汇总；只提交本人全部产品，不等待其他人。 */
@Service
public class TechnicalDataDocumentSubmissionService {

  public record Request(String idempotencyKey, String fingerprint) {
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
      throw new IllegalArgumentException("未定义字段：" + name);
    }
  }

  public record Result(String batchId, String status, OaWorkflowResult oaResult, String requestKey) {}

  public record Workbench(
    long formId,
    String oaNo,
    String operatorName,
    boolean readOnly,
    List<TechnicalDataTaskResponse> tasks,
    int completedProducts,
    String fingerprint,
    boolean canSubmit,
    Result lastSubmission
  ) {}

  private final JdbcTemplate jdbc;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechSubmissionMapper submissions;
  private final QuoteTechnicalDataRepository products;
  private final TechnicalDataTaskApplicationService taskViews;
  private final TechnicalDataTaskRepository taskRepository;
  private final TechnicalDataOaRecipientRepository recipients;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataSubmissionValidationService validation;
  private final TechnicalDataSubmissionSnapshotService snapshots;
  private final TechnicalDataParticipantVersions versions;
  private final TechnicalDataSubmissionRemark remarks;
  private final TechnicalDataOaContext context;
  private final OaTechnicalSubmissionClient client;
  private final OaTechnicalBatchRepository batches;
  private final OaTechnicalBatchDelivery delivery;
  private final OaMessageCodec codec;
  private final TransactionTemplate transaction;

  public TechnicalDataDocumentSubmissionService(
    JdbcTemplate jdbc,
    QuoteTechTaskMapper tasks,
    QuoteTechSubmissionMapper submissions,
    QuoteTechnicalDataRepository products,
    TechnicalDataTaskApplicationService taskViews,
    TechnicalDataTaskRepository taskRepository,
    TechnicalDataOaRecipientRepository recipients,
    TechnicalDataOaWorkflowRepository workflow,
    TechnicalDataSubmissionValidationService validation,
    TechnicalDataSubmissionSnapshotService snapshots,
    TechnicalDataParticipantVersions versions,
    TechnicalDataSubmissionRemark remarks,
    TechnicalDataOaContext context,
    OaTechnicalSubmissionClient client,
    OaTechnicalBatchRepository batches,
    OaTechnicalBatchDelivery delivery,
    OaMessageCodec codec,
    PlatformTransactionManager manager
  ) {
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.submissions = submissions;
    this.products = products;
    this.taskViews = taskViews;
    this.taskRepository = taskRepository;
    this.recipients = recipients;
    this.workflow = workflow;
    this.validation = validation;
    this.snapshots = snapshots;
    this.versions = versions;
    this.remarks = remarks;
    this.context = context;
    this.client = client;
    this.batches = batches;
    this.delivery = delivery;
    this.codec = codec;
    this.transaction = new TransactionTemplate(manager);
  }

  @Transactional(readOnly = true)
  public Workbench workbench(long formId, TechnicalDataActor actor) {
    requireReader(actor);
    var ids = taskIds(formId, actor);
    if (ids.isEmpty()) throw forbidden("本张 OA 单据没有可查看的补录任务");
    var views = ids
      .stream()
      .map(id -> taskViews.detail(id, actor))
      .toList();
    int complete = 0;
    boolean open = false;
    boolean pending = false;
    for (long id : ids) {
      var person = own(id, actor.userId());
      if (person == null) {
        continue;
      }
      if ("OPEN".equals(person.todoStatus())) open = true;
      if (Set.of("PREPARED", "RETURN_PENDING").contains(person.todoStatus())) pending = true;
      var modules = taskRepository.findModules(
        views
          .stream()
          .filter(view -> view.id() == id)
          .findFirst()
          .orElseThrow()
          .products()
          .getFirst()
          .id()
      );
      if (
        !person.processingModules().isEmpty() &&
        person
          .processingModules()
          .stream()
          .allMatch(type ->
            modules
              .stream()
              .anyMatch(
                module ->
                  type.equals(module.getModuleType()) &&
                  Set.of("READY", "FROZEN", "SUBMITTED", "APPROVED").contains(module.getModuleStatus())
              )
          )
      ) complete++;
    }
    var lastIds = jdbc.queryForList(
      "SELECT id FROM lp_oa_technical_batch WHERE operation='I03' AND oa_form_id=? AND actor_user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
      String.class,
      formId,
      actor.userId()
    );
    Result last = lastIds.isEmpty() ? null : result(batches.find(lastIds.getFirst(), false));
    return new Workbench(
      formId,
      views.getFirst().oaNo(),
      actor.name(),
      actor.canViewSupplementOverview(),
      views,
      actor.canViewSupplementOverview() ? 0 : complete,
      fingerprint(ids),
      !actor.canViewSupplementOverview() && open && !pending && complete == ids.size(),
      last
    );
  }

  @Transactional(readOnly = true)
  public Workbench submitted(long formId, String batchId, TechnicalDataActor actor) {
    requireReader(actor);
    var batch = batches.find(batchId, false);
    if (
      batch == null ||
      batch.formId() != formId ||
      !"I03".equals(batch.operation()) ||
      !"SUCCESS".equals(batch.status())
    ) {
      throw forbidden("本次提交尚未确认成功或不属于当前单据");
    }
    List<TechnicalDataTaskResponse> views = new ArrayList<>();
    for (long message : batches.messageIds(batchId)) {
      var row = submissions.selectByOutboundMessage(message);
      var snapshot = snapshots.read(row.getTaskId(), row.getId(), actor);
      views.add(taskViews.submittedDetail(snapshot, actor));
    }
    String name = jdbc.queryForObject(
      "SELECT nick_name FROM sys_user WHERE user_id=?",
      String.class,
      batch.actorId()
    );
    return new Workbench(
      formId,
      views.getFirst().oaNo(),
      name,
      true,
      views,
      views.size(),
      null,
      false,
      result(batch)
    );
  }

  public Result submit(long formId, Request request, TechnicalDataActor actor) {
    requireTechnician(actor);
    if (
      request == null ||
      request.idempotencyKey() == null ||
      !request.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}") ||
      request.fingerprint() == null ||
      !request.fingerprint().matches("[a-f0-9]{64}")
    ) throw new IllegalArgumentException("请提供提交编号和页面资料版本");
    String id = Objects.requireNonNull(transaction.execute(status -> prepare(formId, request, actor)));
    delivery.send(id);
    return Objects.requireNonNull(transaction.execute(status -> complete(id)));
  }

  private String prepare(long formId, Request request, TechnicalDataActor actor) {
    context.lockActor(actor);
    var previous = batches.findRequest("I03", actor.userId(), request.idempotencyKey());
    if (previous != null) {
      if (
        previous.formId() != formId || !previous.inputFingerprint().equals(request.fingerprint())
      ) throw conflict("同一提交编号不能更换单据或资料版本");
      return previous.id();
    }
    var document = context.document(formId);
    var ids = taskIds(formId, actor);
    if (ids.isEmpty()) throw forbidden("本人在本张 OA 单据没有补录任务");
    // 已批准且未退回的其他产品继续保留；本次只冻结本人待补录/待修改的产品。
    var selected = ids.stream().map(tasks::selectByIdForUpdate)
        .filter(task -> "OPEN".equals(own(task.getId(), actor.userId()).todoStatus())).toList();
    if (selected.isEmpty()) throw conflict("本人没有待提交的补录或修订任务");
    if (!fingerprint(ids).equals(request.fingerprint())) throw conflict(
      "本人任务或资料已变化，请刷新后确认提交"
    );
    for (var task : selected) {
      var person = own(task.getId(), actor.userId());
      if (
        person == null ||
        !"OPEN".equals(person.todoStatus()) ||
        !"PUBLISHED".equals(task.getExternalTaskStatus())
      ) {
        throw conflict("本人已有待确认或已提交资料，请查看原提交结果");
      }
      taskViews.detail(task.getId(), actor);
      var checked = validation.validate(task.getId(), actor.userId(), actor);
      if (!checked.valid()) throw conflict(
        checked
          .issues()
          .stream()
          .map(issue -> issue.materialNo() + "：" + issue.message())
          .distinct()
          .collect(Collectors.joining("；"))
      );
    }
    String employee = context.technicianEmployeeNo(actor.userId());
    String batchId = UUID.randomUUID().toString();
    batches.insert(batchId, "I03", formId, actor.userId(), request.idempotencyKey(), request.fingerprint());
    List<String> lines = new ArrayList<>();
    for (var task : selected) {
      var person = own(task.getId(), actor.userId());
      var product = products.lockActiveProducts(task.getId()).getFirst();
      var snapshot = snapshots.prepare(
        task.getId(),
        batchId,
        task.getTaskVersion(),
        product.getRowVersion(),
        person,
        actor
      );
      lines.add(remarks.generate(product.getMaterialNo(), snapshot));
      long message = context.linkMessage(
        document,
        batchId,
        OaMessageCodec.InterfaceType.TECH_SUBMISSION,
        snapshot.getId().toString(),
        Map.of("taskId", task.getId(), "submissionId", snapshot.getId())
      );
      workflow.linkSubmission(snapshot.getId(), message);
      workflow.markSending(snapshot.getId());
    }
    var body = client.preview(
      new OaTechnicalSubmissionClient.Request(
        document.requestId(),
        employee,
        String.join("\n", lines),
        context.workbenchUrl(formId) + "?submission=" + batchId
      )
    );
    batches.prepared(batchId, body);
    return batchId;
  }

  private Result complete(String id) {
    var batch = batches.find(id, true);
    if (batch.result() == null || "SUCCESS".equals(batch.status())) return result(batch);
    boolean accepted = "OA_ACCEPTED".equals(batch.status());
    boolean rejected = Set.of("REJECTED", "NOT_SENT").contains(batch.status());
    for (long message : batches.messageIds(id)) {
      var submission = submissions.selectByOutboundMessage(message);
      var task = tasks.selectByIdForUpdate(submission.getTaskId());
      var person = recipients.findById(submission.getRecipientId());
      if (
        Set.of("SENT", "APPROVED", "RETURNED", "FAILED").contains(submission.getSubmissionStatus())
      ) continue;
      if (accepted) {
        var product = products.lockProduct(submission.getProductId()).orElseThrow();
        var version = versions.verified(submission.getTechnicalVersionId(), product.getId());
        versions.transition(version, "SUBMITTED", batch.actorId());
        versions.state(product, person, version.getId(), "SUBMITTED");
        workflow.acceptSubmission(submission.getId(), batch.request().path("requestId").asText());
        recipients.state(person.id(), submission.getId(), "PREPARED", "SUBMITTED", null);
        recipients.refreshTask(task.getId());
      } else if (rejected) {
        workflow.rejectSubmission(submission.getId());
        recipients.state(person.id(), submission.getId(), "PREPARED", "OPEN", person.returnReason());
        recipients.refreshTask(task.getId());
        var product = products.lockProduct(submission.getProductId()).orElseThrow();
        versions.releaseCandidate(product, person, submission.getTechnicalVersionId(), batch.actorId());
      } else workflow.markUnknown(submission.getId());
    }
    batches.finishMessages(batch);
    if (accepted) batches.completed(id);
    return result(batches.find(id, false));
  }

  private List<Long> taskIds(long formId, TechnicalDataActor actor) {
    if (actor.canViewSupplementOverview()) return jdbc.queryForList(
      "SELECT id FROM lp_quote_tech_task WHERE oa_form_id=? AND active_flag=1 ORDER BY id",
      Long.class,
      formId
    );
    return jdbc.queryForList(
      """
      SELECT DISTINCT t.id FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id
      WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1 AND r.assignee_user_id=? ORDER BY t.id
      """,
      Long.class,
      formId,
      actor.userId()
    );
  }

  private String fingerprint(List<Long> ids) {
    return codec.canonicalHash(
      ids
        .stream()
        .map(id -> {
          var task = tasks.selectById(id);
          var product = taskRepository.findProducts(id).getFirst();
          return Map.of(
            "taskId",
            id,
            "taskVersion",
            task.getTaskVersion(),
            "productId",
            product.getId(),
            "productVersion",
            product.getRowVersion()
          );
        })
        .toList()
    );
  }

  private Recipient own(long taskId, long userId) {
    return recipients
      .current(taskId)
      .stream()
      .filter(person -> person.userId() == userId)
      .findFirst()
      .orElse(null);
  }

  private Result result(OaTechnicalBatchRepository.Batch batch) {
    return new Result(batch.id(), batch.status(), batch.result(), batch.requestKey());
  }

  private void requireReader(TechnicalDataActor actor) {
    if (actor == null || !actor.canReadTasks() || actor.shortSession()) throw forbidden(
      "当前会话无权查看单据工作台"
    );
  }

  private void requireTechnician(TechnicalDataActor actor) {
    requireReader(actor);
    if (!actor.canEdit() || actor.canViewSupplementOverview()) throw forbidden(
      "仅技术员本人可以提交已分派资料"
    );
  }

  private TechnicalDataTaskException forbidden(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }

  private IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }
}
