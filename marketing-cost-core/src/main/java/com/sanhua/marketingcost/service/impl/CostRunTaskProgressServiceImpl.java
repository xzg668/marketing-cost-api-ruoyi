package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.enums.CostRunBatchStatus;
import com.sanhua.marketingcost.enums.CostRunTaskStatus;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunTaskProgressServiceImpl implements CostRunTaskProgressService {

  private final CostRunBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;

  public CostRunTaskProgressServiceImpl(
      CostRunBatchMapper batchMapper, CostRunTaskMapper taskMapper) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
  }

  @Override
  public CostRunBatchProgressSnapshot refreshBatchProgress(String batchNo) {
    String normalizedBatchNo = required("batchNo", batchNo);
    CostRunBatchProgressSnapshot snapshot = snapshot(normalizedBatchNo);
    String status = snapshot.getStatus();
    int finished = snapshot.getSuccessCount()
        + snapshot.getFailedCount()
        + snapshot.getSkippedCount();
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime startedAt = snapshot.getRunningCount() > 0 || finished > 0 ? now : null;
    LocalDateTime finishedAt = isTerminal(status) ? now : null;

    batchMapper.updateProgress(
        normalizedBatchNo,
        status,
        snapshot.getTotalCount(),
        snapshot.getSuccessCount(),
        snapshot.getFailedCount(),
        snapshot.getSkippedCount(),
        snapshot.getProgress(),
        startedAt,
        finishedAt,
        now);
    return snapshot;
  }

  @Override
  public CostRunBatchProgressSnapshot getBatchProgress(String batchNo) {
    return snapshot(required("batchNo", batchNo));
  }

  private CostRunBatchProgressSnapshot snapshot(String normalizedBatchNo) {
    Map<CostRunTaskStatus, Integer> counts = loadCounts(normalizedBatchNo);
    int success = counts.get(CostRunTaskStatus.SUCCESS);
    int failed = counts.get(CostRunTaskStatus.FAILED);
    int canceled = counts.get(CostRunTaskStatus.CANCELED);
    int collaboration = counts.get(CostRunTaskStatus.COLLABORATION);
    int skippedCurrent = counts.get(CostRunTaskStatus.SKIPPED_CURRENT);
    int running = counts.get(CostRunTaskStatus.RUNNING);
    int retryable = counts.get(CostRunTaskStatus.RETRYABLE);
    int pending = counts.get(CostRunTaskStatus.PENDING);
    int skipped = canceled + collaboration + skippedCurrent;
    int total = success + failed + skipped + running + retryable + pending;
    int finished = success + failed + skipped;
    int progress = total == 0 ? 0 : (int) Math.floor(finished * 100.0 / total);
    if (total > 0 && finished == total) {
      progress = 100;
    }
    String status = resolveBatchStatus(
        total,
        success,
        failed,
        canceled,
        collaboration + skippedCurrent,
        running,
        retryable,
        pending);

    CostRunBatchProgressSnapshot snapshot = new CostRunBatchProgressSnapshot();
    snapshot.setBatchNo(normalizedBatchNo);
    snapshot.setStatus(status);
    snapshot.setTotalCount(total);
    snapshot.setSuccessCount(success);
    snapshot.setFailedCount(failed);
    snapshot.setSkippedCount(skipped);
    snapshot.setCollaborationCount(collaboration);
    snapshot.setSkippedCurrentCount(skippedCurrent);
    snapshot.setRunningCount(running);
    snapshot.setRetryableCount(retryable);
    snapshot.setPendingCount(pending);
    snapshot.setProgress(progress);
    return snapshot;
  }

  private Map<CostRunTaskStatus, Integer> loadCounts(String batchNo) {
    Map<CostRunTaskStatus, Integer> counts = new EnumMap<>(CostRunTaskStatus.class);
    for (CostRunTaskStatus status : CostRunTaskStatus.values()) {
      counts.put(status, 0);
    }
    List<CostRunTaskStatusCount> rows = taskMapper.selectStatusCounts(batchNo);
    for (CostRunTaskStatusCount row : rows) {
      CostRunTaskStatus status = CostRunTaskStatus.fromCode(row.getStatus());
      counts.put(status, row.getCount() == null ? 0 : Math.toIntExact(row.getCount()));
    }
    return counts;
  }

  private String resolveBatchStatus(
      int total,
      int success,
      int failed,
      int canceled,
      int acceptedSkipped,
      int running,
      int retryable,
      int pending) {
    if (total == 0 || pending == total) {
      return CostRunBatchStatus.PENDING.name();
    }
    if (success + acceptedSkipped == total && failed == 0 && canceled == 0) {
      return CostRunBatchStatus.SUCCESS.name();
    }
    if (canceled == total) {
      return CostRunBatchStatus.CANCELED.name();
    }
    int finished = success + failed + canceled + acceptedSkipped;
    if (finished == total) {
      return success > 0 ? CostRunBatchStatus.PARTIAL_FAILED.name() : CostRunBatchStatus.FAILED.name();
    }
    if (running > 0 || retryable > 0 || finished > 0) {
      return CostRunBatchStatus.RUNNING.name();
    }
    return CostRunBatchStatus.PENDING.name();
  }

  private boolean isTerminal(String status) {
    return CostRunBatchStatus.SUCCESS.name().equals(status)
        || CostRunBatchStatus.PARTIAL_FAILED.name().equals(status)
        || CostRunBatchStatus.FAILED.name().equals(status)
        || CostRunBatchStatus.CANCELED.name().equals(status);
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }
}
