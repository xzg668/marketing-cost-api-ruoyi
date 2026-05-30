package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceAuditServiceImpl implements MonthlyRepriceAuditService {

  private static final String TARGET_BATCH = "MONTHLY_REPRICE_BATCH";
  private static final String TARGET_OA = "OA_COST_RUN";

  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceAuditServiceImpl(
      MonthlyRepriceAuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
    this.auditLogMapper = auditLogMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public void recordStartCalc(MonthlyRepriceBatch batch, String operator, String changeSummary) {
    if (batch == null) {
      return;
    }
    // 开始计算只按批次记录一次，不按每个任务记录，避免大批量月调时审计日志爆量。
    MonthlyRepriceAuditLog log = batchLog(batch, "START_CALC", "开始月度调价成本核算", operator);
    log.setBeforeJson(batchSnapshot(batch));
    log.setAfterJson(batchSnapshot(batch, "RUNNING"));
    log.setChangeSummary(firstText(changeSummary, "月度调价批次进入成本核算阶段"));
    auditLogMapper.insert(log);
  }

  @Override
  public void recordCalcCompleted(MonthlyRepriceBatch before, MonthlyRepriceProgressSnapshot after) {
    if (before == null || after == null) {
      return;
    }
    MonthlyRepriceAuditLog log =
        batchLog(before, "CALC_COMPLETED", "月度调价成本核算完成", "system");
    log.setBeforeJson(batchSnapshot(before));
    log.setAfterJson(progressSnapshot(after));
    log.setChangeSummary("月度调价成本核算完成，成功 " + after.getSuccessCount()
        + "，失败 " + after.getFailedCount()
        + "，结果 " + after.getResultCount());
    auditLogMapper.insert(log);
  }

  @Override
  public void recordCalcFailed(MonthlyRepriceBatch before, MonthlyRepriceProgressSnapshot after) {
    if (before == null || after == null) {
      return;
    }
    MonthlyRepriceAuditLog log =
        batchLog(before, "CALC_FAILED", "月度调价成本核算失败", "system");
    log.setBeforeJson(batchSnapshot(before));
    log.setAfterJson(progressSnapshot(after));
    log.setChangeSummary("月度调价成本核算收口失败，成功 " + after.getSuccessCount()
        + "，失败 " + after.getFailedCount()
        + "，结果 " + after.getResultCount());
    auditLogMapper.insert(log);
  }

  @Override
  public void recordOaCostRunBlocked(MonthlyRepriceBatch batch, String oaNo, String operator) {
    if (batch == null) {
      return;
    }
    MonthlyRepriceAuditLog log =
        batchLog(batch, "BLOCK_OA_COST_RUN", "拦截普通 OA 成本核算", operator);
    log.setTargetType(TARGET_OA);
    log.setTargetId(null);
    log.setTargetKey(normalize(oaNo));
    log.setBeforeJson(batchSnapshot(batch));
    log.setAfterJson(blockSnapshot(batch, oaNo));
    log.setChangeSummary("月度调价期间拦截普通 OA 成本核算：" + normalize(oaNo));
    auditLogMapper.insert(log);
  }

  private MonthlyRepriceAuditLog batchLog(
      MonthlyRepriceBatch batch, String operationType, String operationName, String operator) {
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(batch.getRepriceNo());
    log.setPricingMonth(batch.getPricingMonth());
    log.setBusinessUnitType(batch.getBusinessUnitType());
    log.setOperationType(operationType);
    log.setOperationName(operationName);
    log.setOperatorId(normalizeOperator(operator));
    log.setOperatorName(normalizeOperator(operator));
    log.setOperationTime(LocalDateTime.now());
    log.setTargetType(TARGET_BATCH);
    log.setTargetId(batch.getId() == null ? null : String.valueOf(batch.getId()));
    log.setTargetKey(batch.getRepriceNo());
    return log;
  }

  private String batchSnapshot(MonthlyRepriceBatch batch) {
    return batchSnapshot(batch, batch.getStatus());
  }

  private String batchSnapshot(MonthlyRepriceBatch batch, String status) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", batch.getRepriceNo());
    snapshot.put("pricingMonth", batch.getPricingMonth());
    snapshot.put("businessUnitType", batch.getBusinessUnitType());
    snapshot.put("status", status);
    snapshot.put("totalCount", batch.getTotalCount());
    snapshot.put("successCount", batch.getSuccessCount());
    snapshot.put("failedCount", batch.getFailedCount());
    snapshot.put("skippedCount", batch.getSkippedCount());
    snapshot.put("startedAt", timeString(batch.getStartedAt()));
    snapshot.put("finishedAt", timeString(batch.getFinishedAt()));
    return writeJson(snapshot);
  }

  private String progressSnapshot(MonthlyRepriceProgressSnapshot progress) {
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
    snapshot.put("progressPercent", progress.getProgressPercent());
    snapshot.put("finishedAt", timeString(progress.getFinishedAt()));
    return writeJson(snapshot);
  }

  private String blockSnapshot(MonthlyRepriceBatch batch, String oaNo) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("oaNo", normalize(oaNo));
    snapshot.put("blocked", true);
    snapshot.put("reason", "MONTHLY_REPRICE_LOCK");
    snapshot.put("repriceNo", batch.getRepriceNo());
    snapshot.put("pricingMonth", batch.getPricingMonth());
    snapshot.put("businessUnitType", batch.getBusinessUnitType());
    snapshot.put("batchStatus", batch.getStatus());
    return writeJson(snapshot);
  }

  private String writeJson(Map<String, Object> snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价审计快照序列化失败", ex);
    }
  }

  private String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first.trim() : second;
  }

  private String timeString(LocalDateTime time) {
    return time == null ? null : time.toString();
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
