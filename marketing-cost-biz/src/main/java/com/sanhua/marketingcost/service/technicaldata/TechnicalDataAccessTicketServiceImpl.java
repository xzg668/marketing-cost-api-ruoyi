package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueResponse;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataAccessTicketServiceImpl implements TechnicalDataAccessTicketService {
  private static final Set<String> PURPOSES = Set.of("TASK_ENTRY", "TASK_REVIEW");
  private static final int DEFAULT_TICKET_SECONDS = 300;
  private static final int MAX_TICKET_SECONDS = 600;
  private static final int SESSION_SECONDS = 900;

  private final JwtUtils jwtUtils;
  private final TechnicalDataTaskRepository taskRepository;
  private final SysUserService userService;
  private final TechnicalDataAuditLogService auditLog;
  private final BusinessChangeLogMapper changeLogMapper;

  public TechnicalDataAccessTicketServiceImpl(
      JwtUtils jwtUtils,
      TechnicalDataTaskRepository taskRepository,
      SysUserService userService,
      TechnicalDataAuditLogService auditLog,
      BusinessChangeLogMapper changeLogMapper) {
    this.jwtUtils = jwtUtils;
    this.taskRepository = taskRepository;
    this.userService = userService;
    this.auditLog = auditLog;
    this.changeLogMapper = changeLogMapper;
  }

  @Override
  @Transactional
  public TechnicalDataAccessTicketIssueResponse issue(
      Long taskId, TechnicalDataAccessTicketIssueRequest request, TechnicalDataActor actor) {
    if (actor == null || !actor.admin()) throw forbidden("仅管理员可签发短时访问票据");
    if (request == null || !request.getUnknownFields().isEmpty()) {
      throw invalid(request == null ? "请求体不能为空" : "请求包含未知字段");
    }
    String reason = text(request.getReason(), "reason", 500);
    String requestId = text(request.getRequestId(), "requestId", 128);
    QuoteTechTask task = task(taskId);
    String purpose = purpose(request.getPurpose());
    Long userId = positive(request.getUserId(), "userId");
    SysUser user = validUser(userId);
    requireBinding(task, userId, purpose);
    int seconds = ttl(request.getTtlSeconds());
    TechnicalDataAccessTicketIssueResponse response = create(task, user, purpose, seconds);
    auditLog.record(
        task, null, null, "ACCESS_TICKET_ISSUED", null,
        "user=" + userId + ",purpose=" + purpose + ",expiresAt=" + response.expiresAt(),
        reason, actor, requestId,
        "TD_TICKET_ISSUE:" + task.getId() + ":" + requestId);
    return response;
  }

  @Override
  @Transactional
  public TechnicalDataAccessTicketIssueResponse issueForAssignee(
      Long taskId, TechnicalDataActor actor) {
    QuoteTechTask task = task(taskId);
    SysUser user = validUser(task.getAssigneeUserId());
    TechnicalDataAccessTicketIssueResponse response = create(
        task, user, "TASK_ENTRY", DEFAULT_TICKET_SECONDS);
    auditLog.record(
        task, null, null, "OA_ACCESS_TICKET_ISSUED", null,
        "user=" + user.getUserId() + ",expiresAt=" + response.expiresAt(),
        "OA待办发布", actor, "oa-ticket:" + task.getId() + ":" + response.expiresAt());
    return response;
  }

  @Override
  @Transactional
  public TechnicalDataAccessTicketExchangeResponse exchange(
      TechnicalDataAccessTicketExchangeRequest request) {
    if (request == null || !StringUtils.hasText(request.ticket())) throw invalid("ticket不能为空");
    JwtUtils.TechnicalDataTicketClaims claims;
    try {
      claims = jwtUtils.parseTechnicalDataTicket(request.ticket().trim());
    } catch (JwtException | IllegalArgumentException exception) {
      throw forbidden("票据无效、被篡改或已过期");
    }
    if (!Objects.equals(claims.userId(), request.expectedUserId())) {
      throw forbidden("票据绑定用户与当前SSO用户不一致");
    }
    QuoteTechTask task = task(claims.taskId());
    SysUser user = validUser(claims.userId());
    if (!Objects.equals(user.getUserName(), claims.username())) throw forbidden("票据用户身份已变化");
    String purpose = purpose(claims.purpose());
    requireBinding(task, user.getUserId(), purpose);
    requirePermission(user.getUserId(), purpose);
    consume(task, user, claims);
    Instant expiresAt = Instant.now().plusSeconds(SESSION_SECONDS);
    String accessToken = jwtUtils.generateTechnicalDataSessionToken(
        user.getUserName(), user.getBusinessUnitType(), task.getId(), purpose, SESSION_SECONDS);
    return new TechnicalDataAccessTicketExchangeResponse(
        accessToken, task.getId(), user.getUserId(), purpose, expiresAt,
        "/technical-data-access/tasks/" + task.getId());
  }

  private TechnicalDataAccessTicketIssueResponse create(
      QuoteTechTask task, SysUser user, String purpose, int seconds) {
    Instant expiresAt = Instant.now().plusSeconds(seconds);
    String ticket = jwtUtils.generateTechnicalDataTicket(
        user.getUserName(), user.getUserId(), task.getId(), purpose, seconds);
    return new TechnicalDataAccessTicketIssueResponse(
        ticket, task.getId(), user.getUserId(), purpose, expiresAt);
  }

  private void consume(
      QuoteTechTask task, SysUser user, JwtUtils.TechnicalDataTicketClaims claims) {
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain(TechnicalDataAuditLogService.DOMAIN);
    log.setBizType("TECH_DATA_SECURITY_EVENT");
    log.setBizId(task.getId());
    log.setTaskId(task.getId());
    log.setOaNo(task.getOaNo());
    log.setFieldName("ACCESS_TICKET_CONSUMED");
    log.setFieldLabel("一次性短票消费");
    log.setAfterValue("user=" + user.getUserId() + ",purpose=" + claims.purpose());
    log.setChangeReason("统一登录入口兑换");
    log.setChangedBy(user.getUserId());
    log.setChangedByName(displayName(user));
    log.setChangedAt(LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    log.setChangeSource("SSO_SHORT_TICKET");
    log.setRequestId("ticket:" + claims.nonce());
    log.setIdempotencyKey("TD_TICKET_CONSUME:" + claims.nonce());
    try {
      if (changeLogMapper.insert(log) != 1) throw conflict("票据消费记录保存失败");
    } catch (DuplicateKeyException exception) {
      throw forbidden("票据已使用，不能重复兑换");
    }
  }

  private void requireBinding(QuoteTechTask task, Long userId, String purpose) {
    if ("TASK_ENTRY".equals(purpose)
        && !Objects.equals(task.getAssigneeUserId(), userId)) {
      throw forbidden("用户不是该任务的技术负责人");
    }
    if ("TASK_REVIEW".equals(purpose)
        && !Objects.equals(task.getReviewerUserId(), userId)) {
      throw forbidden("用户不是该任务的审核人");
    }
  }

  private void requirePermission(Long userId, String purpose) {
    Set<String> values = userService.findPermissionsByUserId(userId);
    boolean all = values.contains("*:*:*");
    boolean allowed = "TASK_ENTRY".equals(purpose)
        ? all || values.contains("technical:data:task:list")
            || values.contains("technical:data:task:edit")
        : all || values.contains("technical:data:review:list");
    if (!allowed) throw forbidden("用户没有票据用途对应的技术资料权限");
  }

  private QuoteTechTask task(Long taskId) {
    QuoteTechTask task = taskRepository.findTask(positive(taskId, "taskId"))
        .orElseThrow(() -> new TechnicalDataTaskException(
            TechnicalDataTaskErrorCode.TASK_NOT_FOUND, "技术资料任务不存在"));
    if (!Integer.valueOf(1).equals(task.getActiveFlag()) || "CANCELLED".equals(task.getTaskStatus())) {
      throw forbidden("历史或已作废任务不能签发、兑换票据");
    }
    return task;
  }

  private SysUser validUser(Long userId) {
    SysUser user = userService.getById(userId);
    if (user == null || !"0".equals(user.getStatus())
        || (StringUtils.hasText(user.getDelFlag()) && !"0".equals(user.getDelFlag()))) {
      throw forbidden("票据用户不存在或已停用");
    }
    return user;
  }

  private String purpose(String value) {
    String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!PURPOSES.contains(result)) throw invalid("purpose只支持TASK_ENTRY或TASK_REVIEW");
    return result;
  }

  private int ttl(Integer value) {
    int result = value == null ? DEFAULT_TICKET_SECONDS : value;
    if (result < 30 || result > MAX_TICKET_SECONDS) {
      throw invalid("ttlSeconds必须在30到600秒之间");
    }
    return result;
  }

  private Long positive(Long value, String field) {
    if (value == null || value <= 0) throw invalid(field + "必须大于0");
    return value;
  }

  private String text(String value, String field, int max) {
    if (!StringUtils.hasText(value)) throw invalid(field + "不能为空");
    String result = value.trim();
    if (result.length() > max) throw invalid(field + "长度不能超过" + max);
    return result;
  }

  private String displayName(SysUser user) {
    return StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
  }

  private TechnicalDataTaskException invalid(String value) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, value);
  }
  private TechnicalDataTaskException forbidden(String value) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, value);
  }
  private TechnicalDataTaskException conflict(String value) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.PERSISTENCE_CONFLICT, value);
  }
}
