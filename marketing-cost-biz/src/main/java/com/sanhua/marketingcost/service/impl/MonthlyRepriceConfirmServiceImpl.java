package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceConfirmService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceConfirmServiceImpl implements MonthlyRepriceConfirmService {

  private static final String ROLE_BU_DIRECTOR = "BU_DIRECTOR";
  private static final String PERM_OPERATE = "price:monthly-reprice:operate";
  private static final String STATUS_WAIT_CONFIRM = "WAIT_CONFIRM";
  private static final String STATUS_CONFIRMED = "CONFIRMED";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MonthlyRepriceResultMapper resultMapper;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final MonthlyRepriceProgressService progressService;
  private final PermissionService permissionService;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceConfirmServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MonthlyRepriceResultMapper resultMapper,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      MonthlyRepriceProgressService progressService,
      PermissionService permissionService,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
    this.auditLogMapper = auditLogMapper;
    this.progressService = progressService;
    this.permissionService = permissionService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceProgressSnapshot confirm(String repriceNo, String operator) {
    assertBusinessDirector();
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch before = findBatch(normalizedRepriceNo);
    if (STATUS_CONFIRMED.equals(normalize(before.getStatus()))) {
      throw new IllegalArgumentException("已确认的月度调价批次不能再次修改");
    }
    if (!STATUS_WAIT_CONFIRM.equals(normalize(before.getStatus()))) {
      throw new IllegalArgumentException("只有 WAIT_CONFIRM 批次可以确认");
    }

    MonthlyRepriceProgressSnapshot progress = progressService.refreshProgress(normalizedRepriceNo);
    validateBatchProgress(progress);
    progress.setResultCount(validateResultConsistency(normalizedRepriceNo, progress.getSuccessCount()));

    String normalizedOperator = normalizeOperator(operator);
    LocalDateTime confirmedAt = LocalDateTime.now();
    int updated = batchMapper.confirmBatch(
        before.getId(), normalizedOperator, normalizedOperator, confirmedAt);
    if (updated != 1) {
      throw new IllegalStateException("月度调价批次状态已变化，确认失败");
    }

    MonthlyRepriceProgressSnapshot confirmed = confirmedSnapshot(progress, confirmedAt);
    auditLogMapper.insert(buildAuditLog(before, confirmed, normalizedOperator));
    return confirmed;
  }

  private void validateBatchProgress(MonthlyRepriceProgressSnapshot progress) {
    if (progress == null || !STATUS_WAIT_CONFIRM.equals(normalize(progress.getStatus()))) {
      throw new IllegalArgumentException("批次尚未完成，不能确认");
    }
    if (progress.getTotalCount() <= 0) {
      throw new IllegalArgumentException("月度调价批次没有可确认结果");
    }
    if (progress.getFailedCount() != 0) {
      throw new IllegalArgumentException("失败数不为 0，不能确认");
    }
    if (progress.getSuccessCount() != progress.getTotalCount()) {
      throw new IllegalArgumentException("成功数不等于总数，不能确认");
    }
  }

  private int validateResultConsistency(String repriceNo, int successCount) {
    long resultCount = resultMapper.countByRepriceNo(repriceNo);
    if (resultCount != successCount) {
      throw new IllegalArgumentException("月度调价结果数量不等于成功任务数");
    }
    if (resultMapper.countDuplicateCalcObjectKeys(repriceNo) > 0) {
      throw new IllegalArgumentException("月度调价结果存在重复 calcObjectKey");
    }
    if (taskMapper.countMonthlyRepriceSuccessfulTasksMissingResult(repriceNo) > 0) {
      throw new IllegalArgumentException("存在成功任务缺少月度调价结果");
    }
    if (resultMapper.countResultsWithoutSuccessfulTask(repriceNo) > 0) {
      throw new IllegalArgumentException("存在未关联成功任务的月度调价结果");
    }
    if (resultMapper.countResultsMissingPartItems(repriceNo) > 0) {
      throw new IllegalArgumentException("存在月度调价结果缺少部品明细");
    }
    if (resultMapper.countResultsMissingCostItems(repriceNo) > 0) {
      throw new IllegalArgumentException("存在月度调价结果缺少成本项明细");
    }
    return safeLong(resultCount);
  }

  private MonthlyRepriceBatch findBatch(String repriceNo) {
    // 确认发布是批次状态的最终闸口；这里锁住批次行，避免 Worker 同时覆盖月度结果。
    MonthlyRepriceBatch batch = batchMapper.selectByRepriceNoForUpdate(repriceNo);
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    return batch;
  }

  private MonthlyRepriceProgressSnapshot confirmedSnapshot(
      MonthlyRepriceProgressSnapshot progress, LocalDateTime confirmedAt) {
    progress.setStatus(STATUS_CONFIRMED);
    progress.setConfirmedAt(confirmedAt);
    progress.setProgressPercent(100);
    return progress;
  }

  private MonthlyRepriceAuditLog buildAuditLog(
      MonthlyRepriceBatch before,
      MonthlyRepriceProgressSnapshot confirmed,
      String operator) {
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(before.getRepriceNo());
    log.setPricingMonth(before.getPricingMonth());
    log.setBusinessUnitType(before.getBusinessUnitType());
    log.setOperationType("CONFIRM_BATCH");
    log.setOperationName("确认月度调价批次");
    log.setOperatorId(operator);
    log.setOperatorName(operator);
    log.setOperatorRole(operatorRole());
    log.setOperationTime(confirmed.getConfirmedAt());
    log.setTargetType("MONTHLY_REPRICE_BATCH");
    log.setTargetId(before.getId() == null ? null : String.valueOf(before.getId()));
    log.setTargetKey(before.getRepriceNo());
    log.setBeforeJson(snapshotJson(before));
    log.setAfterJson(snapshotJson(confirmed));
    log.setChangeSummary("确认月度调价批次，成功 " + confirmed.getSuccessCount()
        + "，失败 " + confirmed.getFailedCount()
        + "，结果 " + confirmed.getResultCount());
    return log;
  }

  private String snapshotJson(MonthlyRepriceBatch batch) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", batch.getRepriceNo());
    snapshot.put("pricingMonth", batch.getPricingMonth());
    snapshot.put("businessUnitType", batch.getBusinessUnitType());
    snapshot.put("status", batch.getStatus());
    snapshot.put("totalCount", batch.getTotalCount());
    snapshot.put("successCount", batch.getSuccessCount());
    snapshot.put("failedCount", batch.getFailedCount());
    snapshot.put("skippedCount", batch.getSkippedCount());
    snapshot.put("confirmedAt", timeString(batch.getConfirmedAt()));
    return writeJson(snapshot);
  }

  private String snapshotJson(MonthlyRepriceProgressSnapshot progress) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", progress.getRepriceNo());
    snapshot.put("pricingMonth", progress.getPricingMonth());
    snapshot.put("businessUnitType", progress.getBusinessUnitType());
    snapshot.put("status", progress.getStatus());
    snapshot.put("totalCount", progress.getTotalCount());
    snapshot.put("successCount", progress.getSuccessCount());
    snapshot.put("failedCount", progress.getFailedCount());
    snapshot.put("skippedCount", progress.getSkippedCount());
    snapshot.put("resultCount", progress.getResultCount());
    snapshot.put("confirmedAt", timeString(progress.getConfirmedAt()));
    return writeJson(snapshot);
  }

  private String timeString(LocalDateTime time) {
    return time == null ? null : time.toString();
  }

  private String writeJson(Map<String, Object> snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价确认审计快照序列化失败", ex);
    }
  }

  private void assertBusinessDirector() {
    if (BusinessUnitContext.isAdmin()
        || permissionService.hasRole(ROLE_BU_DIRECTOR)
        || permissionService.hasPermi(PERM_OPERATE)) {
      return;
    }
    throw new AccessDeniedException("只有业务总监可以确认月度调价");
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

  private int safeLong(long value) {
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }
}
