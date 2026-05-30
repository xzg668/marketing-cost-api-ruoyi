package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceObjectExpandService;
import com.sanhua.marketingcost.service.MonthlyRepriceTaskService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceObjectExpandServiceImpl implements MonthlyRepriceObjectExpandService {

  private static final String OA_CALC_STATUS_DONE = "已核算";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final MonthlyRepriceTaskService taskService;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceObjectExpandServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      OaFormItemMapper oaFormItemMapper,
      MonthlyRepriceTaskService taskService,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.taskService = taskService;
    this.auditLogMapper = auditLogMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceObjectExpandResult expand(String repriceNo, String operator) {
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);

    // T3 范围口径：只看当前业务单元下 oa_form.calc_status = 已核算 的 OA 明细。
    List<MonthlyRepriceCalcObject> calcObjects =
        oaFormItemMapper.selectMonthlyRepriceCalcObjects(
            batch.getBusinessUnitType(), OA_CALC_STATUS_DONE);
    MonthlyRepriceObjectExpandResult result = taskService.createTasks(batch, calcObjects);

    updateBatchCounts(batch, result);
    auditLogMapper.insert(buildAuditLog(batch, result, operator));
    return result;
  }

  private MonthlyRepriceBatch findBatch(String repriceNo) {
    MonthlyRepriceBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
            .eq(MonthlyRepriceBatch::getRepriceNo, repriceNo));
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    return batch;
  }

  private void updateBatchCounts(
      MonthlyRepriceBatch batch, MonthlyRepriceObjectExpandResult result) {
    MonthlyRepriceBatch update = new MonthlyRepriceBatch();
    update.setId(batch.getId());
    update.setTotalCount(result.getTotalCount());
    update.setSuccessCount(0);
    update.setFailedCount(0);
    update.setSkippedCount(result.getSkippedCount());
    update.setUpdatedAt(LocalDateTime.now());
    batchMapper.updateById(update);
  }

  private MonthlyRepriceAuditLog buildAuditLog(
      MonthlyRepriceBatch batch, MonthlyRepriceObjectExpandResult result, String operator) {
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(batch.getRepriceNo());
    log.setPricingMonth(batch.getPricingMonth());
    log.setBusinessUnitType(batch.getBusinessUnitType());
    log.setOperationType("EXPAND_TASKS");
    log.setOperationName("生成月度调价核算任务");
    log.setOperatorId(normalizeOperator(operator));
    log.setOperatorName(normalizeOperator(operator));
    log.setOperationTime(LocalDateTime.now());
    log.setTargetType("MONTHLY_REPRICE_BATCH");
    log.setTargetId(batch.getId() == null ? null : String.valueOf(batch.getId()));
    log.setTargetKey(batch.getRepriceNo());
    log.setAfterJson(afterJson(result));
    log.setChangeSummary("生成月度调价核算任务：总对象 " + result.getTotalCount()
        + "，任务 " + result.getTaskCount()
        + "，跳过 " + result.getSkippedCount());
    return log;
  }

  private String afterJson(MonthlyRepriceObjectExpandResult result) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", result.getRepriceNo());
    snapshot.put("totalCount", result.getTotalCount());
    snapshot.put("taskCount", result.getTaskCount());
    snapshot.put("skippedCount", result.getSkippedCount());
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价任务展开审计快照序列化失败", ex);
    }
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
