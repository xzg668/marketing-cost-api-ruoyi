package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.enums.CostRunTaskStatus;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.service.CostRunTaskClaimService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunTaskClaimServiceImpl implements CostRunTaskClaimService {

  private static final int DEFAULT_BATCH_SIZE = 20;
  private static final int DEFAULT_LOCK_TIMEOUT_MINUTES = 30;
  private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;
  private static final int ERROR_STACK_MAX_LENGTH = 4000;

  private final CostRunTaskMapper taskMapper;

  public CostRunTaskClaimServiceImpl(CostRunTaskMapper taskMapper) {
    this.taskMapper = taskMapper;
  }

  @Override
  public List<CostRunTask> claimTasks(
      String workerId, Set<CostRunTaskScene> scenes, int batchSize, int lockTimeoutMinutes) {
    String safeWorkerId = required("workerId", workerId);
    Set<String> sceneCodes = normalizeScenes(scenes);
    int limit = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    int timeoutMinutes =
        lockTimeoutMinutes > 0 ? lockTimeoutMinutes : DEFAULT_LOCK_TIMEOUT_MINUTES;
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime lockExpireTime = now.plusMinutes(timeoutMinutes);

    List<CostRunTask> candidates = taskMapper.selectClaimCandidates(sceneCodes, now, limit);
    List<CostRunTask> claimed = new ArrayList<>(candidates.size());
    for (CostRunTask candidate : candidates) {
      if (candidate.getId() == null) {
        continue;
      }
      int updated = taskMapper.claimTask(candidate.getId(), safeWorkerId, now, lockExpireTime);
      if (updated == 1) {
        candidate.setStatus(CostRunTaskStatus.RUNNING.name());
        candidate.setWorkerId(safeWorkerId);
        candidate.setLockedAt(now);
        candidate.setLockExpireTime(lockExpireTime);
        candidate.setStartedAt(candidate.getStartedAt() == null ? now : candidate.getStartedAt());
        candidate.setFinishedAt(null);
        if (candidate.getProgress() == null || candidate.getProgress() < 1) {
          candidate.setProgress(1);
        }
        claimed.add(candidate);
      }
    }
    return claimed;
  }

  @Override
  public boolean markSuccess(Long taskId, String workerId, String resultSummaryJson) {
    if (taskId == null || !StringUtils.hasText(workerId)) {
      return false;
    }
    return taskMapper.markSuccess(
            taskId, workerId.trim(), resultSummaryJson, LocalDateTime.now())
        == 1;
  }

  @Override
  public boolean markRetryable(Long taskId, String workerId, String errorMessage, String errorStack) {
    if (taskId == null || !StringUtils.hasText(workerId)) {
      return false;
    }
    return taskMapper.markRetryable(
            taskId,
            workerId.trim(),
            truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH),
            truncate(errorStack, ERROR_STACK_MAX_LENGTH),
            LocalDateTime.now())
        == 1;
  }

  @Override
  public boolean markFailure(Long taskId, String workerId, String errorMessage, String errorStack) {
    if (taskId == null || !StringUtils.hasText(workerId)) {
      return false;
    }
    return taskMapper.markFailure(
            taskId,
            workerId.trim(),
            truncate(errorMessage, ERROR_MESSAGE_MAX_LENGTH),
            truncate(errorStack, ERROR_STACK_MAX_LENGTH),
            LocalDateTime.now())
        == 1;
  }

  @Override
  public boolean recordFailure(
      Long taskId, String workerId, boolean retryable, String errorMessage, String errorStack) {
    if (retryable && markRetryable(taskId, workerId, errorMessage, errorStack)) {
      return true;
    }
    return markFailure(taskId, workerId, errorMessage, errorStack);
  }

  private Set<String> normalizeScenes(Set<CostRunTaskScene> scenes) {
    if (scenes == null || scenes.isEmpty()) {
      throw new IllegalArgumentException("scenes 不能为空");
    }
    Set<String> normalized =
        scenes.stream()
            .map(scene -> scene == null ? null : scene.name())
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("scenes 不能为空");
    }
    return normalized;
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
