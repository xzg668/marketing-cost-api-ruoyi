package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunBatchPrerequisiteService implements CostRunBatchPrerequisiteService {

  private static final Logger log =
      LoggerFactory.getLogger(QuoteCostRunBatchPrerequisiteService.class);
  private static final int DEFAULT_LIMIT = 20;
  private static final int DEFAULT_STALE_TIMEOUT_MINUTES = 10;
  private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

  private final CostRunBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MaterialMasterSyncService materialMasterSyncService;

  public QuoteCostRunBatchPrerequisiteService(
      CostRunBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MaterialMasterSyncService materialMasterSyncService) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.materialMasterSyncService = materialMasterSyncService;
  }

  @Override
  public PreparationSummary preparePendingQuoteBatches(
      String workerId, int limit, int staleTimeoutMinutes) {
    String safeWorkerId = required(workerId);
    int safeLimit = limit > 0 ? limit : DEFAULT_LIMIT;
    int safeTimeout =
        staleTimeoutMinutes > 0 ? staleTimeoutMinutes : DEFAULT_STALE_TIMEOUT_MINUTES;
    LocalDateTime now = LocalDateTime.now();
    List<CostRunBatch> candidates =
        batchMapper.selectQuotePrerequisiteCandidates(now.minusMinutes(safeTimeout), safeLimit);
    if (candidates == null || candidates.isEmpty()) {
      return PreparationSummary.empty();
    }

    int claimed = 0;
    int succeeded = 0;
    int failed = 0;
    for (CostRunBatch candidate : candidates) {
      if (!valid(candidate)) {
        continue;
      }
      String expectedStatus = candidate.getPrerequisiteStatus().trim().toUpperCase(Locale.ROOT);
      int executionNo = value(candidate.getExecutionNo(), 1);
      int controlVersion = value(candidate.getControlVersion(), 0);
      LocalDateTime claimedAt = LocalDateTime.now();
      int acquired =
          batchMapper.transitionPrerequisite(
              candidate.getBatchNo(),
              executionNo,
              controlVersion,
              expectedStatus,
              "RUNNING",
              null,
              claimedAt);
      if (acquired != 1) {
        continue;
      }
      claimed++;
      try {
        MaterialMasterSyncService.SyncResult result =
            materialMasterSyncService.syncByOaNoAndPeriod(
                candidate.getSourceNo(), candidate.getPricingMonth());
        int completed =
            batchMapper.transitionPrerequisite(
                candidate.getBatchNo(),
                executionNo,
                controlVersion + 1,
                "RUNNING",
                "SUCCESS",
                null,
                LocalDateTime.now());
        if (completed != 1) {
          throw new IllegalStateException("批次前置所有权已变化，无法标记成功");
        }
        succeeded++;
        log.info(
            "quote batch prerequisite done: workerId={} batchNo={} executionNo={} oa={} codes={} stagingHits={} affected={}",
            safeWorkerId,
            candidate.getBatchNo(),
            executionNo,
            candidate.getSourceNo(),
            result.distinctCodes(),
            result.stagingHits(),
            result.affectedRows());
      } catch (RuntimeException ex) {
        failed++;
        String message = "主档同步失败: " + valueOr(ex.getMessage(), ex.getClass().getSimpleName());
        int marked =
            batchMapper.transitionPrerequisite(
                candidate.getBatchNo(),
                executionNo,
                controlVersion + 1,
                "RUNNING",
                "FAILED",
                truncate(message),
                LocalDateTime.now());
        int failedTasks =
            marked == 1
                ? taskMapper.markQuoteTasksFailedByPrerequisite(
                    candidate.getBatchNo(), executionNo, truncate(message), LocalDateTime.now())
                : 0;
        log.error(
            "quote batch prerequisite failed: workerId={} batchNo={} executionNo={} oa={} marked={} failedTasks={}",
            safeWorkerId,
            candidate.getBatchNo(),
            executionNo,
            candidate.getSourceNo(),
            marked,
            failedTasks,
            ex);
      }
    }
    return new PreparationSummary(candidates.size(), claimed, succeeded, failed);
  }

  private boolean valid(CostRunBatch batch) {
    return batch != null
        && StringUtils.hasText(batch.getBatchNo())
        && StringUtils.hasText(batch.getSourceNo())
        && StringUtils.hasText(batch.getPrerequisiteStatus());
  }

  private String required(String workerId) {
    if (!StringUtils.hasText(workerId)) {
      throw new IllegalArgumentException("workerId 不能为空");
    }
    return workerId.trim();
  }

  private int value(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private String valueOr(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private String truncate(String value) {
    if (value == null || value.length() <= ERROR_MESSAGE_MAX_LENGTH) {
      return value;
    }
    return value.substring(0, ERROR_MESSAGE_MAX_LENGTH);
  }
}
