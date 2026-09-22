package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一个事务完成校验、本人模块冻结和可靠排队；收到 OA 回执后才能进入审批。 */
@Service
public class TechnicalDataSubmissionApplicationServiceImpl implements TechnicalDataSubmissionApplicationService {
  private final TechnicalDataSubmissionValidationService validation;
  private final TechnicalDataSubmissionSnapshotService snapshots;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaUserDirectory users;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechSubmissionMapper submissions;
  private final QuoteTechnicalDataRepository products;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataAuditLogService audit;
  private final TechnicalDataOaRecipientRepository recipients;

  public TechnicalDataSubmissionApplicationServiceImpl(TechnicalDataSubmissionValidationService validation,
      TechnicalDataSubmissionSnapshotService snapshots, TechnicalDataOaGateway gateway,
      TechnicalDataOaUserDirectory users, TechnicalDataOaWorkflowRepository workflow, QuoteTechTaskMapper tasks,
      QuoteTechSubmissionMapper submissions, QuoteTechnicalDataRepository products, OaMessageRepository messages,
      OaMessageCodec codec, TechnicalDataAuditLogService audit, TechnicalDataOaRecipientRepository recipients) {
    this.validation = validation; this.snapshots = snapshots; this.gateway = gateway; this.users = users;
    this.workflow = workflow; this.tasks = tasks; this.submissions = submissions; this.products = products;
    this.messages = messages; this.codec = codec; this.audit = audit; this.recipients = recipients;
  }

  @Override public TechnicalDataTaskValidationResponse validate(Long taskId, Long assigneeUserId, TechnicalDataActor actor) {
    var person = person(tasks.selectById(taskId), assigneeUserId, actor);
    return validation.validate(taskId, person.userId(), actor);
  }

