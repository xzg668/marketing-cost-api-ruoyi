package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskRequest;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse.BomSupplementRejectedRow;
import com.sanhua.marketingcost.dto.ingest.BomSupplementBatchOaTaskResponse.BomSupplementTaskResult;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTaskQuoteLink;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class BomSupplementTaskServiceImpl implements BomSupplementTaskService {
  private static final String SCOPE_PRODUCT_BOM = "PRODUCT_BOM";
  private static final String STATUS_TODO_PUSHED = "TODO_PUSHED";
  private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final BomSupplementTaskMapper taskMapper;
  private final BomSupplementTaskQuoteLinkMapper linkMapper;
  private final BomSupplementTodoMapper todoMapper;

  public BomSupplementTaskServiceImpl(
      QuoteBomStatusMapper quoteBomStatusMapper,
      OaFormItemMapper oaFormItemMapper,
      BomSupplementTaskMapper taskMapper,
      BomSupplementTaskQuoteLinkMapper linkMapper,
      BomSupplementTodoMapper todoMapper) {
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.taskMapper = taskMapper;
    this.linkMapper = linkMapper;
    this.todoMapper = todoMapper;
  }

  @Override
  @Transactional
  public BomSupplementBatchOaTaskResponse batchCreateAndMockPush(
      BomSupplementBatchOaTaskRequest request) {
    List<Long> statusIds = normalizeIds(request == null ? null : request.getQuoteBomStatusIds());
    if (statusIds.isEmpty()) {
      throw new QuoteIngestException("请选择需要发起 OA 任务的无 BOM 产品行");
    }
    List<QuoteBomStatus> statuses =
        quoteBomStatusMapper.selectList(
            Wrappers.lambdaQuery(QuoteBomStatus.class).in(QuoteBomStatus::getId, statusIds));
    Map<Long, QuoteBomStatus> statusById = new LinkedHashMap<>();
    for (QuoteBomStatus status : statuses) {
      statusById.put(status.getId(), status);
    }

    BomSupplementBatchOaTaskResponse response = new BomSupplementBatchOaTaskResponse();
    response.setRequestedCount(statusIds.size());
    for (Long statusId : statusIds) {
      QuoteBomStatus status = statusById.get(statusId);
      if (status == null) {
        reject(response, statusId, null, "BOM 状态记录不存在");
        continue;
      }
      handleOneStatus(request, response, status);
    }
    response.setRejectedCount(response.getRejectedRows().size());
    return response;
  }

  private void handleOneStatus(
      BomSupplementBatchOaTaskRequest request,
      BomSupplementBatchOaTaskResponse response,
      QuoteBomStatus status) {
    if (!QuoteBomStatusCode.NO_BOM.getCode().equals(status.getBomStatus())) {
      reject(response, status.getId(), status.getProductCode(), "只有无 BOM 产品行允许发起 OA 技术补录任务");
      return;
    }
    String productCode = trimToNull(status.getProductCode());
    if (productCode == null) {
      reject(response, status.getId(), null, "产品料号为空，不能自动发起 OA 技术补录任务");
      return;
    }
    String technicianName = trimToNull(status.getTechnicianName());
    if (technicianName == null) {
      reject(response, status.getId(), productCode, "技术员为空，不能自动发起 OA 技术补录任务");
      return;
    }

    OaFormItem item = oaFormItemMapper.selectById(status.getOaFormItemId());
    BomSupplementTask task = findActiveTask(status, item);
    boolean reused = task != null;
    if (task == null) {
      task = createTask(request, status, item, technicianName);
      taskMapper.insert(task);
      response.setCreatedTaskCount(response.getCreatedTaskCount() + 1);
    } else {
      response.setReusedTaskCount(response.getReusedTaskCount() + 1);
    }
    ensureLink(task, status);
    BomSupplementTodo todo = ensureTechnicianTodo(task, status, technicianName);
    ensureQuoteOwnerNotice(task, status);
    updateQuoteStatus(status, task);
    response.setPushedTodoCount(response.getPushedTodoCount() + 1);
    response.getTasks().add(toTaskResult(task, status, reused, todo));
  }

  private BomSupplementTask createTask(
      BomSupplementBatchOaTaskRequest request,
      QuoteBomStatus status,
      OaFormItem item,
      String technicianName) {
    LocalDateTime now = LocalDateTime.now();
    BomSupplementTask task = new BomSupplementTask();
    task.setTaskNo(nextTaskNo(now, status.getId()));
    task.setBusinessUnitType(item == null ? null : trimToNull(item.getBusinessUnitType()));
    task.setProductCode(trimToNull(status.getProductCode()));
    task.setProductName(item == null ? null : trimToNull(item.getProductName()));
    task.setProductModel(trimToNull(status.getProductModel()));
    task.setCustomerCode(trimToNull(status.getCustomerCode()));
    task.setPackageType(trimToNull(status.getPackageType()));
    task.setPackageMethod(trimToNull(status.getPackageMethod()));
    // T15 先只支持产品主体 BOM 缺失；包装 BOM / 报价组合 BOM 等字段在后续补录闭环任务扩展。
    task.setMissingBomScope(SCOPE_PRODUCT_BOM);
    task.setMissingReason("PRODUCT_BOM_NOT_FOUND");
    task.setTaskStatus(STATUS_TODO_PUSHED);
    task.setTechnicianName(technicianName);
    task.setDueAt(request == null ? null : request.getDueAt());
    task.setRemark(trimToNull(request == null ? null : request.getRemark()));
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private BomSupplementTask findActiveTask(QuoteBomStatus status, OaFormItem item) {
    String businessUnitType = item == null ? null : trimToNull(item.getBusinessUnitType());
    var query =
        Wrappers.lambdaQuery(BomSupplementTask.class)
            .eq(BomSupplementTask::getMissingBomScope, SCOPE_PRODUCT_BOM)
            .eq(BomSupplementTask::getProductCode, trimToNull(status.getProductCode()))
            .in(
                BomSupplementTask::getTaskStatus,
                List.of("TODO_PENDING", STATUS_TODO_PUSHED, "IN_PROGRESS", "FINANCE_REVIEW"));
    if (businessUnitType == null) {
      query.isNull(BomSupplementTask::getBusinessUnitType);
    } else {
      query.eq(BomSupplementTask::getBusinessUnitType, businessUnitType);
    }
    return taskMapper.selectOne(query.last("LIMIT 1"));
  }

  private void ensureLink(BomSupplementTask task, QuoteBomStatus status) {
    BomSupplementTaskQuoteLink existing =
        linkMapper.selectOne(
            Wrappers.lambdaQuery(BomSupplementTaskQuoteLink.class)
                .eq(BomSupplementTaskQuoteLink::getQuoteBomStatusId, status.getId())
                .last("LIMIT 1"));
    if (existing != null) {
      return;
    }
    BomSupplementTaskQuoteLink link = new BomSupplementTaskQuoteLink();
    link.setTaskId(task.getId());
    link.setTaskNo(task.getTaskNo());
    link.setQuoteBomStatusId(status.getId());
    link.setOaFormId(status.getOaFormId());
    link.setOaFormItemId(status.getOaFormItemId());
    link.setOaNo(status.getOaNo());
    link.setProductCode(status.getProductCode());
    link.setCreatedAt(LocalDateTime.now());
    linkMapper.insert(link);
  }

  private BomSupplementTodo ensureTechnicianTodo(
      BomSupplementTask task, QuoteBomStatus status, String technicianName) {
    BomSupplementTodo existing =
        todoMapper.selectOne(
            Wrappers.lambdaQuery(BomSupplementTodo.class)
                .eq(BomSupplementTodo::getTaskId, task.getId())
                .eq(BomSupplementTodo::getRecipientRole, "TECHNICIAN")
                .eq(BomSupplementTodo::getTodoKind, "TODO")
                .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    BomSupplementTodo todo = baseTodo(task, status);
    todo.setTodoNo("MOCK-OA-TODO-" + task.getTaskNo());
    todo.setTodoKind("TODO");
    todo.setRecipientRole("TECHNICIAN");
    todo.setAssigneeName(technicianName);
    todo.setTitle("请补录产品 " + status.getProductCode() + " 的报价 BOM");
    todoMapper.insert(todo);
    return todo;
  }

  private void ensureQuoteOwnerNotice(BomSupplementTask task, QuoteBomStatus status) {
    BomSupplementTodo existing =
        todoMapper.selectOne(
            Wrappers.lambdaQuery(BomSupplementTodo.class)
                .eq(BomSupplementTodo::getTaskId, task.getId())
                .eq(BomSupplementTodo::getRecipientRole, "QUOTE_OWNER")
                .eq(BomSupplementTodo::getTodoKind, "NOTICE")
                .last("LIMIT 1"));
    if (existing != null) {
      return;
    }
    BomSupplementTodo todo = baseTodo(task, status);
    todo.setTodoNo("MOCK-OA-NOTICE-" + task.getTaskNo());
    todo.setTodoKind("NOTICE");
    todo.setRecipientRole("QUOTE_OWNER");
    todo.setAssigneeName(null);
    todo.setTitle("产品 " + status.getProductCode() + " 的 BOM 补录任务已发起");
    todoMapper.insert(todo);
  }

  private BomSupplementTodo baseTodo(BomSupplementTask task, QuoteBomStatus status) {
    LocalDateTime now = LocalDateTime.now();
    BomSupplementTodo todo = new BomSupplementTodo();
    todo.setTaskId(task.getId());
    todo.setTaskNo(task.getTaskNo());
    todo.setTodoStatus("MOCK_PUSHED");
    todo.setTodoUrl("/collaborate/bom-supplement?taskNo=" + task.getTaskNo());
    todo.setPayloadJson(
        "{\"oaNo\":\"" + safeJson(status.getOaNo()) + "\",\"productCode\":\""
            + safeJson(status.getProductCode()) + "\"}");
    todo.setPushedAt(now);
    todo.setCreatedAt(now);
    todo.setUpdatedAt(now);
    return todo;
  }

  private void updateQuoteStatus(QuoteBomStatus status, BomSupplementTask task) {
    status.setBomStatus(QuoteBomStatusCode.ENTRY_IN_PROGRESS.getCode());
    status.setManualTaskNo(task.getTaskNo());
    status.setSupplementTaskId(task.getId());
    status.setLockOwner(task.getTaskNo());
    status.setCheckedAt(LocalDateTime.now());
    status.setUpdatedAt(LocalDateTime.now());
    quoteBomStatusMapper.updateById(status);
  }

  private BomSupplementTaskResult toTaskResult(
      BomSupplementTask task, QuoteBomStatus status, boolean reused, BomSupplementTodo todo) {
    BomSupplementTaskResult result = new BomSupplementTaskResult();
    result.setTaskId(task.getId());
    result.setTaskNo(task.getTaskNo());
    result.setProductCode(status.getProductCode());
    result.setTechnicianName(task.getTechnicianName());
    result.setTaskStatus(task.getTaskStatus());
    result.setReused(reused);
    result.setTodoNo(todo == null ? null : todo.getTodoNo());
    return result;
  }

  private void reject(
      BomSupplementBatchOaTaskResponse response, Long quoteBomStatusId, String productCode, String reason) {
    BomSupplementRejectedRow row = new BomSupplementRejectedRow();
    row.setQuoteBomStatusId(quoteBomStatusId);
    row.setProductCode(productCode);
    row.setReason(reason);
    response.getRejectedRows().add(row);
  }

  private List<Long> normalizeIds(List<Long> ids) {
    if (ids == null) {
      return List.of();
    }
    Set<Long> normalized = new LinkedHashSet<>();
    for (Long id : ids) {
      if (id != null && id > 0) {
        normalized.add(id);
      }
    }
    return new ArrayList<>(normalized);
  }

  private String nextTaskNo(LocalDateTime now, Long seed) {
    return "BST-" + TASK_NO_DATE.format(now) + "-" + String.format("%04d", seed == null ? 0 : seed);
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String safeJson(String value) {
    return StringUtils.hasText(value) ? value.replace("\\", "\\\\").replace("\"", "\\\"") : "";
  }
}
