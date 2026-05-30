package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.OaTodoCallbackRequest;
import com.sanhua.marketingcost.dto.quotebom.OaTodoPushResponse;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.integration.oa.OaTodoGateway;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.service.OaTodoPushService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OaTodoPushServiceImpl implements OaTodoPushService {

  private static final String RECIPIENT_TECHNICIAN = "TECHNICIAN";
  private static final String KIND_TODO = "TODO";
  private static final String STATUS_NOT_PUSHED = "NOT_PUSHED";
  private static final String STATUS_PUSHED = "PUSHED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_CLOSED = "CLOSED";
  private static final String STATUS_DONE = "DONE";

  private final BomSupplementTaskMapper taskMapper;
  private final BomSupplementTodoMapper todoMapper;
  private final OaTodoGateway gateway;

  public OaTodoPushServiceImpl(
      BomSupplementTaskMapper taskMapper,
      BomSupplementTodoMapper todoMapper,
      OaTodoGateway gateway) {
    this.taskMapper = taskMapper;
    this.todoMapper = todoMapper;
    this.gateway = gateway;
  }

  @Override
  @Transactional
  public OaTodoPushResponse pushBomSupplementTask(Long taskId) {
    BomSupplementTask task = loadTask(taskId);
    BomSupplementTodo todo = loadOrCreateTodo(task);
    LocalDateTime now = LocalDateTime.now();
    OaTodoGateway.PushResult result =
        gateway.push(
            new OaTodoGateway.PushRequest(
                task.getId(),
                task.getTaskNo(),
                todo.getTitle(),
                todo.getAssigneeName(),
                todo.getTodoUrl(),
                todo.getPayloadJson()));
    todo.setLastPushAt(now);
    todo.setPushedAt(now);
    todo.setUpdatedAt(now);
    if (result.success()) {
      todo.setOaTodoId(result.oaTodoId());
      todo.setOaTodoUrl(firstText(result.oaTodoUrl(), todo.getTodoUrl()));
      todo.setTodoNo(firstText(result.oaTodoId(), todo.getTodoNo()));
      todo.setPushStatus(STATUS_PUSHED);
      todo.setTodoStatus(STATUS_PUSHED);
      todo.setPushErrorMessage(null);
      if (STATUS_NOT_PUSHED.equals(task.getTaskStatus()) || "TODO_PENDING".equals(task.getTaskStatus())) {
        task.setTaskStatus("TODO_PUSHED");
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
      }
    } else {
      todo.setPushStatus(STATUS_FAILED);
      todo.setTodoStatus(STATUS_FAILED);
      todo.setPushErrorMessage(firstText(result.errorMessage(), "OA 待办推送失败"));
    }
    todoMapper.updateById(todo);
    return toResponse(task, todo);
  }

  @Override
  @Transactional
  public OaTodoPushResponse queryTodoStatus(Long taskId) {
    BomSupplementTask task = loadTask(taskId);
    BomSupplementTodo todo = loadTodo(task.getId());
    if (todo == null) {
      throw new QuoteIngestException("任务尚未生成 OA 待办记录");
    }
    if (!StringUtils.hasText(todo.getOaTodoId())) {
      return toResponse(task, todo);
    }
    OaTodoGateway.StatusResult result = gateway.query(todo.getOaTodoId());
    applyStatusResult(todo, result, false);
    todoMapper.updateById(todo);
    return toResponse(task, todo);
  }

  @Override
  @Transactional
  public OaTodoPushResponse closeTodo(Long taskId) {
    BomSupplementTask task = loadTask(taskId);
    BomSupplementTodo todo = loadTodo(task.getId());
    if (todo == null) {
      throw new QuoteIngestException("任务尚未生成 OA 待办记录");
    }
    OaTodoGateway.StatusResult result = gateway.close(firstText(todo.getOaTodoId(), todo.getTodoNo()));
    applyStatusResult(todo, result, true);
    todoMapper.updateById(todo);
    return toResponse(task, todo);
  }

  @Override
  @Transactional
  public OaTodoPushResponse handleCallback(OaTodoCallbackRequest request) {
    if (request == null || (!StringUtils.hasText(request.oaTodoId()) && request.taskId() == null)) {
      throw new QuoteIngestException("OA 回调缺少待办 ID 或任务 ID");
    }
    BomSupplementTodo todo =
        StringUtils.hasText(request.oaTodoId())
            ? todoMapper.selectOne(
                Wrappers.<BomSupplementTodo>lambdaQuery()
                    .eq(BomSupplementTodo::getOaTodoId, request.oaTodoId())
                    .last("LIMIT 1"))
            : loadTodo(request.taskId());
    if (todo == null) {
      throw new QuoteIngestException("OA 回调对应的待办记录不存在");
    }
    BomSupplementTask task = loadTask(todo.getTaskId());
    String status = normalizeCallbackStatus(request.status());
    todo.setPushStatus(status);
    todo.setTodoStatus(status);
    todo.setPushErrorMessage(null);
    if (StringUtils.hasText(request.oaTodoId())) {
      todo.setOaTodoId(request.oaTodoId().trim());
      todo.setTodoNo(request.oaTodoId().trim());
    }
    if (STATUS_DONE.equals(status) || STATUS_CLOSED.equals(status)) {
      todo.setClosedAt(LocalDateTime.now());
    }
    todo.setUpdatedAt(LocalDateTime.now());
    todoMapper.updateById(todo);
    return toResponse(task, todo);
  }

  private void applyStatusResult(BomSupplementTodo todo, OaTodoGateway.StatusResult result, boolean closing) {
    todo.setUpdatedAt(LocalDateTime.now());
    if (result.success()) {
      String status = closing ? STATUS_CLOSED : normalizeCallbackStatus(result.status());
      todo.setPushStatus(status);
      todo.setTodoStatus(status);
      todo.setOaTodoId(firstText(result.oaTodoId(), todo.getOaTodoId()));
      todo.setOaTodoUrl(firstText(result.oaTodoUrl(), todo.getOaTodoUrl()));
      todo.setPushErrorMessage(null);
      if (closing || STATUS_CLOSED.equals(status) || STATUS_DONE.equals(status)) {
        todo.setClosedAt(LocalDateTime.now());
      }
      return;
    }
    todo.setPushStatus(STATUS_FAILED);
    todo.setTodoStatus(STATUS_FAILED);
    todo.setPushErrorMessage(firstText(result.errorMessage(), "OA 待办状态同步失败"));
  }

  private BomSupplementTask loadTask(Long taskId) {
    if (taskId == null || taskId <= 0) {
      throw new QuoteIngestException("任务 ID 不能为空");
    }
    BomSupplementTask task = taskMapper.selectById(taskId);
    if (task == null) {
      throw new QuoteIngestException("BOM 补录任务不存在: " + taskId);
    }
    return task;
  }

  private BomSupplementTodo loadOrCreateTodo(BomSupplementTask task) {
    BomSupplementTodo todo = loadTodo(task.getId());
    if (todo != null) {
      return todo;
    }
    LocalDateTime now = LocalDateTime.now();
    todo = new BomSupplementTodo();
    todo.setTaskId(task.getId());
    todo.setTaskNo(task.getTaskNo());
    todo.setTodoNo("PENDING-" + task.getTaskNo());
    todo.setTodoStatus(STATUS_NOT_PUSHED);
    todo.setPushStatus(STATUS_NOT_PUSHED);
    todo.setTodoKind(KIND_TODO);
    todo.setRecipientRole(RECIPIENT_TECHNICIAN);
    todo.setAssigneeName(task.getTechnicianName());
    todo.setTitle("请处理报价产品 " + task.getProductCode() + " 的 BOM 准备任务");
    todo.setCreatedAt(now);
    todo.setUpdatedAt(now);
    todoMapper.insert(todo);
    return todo;
  }

  private BomSupplementTodo loadTodo(Long taskId) {
    return todoMapper.selectOne(
        Wrappers.<BomSupplementTodo>lambdaQuery()
            .eq(BomSupplementTodo::getTaskId, taskId)
            .eq(BomSupplementTodo::getRecipientRole, RECIPIENT_TECHNICIAN)
            .eq(BomSupplementTodo::getTodoKind, KIND_TODO)
            .last("LIMIT 1"));
  }

  private String normalizeCallbackStatus(String status) {
    String value = trimToNull(status);
    if (value == null) {
      return STATUS_PUSHED;
    }
    return switch (value) {
      case "DONE", "FINISHED", "COMPLETE", "COMPLETED" -> STATUS_DONE;
      case "CLOSE", "CLOSED", "CANCELLED", "CANCELED" -> STATUS_CLOSED;
      case "FAILED", "ERROR" -> STATUS_FAILED;
      default -> STATUS_PUSHED;
    };
  }

  private OaTodoPushResponse toResponse(BomSupplementTask task, BomSupplementTodo todo) {
    return new OaTodoPushResponse(
        task.getId(),
        task.getTaskNo(),
        firstText(todo.getOaTodoId(), todo.getTodoNo()),
        firstText(todo.getOaTodoUrl(), todo.getTodoUrl()),
        firstText(todo.getPushStatus(), todo.getTodoStatus()),
        todo.getPushErrorMessage(),
        firstDate(todo.getLastPushAt(), todo.getPushedAt()));
  }

  private LocalDateTime firstDate(LocalDateTime first, LocalDateTime second) {
    return first == null ? second : first;
  }

  private String firstText(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
