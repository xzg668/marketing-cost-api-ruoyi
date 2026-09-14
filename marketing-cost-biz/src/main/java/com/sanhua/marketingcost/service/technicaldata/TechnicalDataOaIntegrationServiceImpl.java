package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaProperties;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataOaIntegrationServiceImpl implements TechnicalDataOaIntegrationService {
  private static final Set<String> CALLBACK_STATUSES = Set.of(
      "PUBLISHED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "FAILED");

  private final TechnicalDataOaProperties properties;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataAccessTicketService ticketService;
  private final TechnicalDataTaskRepository repository;
  private final QuoteTechTaskMapper taskMapper;
  private final TechnicalDataTaskApplicationService taskService;
  private final TechnicalDataAuditLogService auditLog;

  public TechnicalDataOaIntegrationServiceImpl(
      TechnicalDataOaProperties properties,
      TechnicalDataOaGateway gateway,
      TechnicalDataAccessTicketService ticketService,
      TechnicalDataTaskRepository repository,
      QuoteTechTaskMapper taskMapper,
      TechnicalDataTaskApplicationService taskService,
      TechnicalDataAuditLogService auditLog) {
    this.properties = properties;
    this.gateway = gateway;
    this.ticketService = ticketService;
    this.repository = repository;
    this.taskMapper = taskMapper;
    this.taskService = taskService;
    this.auditLog = auditLog;
  }

  @Override
  public boolean enabled() {
    return properties.isEnabled();
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse publishInitial(Long taskId, TechnicalDataActor actor) {
    return synchronize(positive(taskId, "taskId"), actor, "OA待办初次发布", null, false);
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse retry(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    if (actor == null || !actor.admin()) throw forbidden("仅管理员可重试OA同步");
    if (request == null || !request.getUnknownFields().isEmpty()) {
      throw invalid(request == null ? "请求体不能为空" : "请求包含未知字段");
    }
    String reason = text(request.getReason(), "reason", 500);
    String requestId = text(request.getRequestId(), "requestId", 128);
    if (request.getExpectedTaskVersion() == null || request.getExpectedTaskVersion() < 0) {
      throw invalid("expectedTaskVersion必须大于等于0");
    }
    QuoteTechTask current = repository.findTask(positive(taskId, "taskId"))
        .orElseThrow(() -> notFound("技术资料任务不存在"));
    if (!Objects.equals(current.getTaskVersion(), request.getExpectedTaskVersion())) {
      throw conflict("任务已被其他会话修改；当前版本=" + current.getTaskVersion());
    }
    TechnicalDataTaskResponse response = synchronize(taskId, actor, reason, requestId, true);
    QuoteTechTask after = repository.findTask(taskId).orElseThrow(() -> notFound("技术资料任务不存在"));
    auditLog.record(
        after, null, null, "ADMIN_OA_SYNC_RETRIED",
        current.getExternalTaskStatus(), after.getExternalTaskStatus(), reason, actor, requestId,
        "TD_ADMIN:OA_RETRY:" + taskId + ":" + requestId);
    return response;
  }

  private TechnicalDataTaskResponse synchronize(
      Long taskId, TechnicalDataActor actor, String reason, String requestId, boolean retry) {
    QuoteTechTask task = repository.findTask(taskId)
        .orElseThrow(() -> notFound("技术资料任务不存在"));
    if (!Integer.valueOf(1).equals(task.getActiveFlag())) throw conflict("历史任务不能同步OA待办");
    if (StringUtils.hasText(task.getExternalTaskId())
        && !"SYNC_FAILED".equals(task.getExternalTaskStatus()) && !retry) {
      return taskService.detail(taskId, actor);
    }
    TechnicalDataAccessTicketIssueResponse ticket = ticketService.issueForAssignee(taskId, actor);
    String accessUrl = properties.getFrontendBaseUrl().replaceAll("/+$", "")
        + "/technical-data-access?ticket=" + ticket.ticket()
        + "&userId=" + ticket.userId();
    String idempotencyKey = "TD-OA-PUBLISH:" + task.getId();
    TechnicalDataOaGateway.PublishResult published;
    try {
      published = gateway.publish(
          new TechnicalDataOaGateway.PublishCommand(
              task.getId(), task.getTaskNo(), task.getOaNo(), task.getAccountingMonth(),
              task.getAssigneeUserId(), task.getAssigneeName(), task.getDueAt(), accessUrl,
              idempotencyKey));
    } catch (RuntimeException exception) {
      markFailed(task, actor, exception);
      return taskService.detail(taskId, actor);
    }
    markPublished(task, published, actor, reason, requestId, idempotencyKey);
    return taskService.detail(taskId, actor);
  }

  @Transactional
  protected void markPublished(
      QuoteTechTask snapshot,
      TechnicalDataOaGateway.PublishResult result,
      TechnicalDataActor actor,
      String reason,
      String requestId,
      String idempotencyKey) {
    if (!StringUtils.hasText(result.externalTaskId())) throw invalid("OA未返回外部任务ID");
    QuoteTechTask conflict = taskMapper.selectByExternalIdentityForUpdate(
        "OA", result.externalTaskId().trim());
    if (conflict != null && !Objects.equals(conflict.getId(), snapshot.getId())) {
      throw new TechnicalDataTaskException(
          TechnicalDataTaskErrorCode.ACTIVE_PRODUCT_CONFLICT,
          "外部任务ID已绑定其他技术任务：" + conflict.getId());
    }
    try {
      if (taskMapper.markExternalPublished(
          snapshot.getId(), snapshot.getTaskVersion(), "OA", result.externalTaskId().trim(),
          normalizeStatus(result.status()), actor.userId(), now()) != 1) {
        throw conflict("OA发布完成时任务版本已变化，请按相同幂等键重试");
      }
    } catch (DuplicateKeyException exception) {
      throw conflict("外部任务ID已绑定其他技术任务");
    }
    QuoteTechTask after = repository.findTask(snapshot.getId())
        .orElseThrow(() -> notFound("技术资料任务不存在"));
    auditLog.recordSystem(
        after, "OA_TASK_PUBLISHED", snapshot.getExternalTaskStatus(),
        after.getExternalTaskStatus(), reason,
        requestId == null ? idempotencyKey : requestId,
        null);
  }

  @Transactional
  protected void markFailed(QuoteTechTask snapshot, TechnicalDataActor actor, RuntimeException failure) {
    QuoteTechTask current = repository.findTask(snapshot.getId()).orElse(snapshot);
    String message = failure.getMessage() == null
        ? failure.getClass().getSimpleName() : failure.getMessage();
    if (message.length() > 1000) message = message.substring(0, 1000);
    int retries = current.getExternalRetryCount() == null ? 0 : current.getExternalRetryCount();
    long delay = Math.min(3600, 30L << Math.min(retries, 6));
    LocalDateTime changedAt = now();
    if (taskMapper.markExternalFailed(
        current.getId(), current.getTaskVersion(), message, changedAt.plusSeconds(delay),
        actor.userId(), changedAt) != 1) {
      throw conflict("记录OA同步失败时任务版本已变化");
    }
    QuoteTechTask after = repository.findTask(current.getId()).orElse(current);
    auditLog.recordSystem(
        after, "OA_TASK_SYNC_FAILED", current.getExternalTaskStatus(), "SYNC_FAILED",
        message, "oa-failure:" + current.getId() + ":" + (retries + 1), null);
  }

  @Override
  @Transactional
  public TechnicalDataOaCallbackResponse callback(TechnicalDataOaCallbackRequest request) {
    Callback command = callbackCommand(request);
    verifySignature(command);
    QuoteTechTask task = taskMapper.selectByIdForUpdate(command.taskId());
    if (task == null) throw notFound("技术资料任务不存在");
    if (!"OA".equalsIgnoreCase(task.getExternalSystem())
        || !Objects.equals(task.getExternalTaskId(), command.externalTaskId())) {
      throw conflict("回调外部任务ID与技术任务绑定不一致");
    }
    QuoteTechTask owner = taskMapper.selectByExternalIdentityForUpdate("OA", command.externalTaskId());
    if (owner == null || !Objects.equals(owner.getId(), task.getId())) {
      throw conflict("外部任务ID绑定冲突");
    }
    long accepted = task.getExternalCallbackSeq() == null ? 0 : task.getExternalCallbackSeq();
    if (command.sequence() <= accepted
        || Objects.equals(task.getExternalLastEventId(), command.eventId())) {
      return new TechnicalDataOaCallbackResponse(
          "IGNORED", task.getId(), task.getExternalTaskId(), task.getExternalTaskStatus(), accepted);
    }
    if (taskMapper.acceptExternalCallback(
        task.getId(), "OA", command.externalTaskId(), command.status(), command.sequence(),
        command.eventId(), now()) != 1) {
      return new TechnicalDataOaCallbackResponse(
          "IGNORED", task.getId(), task.getExternalTaskId(), task.getExternalTaskStatus(), accepted);
    }
    QuoteTechTask after = repository.findTask(task.getId()).orElse(task);
    auditLog.recordSystem(
        after, "OA_TASK_CALLBACK_ACCEPTED", task.getExternalTaskStatus(), command.status(),
        "sequence=" + command.sequence(), command.eventId(),
        "TD_OA_CALLBACK:" + command.eventId());
    return new TechnicalDataOaCallbackResponse(
        "ACCEPTED", after.getId(), after.getExternalTaskId(),
        after.getExternalTaskStatus(), command.sequence());
  }

  private Callback callbackCommand(TechnicalDataOaCallbackRequest request) {
    if (request == null) throw invalid("回调请求不能为空");
    String eventId = text(request.eventId(), "eventId", 128);
    Long taskId = positive(request.taskId(), "taskId");
    String externalTaskId = text(request.externalTaskId(), "externalTaskId", 128);
    String status = normalizeStatus(request.status());
    long sequence = request.sequence() == null ? 0 : request.sequence();
    if (sequence <= 0) throw invalid("sequence必须大于0");
    if (request.timestamp() == null || request.timestamp() <= 0) throw invalid("timestamp无效");
    if (!StringUtils.hasText(request.signature())) throw forbidden("回调签名不能为空");
    return new Callback(eventId, taskId, externalTaskId, status, sequence,
        request.occurredAt(), request.timestamp(), request.signature().trim().toLowerCase(Locale.ROOT));
  }

  private void verifySignature(Callback command) {
    long skew = Math.abs(Instant.now().getEpochSecond() - command.timestamp());
    if (skew > properties.getCallbackClockSkewSeconds()) throw forbidden("回调时间戳已过期");
    String canonical = String.join("\n",
        command.eventId(), String.valueOf(command.taskId()), command.externalTaskId(),
        command.status(), String.valueOf(command.sequence()), String.valueOf(command.timestamp()));
    String expected = hmac(canonical, properties.getCallbackSecret());
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        command.signature().getBytes(StandardCharsets.US_ASCII))) {
      throw forbidden("回调签名错误");
    }
  }

  private String hmac(String value, String secret) {
    if (!StringUtils.hasText(secret) || secret.length() < 32) {
      throw new IllegalStateException("OA回调密钥必须至少32个字符");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("OA回调签名计算失败", exception);
    }
  }

  private String normalizeStatus(String value) {
    String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!CALLBACK_STATUSES.contains(result)) throw invalid("OA任务状态无效");
    return result;
  }

  private String text(String value, String field, int max) {
    if (!StringUtils.hasText(value)) throw invalid(field + "不能为空");
    String result = value.trim();
    if (result.length() > max) throw invalid(field + "长度不能超过" + max);
    return result;
  }

  private Long positive(Long value, String field) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  private TechnicalDataTaskException invalid(String value) {
    return error(TechnicalDataTaskErrorCode.INVALID_REQUEST, value);
  }
  private TechnicalDataTaskException forbidden(String value) {
    return error(TechnicalDataTaskErrorCode.FORBIDDEN, value);
  }
  private TechnicalDataTaskException notFound(String value) {
    return error(TechnicalDataTaskErrorCode.TASK_NOT_FOUND, value);
  }
  private TechnicalDataTaskException conflict(String value) {
    return error(TechnicalDataTaskErrorCode.VERSION_CONFLICT, value);
  }
  private TechnicalDataTaskException error(TechnicalDataTaskErrorCode code, String value) {
    return new TechnicalDataTaskException(code, value);
  }

  private record Callback(
      String eventId, Long taskId, String externalTaskId, String status, long sequence,
      Instant occurredAt, long timestamp, String signature) {}
}
