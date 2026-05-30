package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceProgressServiceImpl implements MonthlyRepriceProgressService {

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_RETRYABLE = "RETRYABLE";
  private static final String STATUS_WAIT_CONFIRM = "WAIT_CONFIRM";
  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_CANCELLED = "CANCELLED";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MonthlyRepriceResultMapper resultMapper;
  private final MonthlyRepriceAuditService auditService;

  public MonthlyRepriceProgressServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MonthlyRepriceResultMapper resultMapper,
      MonthlyRepriceAuditService auditService) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
    this.auditService = auditService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MonthlyRepriceProgressSnapshot refreshProgress(String repriceNo) {
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    TaskCounts counts = loadTaskCounts(normalizedRepriceNo);
    int resultCount = safeLong(resultMapper.countByRepriceNo(normalizedRepriceNo));

    if (isReadOnlyStatus(batch.getStatus())) {
      return snapshot(batch, counts, resultCount);
    }

    int skippedCount = safe(batch.getSkippedCount());
    String currentStatus = normalize(batch.getStatus());
    MonthlyRepriceBatch before = copyBatch(batch);
    String nextStatus = resolveStatus(batch, counts, skippedCount);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime finishedAt =
        isCompletedStatus(nextStatus) ? (batch.getFinishedAt() == null ? now : batch.getFinishedAt()) : null;

    batchMapper.updateProgress(
        batch.getId(),
        counts.successCount(),
        counts.failedCount(),
        skippedCount,
        nextStatus,
        finishedAt,
        now);

    batch.setSuccessCount(counts.successCount());
    batch.setFailedCount(counts.failedCount());
    batch.setSkippedCount(skippedCount);
    batch.setStatus(nextStatus);
    batch.setFinishedAt(finishedAt);
    batch.setUpdatedAt(now);
    MonthlyRepriceProgressSnapshot refreshed = snapshot(batch, counts, resultCount);
    recordCompletionAudit(before, nextStatus, currentStatus, refreshed);
    return refreshed;
  }

  @Override
  public MonthlyRepriceProgressSnapshot getProgress(String repriceNo) {
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    TaskCounts counts = loadTaskCounts(normalizedRepriceNo);
    int resultCount = safeLong(resultMapper.countByRepriceNo(normalizedRepriceNo));
    return snapshot(batch, counts, resultCount);
  }

  @Override
  public MonthlyRepriceBatch getLatestConfirmedBatch(String pricingMonth, String businessUnitType) {
    return batchMapper.selectLatestConfirmed(
        required("pricingMonth", pricingMonth), required("businessUnitType", businessUnitType));
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

  private TaskCounts loadTaskCounts(String repriceNo) {
    int pending = 0;
    int running = 0;
    int success = 0;
    int failed = 0;
    List<CostRunTaskStatusCount> rows = taskMapper.selectMonthlyRepriceStatusCounts(repriceNo);
    if (rows == null) {
      return new TaskCounts(0, 0, 0, 0);
    }
    for (CostRunTaskStatusCount row : rows) {
      if (row == null) {
        continue;
      }
      int count = safeLong(row.getCount());
      String status = normalize(row.getStatus());
      if (STATUS_PENDING.equals(status) || STATUS_RETRYABLE.equals(status)) {
        pending += count;
      } else if (STATUS_RUNNING.equals(status)) {
        running += count;
      } else if (STATUS_SUCCESS.equals(status)) {
        success += count;
      } else if (STATUS_FAILED.equals(status)) {
        failed += count;
      }
    }
    return new TaskCounts(pending, running, success, failed);
  }

  private String resolveStatus(MonthlyRepriceBatch batch, TaskCounts counts, int skippedCount) {
    String currentStatus = normalize(batch.getStatus());
    if (STATUS_CANCELLED.equals(currentStatus)) {
      return currentStatus;
    }
    int totalCount = safe(batch.getTotalCount());
    int completedCount = counts.successCount() + counts.failedCount() + skippedCount;
    // 批次只在所有任务都有终态后收口；有失败必进 FAILED，只有全成功才进入待确认。
    if (totalCount > 0 && completedCount >= totalCount) {
      if (counts.failedCount() == 0 && counts.successCount() == totalCount) {
        return STATUS_WAIT_CONFIRM;
      }
      return STATUS_FAILED;
    }
    if (STATUS_WAIT_CONFIRM.equals(currentStatus) || STATUS_FAILED.equals(currentStatus)) {
      return STATUS_RUNNING;
    }
    return currentStatus;
  }

  private void recordCompletionAudit(
      MonthlyRepriceBatch before,
      String nextStatus,
      String currentStatus,
      MonthlyRepriceProgressSnapshot after) {
    if (nextStatus.equals(currentStatus)) {
      return;
    }
    // 批次收口只记录一次完成/失败审计，不记录每个成功任务，避免大批量核算日志爆量。
    if (STATUS_WAIT_CONFIRM.equals(nextStatus)) {
      auditService.recordCalcCompleted(before, after);
    } else if (STATUS_FAILED.equals(nextStatus)) {
      auditService.recordCalcFailed(before, after);
    }
  }

  private MonthlyRepriceBatch copyBatch(MonthlyRepriceBatch source) {
    MonthlyRepriceBatch copy = new MonthlyRepriceBatch();
    copy.setId(source.getId());
    copy.setRepriceNo(source.getRepriceNo());
    copy.setPricingMonth(source.getPricingMonth());
    copy.setBusinessUnitType(source.getBusinessUnitType());
    copy.setStatus(source.getStatus());
    copy.setTotalCount(source.getTotalCount());
    copy.setSuccessCount(source.getSuccessCount());
    copy.setFailedCount(source.getFailedCount());
    copy.setSkippedCount(source.getSkippedCount());
    copy.setStartedAt(source.getStartedAt());
    copy.setFinishedAt(source.getFinishedAt());
    return copy;
  }

  private MonthlyRepriceProgressSnapshot snapshot(
      MonthlyRepriceBatch batch, TaskCounts counts, int resultCount) {
    return MonthlyRepriceProgressSnapshot.of(
        batch, counts.pendingCount(), counts.runningCount(), resultCount);
  }

  private boolean isReadOnlyStatus(String status) {
    String normalized = normalize(status);
    return STATUS_CONFIRMED.equals(normalized) || STATUS_CANCELLED.equals(normalized);
  }

  private boolean isCompletedStatus(String status) {
    String normalized = normalize(status);
    return STATUS_WAIT_CONFIRM.equals(normalized) || STATUS_FAILED.equals(normalized);
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private int safe(Integer value) {
    return value == null ? 0 : value;
  }

  private int safeLong(long value) {
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }

  private record TaskCounts(
      int pendingCount, int runningCount, int successCount, int failedCount) {
  }
}
