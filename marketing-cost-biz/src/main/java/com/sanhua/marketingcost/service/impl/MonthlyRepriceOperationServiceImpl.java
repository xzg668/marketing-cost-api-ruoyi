package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceOperationService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceOperationServiceImpl implements MonthlyRepriceOperationService {

  private static final String ROLE_BU_DIRECTOR = "BU_DIRECTOR";
  private static final String PERM_OPERATE = "price:monthly-reprice:operate";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_CANCELLED = "CANCELLED";
  private static final String STATUS_FAILED = "FAILED";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final MonthlyRepriceProgressService progressService;
  private final PermissionService permissionService;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceOperationServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      MonthlyRepriceProgressService progressService,
      PermissionService permissionService,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.auditLogMapper = auditLogMapper;
    this.progressService = progressService;
    this.permissionService = permissionService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceProgressSnapshot cancel(String repriceNo, String operator) {
    assertBusinessDirector("只有业务总监可以取消月度调价");
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    String status = normalize(batch.getStatus());
    if (STATUS_CONFIRMED.equals(status)) {
      throw new IllegalArgumentException("已确认的月度调价批次不能取消");
    }
    if (STATUS_CANCELLED.equals(status)) {
      return progressService.getProgress(normalizedRepriceNo);
    }

    LocalDateTime now = LocalDateTime.now();
    int cancelledTasks = taskMapper.cancelMonthlyRepriceOpenTasks(normalizedRepriceNo, now);
    int updated = batchMapper.cancelBatch(normalizedRepriceNo, now);
    if (updated != 1) {
      throw new IllegalStateException("月度调价批次状态已变化，取消失败");
    }
    auditLogMapper.insert(buildAuditLog(
        batch,
        operator,
        "CANCEL_BATCH",
        "取消月度调价批次",
        "CANCELLED",
        "取消未确认批次，停止任务 " + cancelledTasks));
    return progressService.getProgress(normalizedRepriceNo);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceProgressSnapshot retryFailed(String repriceNo, String operator) {
    assertBusinessDirector("只有业务总监可以重试月度调价失败任务");
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    String status = normalize(batch.getStatus());
    if (STATUS_CONFIRMED.equals(status)) {
      throw new IllegalArgumentException("已确认的月度调价批次不能重试");
    }
    if (STATUS_CANCELLED.equals(status)) {
      throw new IllegalArgumentException("已取消的月度调价批次不能重试");
    }
    if (!STATUS_FAILED.equals(status)) {
      throw new IllegalArgumentException("只有 FAILED 批次可以重试失败任务");
    }

    LocalDateTime now = LocalDateTime.now();
    int retriedTasks = taskMapper.retryMonthlyRepriceFailedTasks(normalizedRepriceNo, now);
    if (retriedTasks <= 0) {
      throw new IllegalArgumentException("当前批次没有可重试的失败任务");
    }
    int updated = batchMapper.retryFailedBatch(normalizedRepriceNo, now);
    if (updated != 1) {
      throw new IllegalStateException("月度调价批次状态已变化，重试失败");
    }
    auditLogMapper.insert(buildAuditLog(
        batch,
        operator,
        "RETRY_FAILED_TASKS",
        "重试月度调价失败任务",
        "RUNNING",
        "重试失败任务 " + retriedTasks));
    return progressService.getProgress(normalizedRepriceNo);
  }

  private MonthlyRepriceBatch findBatch(String repriceNo) {
    // 取消/重试会改变批次可写状态，必须和 Worker 结果写入串行化。
    MonthlyRepriceBatch batch = batchMapper.selectByRepriceNoForUpdate(repriceNo);
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    return batch;
  }

  private MonthlyRepriceAuditLog buildAuditLog(
      MonthlyRepriceBatch batch,
      String operator,
      String operationType,
      String operationName,
      String afterStatus,
      String summary) {
    String normalizedOperator = normalizeOperator(operator);
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(batch.getRepriceNo());
    log.setPricingMonth(batch.getPricingMonth());
    log.setBusinessUnitType(batch.getBusinessUnitType());
    log.setOperationType(operationType);
    log.setOperationName(operationName);
    log.setOperatorId(normalizedOperator);
    log.setOperatorName(normalizedOperator);
    log.setOperatorRole(operatorRole());
    log.setOperationTime(LocalDateTime.now());
    log.setTargetType("MONTHLY_REPRICE_BATCH");
    log.setTargetId(batch.getId() == null ? null : String.valueOf(batch.getId()));
    log.setTargetKey(batch.getRepriceNo());
    log.setBeforeJson(snapshotJson(batch));
    log.setAfterJson(snapshotJson(batch, afterStatus));
    log.setChangeSummary(summary);
    return log;
  }

  private String snapshotJson(MonthlyRepriceBatch batch) {
    return snapshotJson(batch, batch.getStatus());
  }

  private String snapshotJson(MonthlyRepriceBatch batch, String status) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", batch.getRepriceNo());
    snapshot.put("pricingMonth", batch.getPricingMonth());
    snapshot.put("businessUnitType", batch.getBusinessUnitType());
    snapshot.put("status", status);
    snapshot.put("successCount", batch.getSuccessCount());
    snapshot.put("failedCount", batch.getFailedCount());
    snapshot.put("skippedCount", batch.getSkippedCount());
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价操作审计快照序列化失败", ex);
    }
  }

  private void assertBusinessDirector(String message) {
    if (BusinessUnitContext.isAdmin()
        || permissionService.hasRole(ROLE_BU_DIRECTOR)
        || permissionService.hasPermi(PERM_OPERATE)) {
      return;
    }
    throw new AccessDeniedException(message);
  }

  private String operatorRole() {
    if (BusinessUnitContext.isAdmin()) {
      return "ADMIN";
    }
    if (permissionService.hasRole(ROLE_BU_DIRECTOR)) {
      return ROLE_BU_DIRECTOR;
    }
    if (permissionService.hasPermi(PERM_OPERATE)) {
      return "MONTHLY_REPRICE_OPERATOR";
    }
    return null;
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalizeOperator(String operator) {
    String normalized = normalize(operator);
    return StringUtils.hasText(normalized) ? normalized : "system";
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
