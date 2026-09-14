package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TechnicalDataAdminOperationServiceImpl
    implements TechnicalDataAdminOperationService {
  private final QuoteTechTaskMapper taskMapper;
  private final TechnicalDataTaskRepository repository;
  private final TechnicalDataTaskApplicationService taskService;
  private final TechnicalDataAuditLogService auditLog;
  private final SysUserService userService;

  public TechnicalDataAdminOperationServiceImpl(
      QuoteTechTaskMapper taskMapper,
      TechnicalDataTaskRepository repository,
      TechnicalDataTaskApplicationService taskService,
      TechnicalDataAuditLogService auditLog,
      SysUserService userService) {
    this.taskMapper = taskMapper;
    this.repository = repository;
    this.taskService = taskService;
    this.auditLog = auditLog;
    this.userService = userService;
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse reassign(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    Command command = command(request, actor);
    QuoteTechTask task = lock(taskId, command.expectedVersion());
    SysUser assignee = activeUser(positive(request.getAssigneeUserId(), "assigneeUserId"));
    SysUser reviewer = request.getReviewerUserId() == null
        ? task.getReviewerUserId() == null ? null : activeUser(task.getReviewerUserId())
        : activeUser(positive(request.getReviewerUserId(), "reviewerUserId"));
    if (reviewer != null && Objects.equals(assignee.getUserId(), reviewer.getUserId())) {
      throw invalid("技术负责人和审核人不能是同一人");
    }
    String before = assignment(task.getAssigneeUserId(), task.getAssigneeName(),
        task.getReviewerUserId(), task.getReviewerName());
    String after = assignment(assignee.getUserId(), displayName(assignee),
        reviewer == null ? null : reviewer.getUserId(), reviewer == null ? null : displayName(reviewer));
    try {
      int rows = taskMapper.reassign(
          task.getId(), command.expectedVersion(), assignee.getUserId(), displayName(assignee),
          reviewer == null ? null : reviewer.getUserId(), reviewer == null ? null : displayName(reviewer),
          actor.userId(), now());
      if (rows != 1) throw conflict("任务状态或版本已变化，不能改派");
    } catch (DuplicateKeyException exception) {
      throw conflict("目标负责人已有同一OA单和月份的活动任务，不能改派");
    }
    audit(task, "ADMIN_REASSIGN", before, after, command, actor);
    return taskService.detail(task.getId(), actor);
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse startProxyEntry(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    Command command = command(request, actor);
    QuoteTechTask task = lock(taskId, command.expectedVersion());
    if (taskMapper.startProxyEntry(
        task.getId(), command.expectedVersion(), actor.userId(), actor.name(),
        command.reason(), command.requestId(), now()) != 1) {
      throw conflict("任务状态、版本已变化或已有管理员正在代录");
    }
    audit(task, "ADMIN_PROXY_ENTRY_STARTED", null,
        "operator=" + actor.userId(), command, actor);
    return taskService.detail(task.getId(), actor);
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse unlockDraft(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    Command command = command(request, actor);
    QuoteTechTask task = lock(taskId, command.expectedVersion());
    String before = "operator=" + task.getProxyOperatorUserId()
        + ",requestId=" + task.getProxyRequestId();
    if (taskMapper.unlockDraft(
        task.getId(), command.expectedVersion(), actor.userId(), now()) != 1) {
      throw conflict("任务版本已变化或当前没有可解除的异常代录锁");
    }
    audit(task, "ADMIN_DRAFT_UNLOCKED", before, "UNLOCKED", command, actor);
    return taskService.detail(task.getId(), actor);
  }

  @Override
  @Transactional
  public TechnicalDataTaskResponse voidTask(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    Command command = command(request, actor);
    QuoteTechTask task = lock(taskId, command.expectedVersion());
    if ("APPROVED".equals(task.getTaskStatus())) {
      throw conflict("已生效任务不可作废；必须通过新版本更正");
    }
    repository.deactivateProducts(task.getId(), now());
    if (taskMapper.voidTask(
        task.getId(), command.expectedVersion(), actor.userId(), now()) != 1) {
      throw conflict("任务状态或版本已变化，不能作废");
    }
    audit(task, "ADMIN_TASK_VOIDED", task.getTaskStatus(), "CANCELLED", command, actor);
    return taskService.detail(task.getId(), actor);
  }

  private Command command(TechnicalDataAdminActionRequest request, TechnicalDataActor actor) {
    if (actor == null || !actor.admin()) throw forbidden("仅管理员可执行该操作");
    if (request == null || !request.getUnknownFields().isEmpty()) {
      throw invalid(request == null ? "请求体不能为空" : "请求包含未知字段");
    }
    int expected = request.getExpectedTaskVersion() == null
        ? -1 : request.getExpectedTaskVersion();
    if (expected < 0) throw invalid("expectedTaskVersion必须大于等于0");
    return new Command(expected, text(request.getReason(), "reason", 500),
        text(request.getRequestId(), "requestId", 128));
  }

  private QuoteTechTask lock(Long taskId, int expectedVersion) {
    QuoteTechTask task = taskMapper.selectByIdForUpdate(positive(taskId, "taskId"));
    if (task == null) throw notFound("技术资料任务不存在");
    if (!Objects.equals(task.getTaskVersion(), expectedVersion)) {
      throw conflict("任务已被其他会话修改；当前版本=" + task.getTaskVersion());
    }
    if (!Integer.valueOf(1).equals(task.getActiveFlag())) throw conflict("历史任务不可操作");
    return task;
  }

  private SysUser activeUser(Long userId) {
    SysUser user = userService.getById(userId);
    if (user == null || !"0".equals(user.getStatus())
        || (StringUtils.hasText(user.getDelFlag()) && !"0".equals(user.getDelFlag()))) {
      throw invalid("用户不存在或已停用：" + userId);
    }
    return user;
  }

  private void audit(
      QuoteTechTask task, String event, String before, String after,
      Command command, TechnicalDataActor actor) {
    auditLog.record(
        task, null, null, event, before, after, command.reason(), actor,
        command.requestId(), "TD_ADMIN:" + event + ":" + task.getId() + ":" + command.requestId());
  }

  private String displayName(SysUser user) {
    return StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
  }

  private String assignment(Long assigneeId, String assigneeName, Long reviewerId, String reviewerName) {
    return "assignee=" + assigneeId + "/" + assigneeName
        + ",reviewer=" + reviewerId + "/" + reviewerName;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
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

  private record Command(int expectedVersion, String reason, String requestId) {}
}
