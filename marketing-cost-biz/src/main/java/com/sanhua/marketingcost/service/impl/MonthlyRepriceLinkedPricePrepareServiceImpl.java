package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceLinkedPricePrepareResult;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import com.sanhua.marketingcost.service.MonthlyRepriceLinkedPricePrepareService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceLinkedPricePrepareServiceImpl
    implements MonthlyRepriceLinkedPricePrepareService {

  private static final String ADJUST_TYPE_MONTHLY = "MONTHLY";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_FAILED = "FAILED";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final FactorAdjustBatchMapper factorAdjustBatchMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final MonthlyRepriceAuditLogMapper auditLogMapper;
  private final MonthlyRepriceAuditService auditService;
  private final ObjectMapper objectMapper;

  public MonthlyRepriceLinkedPricePrepareServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      FactorAdjustBatchMapper factorAdjustBatchMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      LinkedPriceEnsureService linkedPriceEnsureService,
      MonthlyRepriceAuditLogMapper auditLogMapper,
      MonthlyRepriceAuditService auditService,
      ObjectMapper objectMapper) {
    this.batchMapper = batchMapper;
    this.factorAdjustBatchMapper = factorAdjustBatchMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.auditLogMapper = auditLogMapper;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceLinkedPricePrepareResult prepare(String repriceNo, String operator) {
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    validateAdjustBatchIfPresent(batch);
    Set<String> itemCodes = loadLinkedItemCodes(batch);

    // 月度调价以批次 price_as_of_time 为总口径；影响因素批次只是兼容旧数据的可选覆盖。
    LinkedPriceEnsureResult ensureResult = linkedPriceEnsureService.ensure(
        LinkedPriceEnsureRequest.monthlyAdjust(
            batch.getAdjustBatchId(),
            batch.getBusinessUnitType(),
            batch.getPricingMonth(),
            itemCodes,
            true,
            batch.getPriceAsOfTime()));
    String nextStatus = ensureResult.getFailedCount() > 0 ? STATUS_FAILED : STATUS_RUNNING;
    updateBatchStatus(batch, nextStatus);
    MonthlyRepriceLinkedPricePrepareResult result =
        MonthlyRepriceLinkedPricePrepareResult.of(
            batch.getRepriceNo(),
            batch.getPricingMonth(),
            batch.getAdjustBatchId(),
            ensureResult,
            nextStatus);
    auditLogMapper.insert(buildAuditLog(batch, result, operator));
    if (STATUS_RUNNING.equals(nextStatus)) {
      auditService.recordStartCalc(
          batch,
          operator,
          "联动价准备完成，月度调价批次进入成本核算阶段");
    }
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

  private void validateAdjustBatchIfPresent(MonthlyRepriceBatch batch) {
    if (batch.getAdjustBatchId() == null) {
      return;
    }
    FactorAdjustBatch adjustBatch = factorAdjustBatchMapper.selectById(batch.getAdjustBatchId());
    if (adjustBatch == null || Integer.valueOf(1).equals(adjustBatch.getDeleted())) {
      throw new IllegalArgumentException("影响因素调价批次不存在");
    }
    if (!ADJUST_TYPE_MONTHLY.equals(normalize(adjustBatch.getAdjustType()))) {
      throw new IllegalArgumentException("月度调价只能引用 adjust_type = MONTHLY 的影响因素调价批次");
    }
    if (!normalize(batch.getPricingMonth()).equals(normalize(adjustBatch.getPricingMonth()))) {
      throw new IllegalArgumentException("影响因素调价批次月份与月度调价批次不一致");
    }
    if (!normalize(batch.getBusinessUnitType()).equals(normalize(adjustBatch.getBusinessUnitType()))) {
      throw new IllegalArgumentException("影响因素调价批次业务单元与月度调价批次不一致");
    }
  }

  private Set<String> loadLinkedItemCodes(MonthlyRepriceBatch batch) {
    LocalDate priceDate = batch.getPriceAsOfTime() == null ? null : batch.getPriceAsOfTime().toLocalDate();
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getDeleted, 0)
        .eq(PriceLinkedItem::getBusinessUnitType, batch.getBusinessUnitType())
        .le(PriceLinkedItem::getPricingMonth, batch.getPricingMonth());
    if (priceDate != null) {
      query.and(q -> q.le(PriceLinkedItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceLinkedItem::getEffectiveFrom));
      query.and(q -> q.ge(PriceLinkedItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceLinkedItem::getEffectiveTo));
    }
    List<PriceLinkedItem> linkedItems = priceLinkedItemMapper.selectList(
        query.orderByAsc(PriceLinkedItem::getMaterialCode)
            .orderByAsc(PriceLinkedItem::getId));
    Set<String> itemCodes = new LinkedHashSet<>();
    for (PriceLinkedItem item : linkedItems) {
      if (item != null && StringUtils.hasText(item.getMaterialCode())) {
        itemCodes.add(item.getMaterialCode().trim());
      }
    }
    return itemCodes;
  }

  private void updateBatchStatus(MonthlyRepriceBatch batch, String status) {
    MonthlyRepriceBatch update = new MonthlyRepriceBatch();
    update.setId(batch.getId());
    update.setStatus(status);
    update.setStartedAt(batch.getStartedAt() == null ? LocalDateTime.now() : batch.getStartedAt());
    update.setFinishedAt(STATUS_FAILED.equals(status) ? LocalDateTime.now() : null);
    update.setUpdatedAt(LocalDateTime.now());
    batchMapper.updateById(update);
  }

  private MonthlyRepriceAuditLog buildAuditLog(
      MonthlyRepriceBatch batch,
      MonthlyRepriceLinkedPricePrepareResult result,
      String operator) {
    MonthlyRepriceAuditLog log = new MonthlyRepriceAuditLog();
    log.setRepriceNo(batch.getRepriceNo());
    log.setPricingMonth(batch.getPricingMonth());
    log.setBusinessUnitType(batch.getBusinessUnitType());
    log.setOperationType("PREPARE_LINKED_PRICE");
    log.setOperationName("准备月度调价联动价结果");
    log.setOperatorId(normalizeOperator(operator));
    log.setOperatorName(normalizeOperator(operator));
    log.setOperationTime(LocalDateTime.now());
    log.setTargetType("MONTHLY_REPRICE_BATCH");
    log.setTargetId(batch.getId() == null ? null : String.valueOf(batch.getId()));
    log.setTargetKey(batch.getRepriceNo());
    log.setAfterJson(afterJson(result));
    log.setChangeSummary("准备 MONTHLY_ADJUST 联动价：料号 " + result.getItemCount()
        + "，新增 " + result.getCreatedCount()
        + "，更新 " + result.getUpdatedCount()
        + "，跳过 " + result.getSkippedCount()
        + "，失败 " + result.getFailedCount());
    return log;
  }

  private String afterJson(MonthlyRepriceLinkedPricePrepareResult result) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("repriceNo", result.getRepriceNo());
    snapshot.put("pricingMonth", result.getPricingMonth());
    snapshot.put("adjustBatchId", result.getAdjustBatchId());
    snapshot.put("itemCount", result.getItemCount());
    snapshot.put("createdCount", result.getCreatedCount());
    snapshot.put("updatedCount", result.getUpdatedCount());
    snapshot.put("skippedCount", result.getSkippedCount());
    snapshot.put("failedCount", result.getFailedCount());
    snapshot.put("batchStatus", result.getBatchStatus());
    snapshot.put("failedItems", result.getFailedItems());
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("月度调价联动价准备审计快照序列化失败", ex);
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