  @Override @Transactional
  public TechnicalDataTaskSubmissionResponse submit(Long taskId, TechnicalDataTaskSubmissionRequest request, TechnicalDataActor actor) {
    if (request == null || !request.getUnknownFields().isEmpty() || request.getExpectedTaskVersion() == null
        || request.getExpectedVersion() == null || request.getIdempotencyKey() == null
        || !request.getIdempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("请提供提交编号和任务、产品并发版本");
    var task = tasks.selectByIdForUpdate(taskId);
    var person = person(task, request.getAssigneeUserId(), actor);
    var existing = submissions.selectByRequest(taskId, request.getIdempotencyKey());
    if (existing != null) {
      if (!Objects.equals(existing.getExpectedTaskVersion(), request.getExpectedTaskVersion())
          || !Objects.equals(existing.getExpectedProductVersion(), request.getExpectedVersion())
          || !Objects.equals(existing.getAssigneeUserId(), person.userId())) throw conflict("同一提交编号不能更换任务或产品版本");
      return response(task, existing, true, null);
    }
    var peer = gateway.peer();
    if (!peer.businessUnits().contains(task.getBusinessUnitType())) throw conflict("此 OA 通道无权办理该业务单元");
    if (!Objects.equals(task.getTaskVersion(), request.getExpectedTaskVersion())) throw conflict("任务版本已变化，请刷新后提交");
    if (!"PUBLISHED".equals(task.getExternalTaskStatus()) || task.getOaFlowId() == null) throw conflict("OA 分派尚未确认，请先核实待办");
    var flow = workflow.lockFlow(task.getOaFlowId());
    if (flow == null || flow.externalFlowId() == null || !peer.sourceSystem().equals(flow.sourceSystem())
        || !peer.environment().equals(flow.environment())) throw conflict("OA 流程身份不一致");
    String assignee = person.externalUserId();
    if (person.integrationTaskId() == null || person.integrationTaskId().isBlank()) throw conflict("本人待办缺少对外任务编号，请先核实 OA 分派");
    if (!"OPEN".equals(person.todoStatus())) throw conflict("本人已有待确认或待审批提交，请先查看处理结果");
    String operator = users.externalId(peer, actor.userId());
    var checked = validation.validate(taskId, person.userId(), actor);
    if (!checked.valid()) return response(task, null, false, checked);
    var submission = snapshots.prepare(taskId, request.getIdempotencyKey(), request.getExpectedTaskVersion(), request.getExpectedVersion(), person, actor);
    String requestId = "TD-SUBMISSION:" + submission.getId();
    String raw = codec.write(Map.of("schemaVersion", 1, "sourceSystem", peer.sourceSystem(), "environment", peer.environment(),
        "requestId", requestId, "occurredAt", OffsetDateTime.now().toString(), "payload", Map.ofEntries(
        Map.entry("taskId", person.integrationTaskId()), Map.entry("quoteTaskId", taskId),
        Map.entry("submissionId", requestId), Map.entry("quoteSubmissionId", submission.getId()),
        Map.entry("documentId", flow.documentId()), Map.entry("technicalVersionId", submission.getTechnicalVersionId()),
        Map.entry("round", submission.getSubmissionRound()), Map.entry("recipientId", person.id()),
        Map.entry("moduleTypes", person.modules()), Map.entry("leaderExternalId", person.leaderExternalId()),
        Map.entry("technicalDataReady", true), Map.entry("externalTaskId", person.externalTaskId()),
        Map.entry("externalFlowId", flow.externalFlowId()), Map.entry("assigneeExternalId", assignee), Map.entry("operatorExternalId", operator),
        Map.entry("contentFingerprint", submission.getContentFingerprint()), Map.entry("contentSnapshot", codec.read(submission.getContentSnapshotJson())),
        Map.entry("summary", codec.read(submission.getSummaryJson())))));
    var type = OaMessageCodec.InterfaceType.TECH_SUBMISSION;
    var message = messages.enqueue(peer, type, codec.decode(raw, peer, type));
    workflow.linkSubmission(submission.getId(), message.id());
    audit.record(task, null, submission.getId(), "PRODUCT_SUBMISSION_QUEUED", task.getTaskStatus(), "PREPARED",
        person.name() + "负责的模块已冻结并排队，尚未确认 OA 受理", actor, request.getIdempotencyKey(), "TD-SUBMISSION-QUEUED:" + submission.getId());
    return response(tasks.selectById(taskId), submissions.selectById(submission.getId()), false, checked);
  }

  private TechnicalDataTaskSubmissionResponse response(QuoteTechTask task, QuoteTechSubmission submission,
      boolean replay, TechnicalDataTaskValidationResponse checked) {
    boolean sent = submission != null && Set.of("SENT", "APPROVED", "RETURNED").contains(submission.getSubmissionStatus());
    List<TechnicalDataTaskSubmissionResponse.ProductSubmission> values = List.of();
    if (submission != null) {
      var product = products.findProduct(submission.getProductId()).orElseThrow();
      var version = products.findVersion(submission.getTechnicalVersionId()).orElseThrow();
      values = List.of(new TechnicalDataTaskSubmissionResponse.ProductSubmission(product.getId(), product.getMaterialNo(),
          version.getId(), version.getVersionNo(), version.getVersionStatus(), version.getContentFingerprint(), 0));
    }
    return new TechnicalDataTaskSubmissionResponse(sent, replay, task.getId(), task.getTaskNo(), task.getTaskStatus(),
        task.getReviewStatus(), submission == null ? 0 : submission.getSubmissionRound(), task.getTaskVersion(), submission == null ? null : submission.getContentFingerprint(),
        submission == null ? null : submission.getSentAt(), checked, values, submission == null ? null : submission.getId(),
        submission == null ? null : submission.getSubmissionStatus(), submission != null && !"FAILED".equals(submission.getSubmissionStatus()));
  }

  private Recipient person(QuoteTechTask task, Long requestedUserId, TechnicalDataActor actor) {
    if (task == null || actor == null || !actor.canEdit() || !actor.canAccessTask(task.getId())
        || !Integer.valueOf(1).equals(task.getActiveFlag())) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "无权办理此产品任务");
    }
    var current = recipients.current(task.getId());
    Long userId = requestedUserId;
    if (userId == null) userId = actor.admin() && current.size() == 1 ? current.getFirst().userId() : actor.userId();
    if (!actor.admin() && !Objects.equals(actor.userId(), userId)) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "只能提交本人负责的模块");
    }
    Long selected = userId;
    return current.stream().filter(row -> row.userId() == selected && "CONFIRMED".equals(row.dispatchStatus())
        && row.leaderExternalId() != null).findFirst().orElseThrow(() -> conflict(
            actor.admin() ? "请选择一位已分派人员，按其负责模块代为提交" : "本人尚无已确认的 OA 待办"));
  }

  private TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
