package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.service.CostRunTaskClaimService;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CostRunTaskClaimServiceImplTest {

  @Test
  void concurrentWorkersDoNotClaimSameTaskTwice() throws Exception {
    FakeTaskMapper mapper =
        new FakeTaskMapper(List.of(task(1L, "PENDING", null, null, 0, 3)), new CyclicBarrier(2));
    CostRunTaskClaimService service = new CostRunTaskClaimServiceImpl(mapper.proxy());
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<List<CostRunTask>> workerA =
          executor.submit(
              () -> service.claimTasks("worker-a", Set.of(CostRunTaskScene.QUOTE), 1, 30));
      Future<List<CostRunTask>> workerB =
          executor.submit(
              () -> service.claimTasks("worker-b", Set.of(CostRunTaskScene.QUOTE), 1, 30));

      List<CostRunTask> claimed =
          Stream.concat(
                  workerA.get(5, TimeUnit.SECONDS).stream(),
                  workerB.get(5, TimeUnit.SECONDS).stream())
              .toList();

      assertThat(claimed).hasSize(1);
      assertThat(claimed.get(0).getId()).isEqualTo(1L);
      assertThat(mapper.snapshot(1L).getStatus()).isEqualTo("RUNNING");
      assertThat(mapper.snapshot(1L).getWorkerId()).isIn("worker-a", "worker-b");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredRunningTaskCanBeReclaimedByAnotherWorker() {
    FakeTaskMapper mapper =
        new FakeTaskMapper(
            List.of(task(2L, "RUNNING", "old-worker", LocalDateTime.now().minusMinutes(5), 1, 3)),
            null);
    CostRunTaskClaimService service = new CostRunTaskClaimServiceImpl(mapper.proxy());

    List<CostRunTask> claimed =
        service.claimTasks("new-worker", Set.of(CostRunTaskScene.MONTHLY_REPRICE), 5, 15);

    assertThat(claimed).hasSize(1);
    CostRunTask stored = mapper.snapshot(2L);
    assertThat(stored.getStatus()).isEqualTo("RUNNING");
    assertThat(stored.getWorkerId()).isEqualTo("new-worker");
    assertThat(stored.getLockExpireTime()).isAfter(stored.getLockedAt());
  }

  @Test
  void retryableFailureMovesTaskToRetryableBeforeRetryLimit() {
    FakeTaskMapper mapper =
        new FakeTaskMapper(
            List.of(task(3L, "RUNNING", "worker-a", LocalDateTime.now().plusMinutes(10), 0, 3)),
            null);
    CostRunTaskClaimService service = new CostRunTaskClaimServiceImpl(mapper.proxy());

    boolean updated = service.recordFailure(3L, "worker-a", true, "计算异常", "stack");

    CostRunTask stored = mapper.snapshot(3L);
    assertThat(updated).isTrue();
    assertThat(stored.getStatus()).isEqualTo("RETRYABLE");
    assertThat(stored.getRetryCount()).isEqualTo(1);
    assertThat(stored.getWorkerId()).isNull();
    assertThat(stored.getErrorMessage()).isEqualTo("计算异常");
  }

  @Test
  void retryLimitReachedMarksTaskFailed() {
    FakeTaskMapper mapper =
        new FakeTaskMapper(
            List.of(task(4L, "RUNNING", "worker-a", LocalDateTime.now().plusMinutes(10), 2, 3)),
            null);
    CostRunTaskClaimService service = new CostRunTaskClaimServiceImpl(mapper.proxy());

    boolean updated = service.recordFailure(4L, "worker-a", true, "超过重试上限", "stack");

    CostRunTask stored = mapper.snapshot(4L);
    assertThat(updated).isTrue();
    assertThat(stored.getStatus()).isEqualTo("FAILED");
    assertThat(stored.getRetryCount()).isEqualTo(3);
    assertThat(stored.getWorkerId()).isNull();
    assertThat(stored.getErrorMessage()).isEqualTo("超过重试上限");
  }

  @Test
  void successfulTaskIsMarkedSuccessOnlyByCurrentWorker() {
    FakeTaskMapper mapper =
        new FakeTaskMapper(
            List.of(task(5L, "RUNNING", "worker-a", LocalDateTime.now().plusMinutes(10), 0, 3)),
            null);
    CostRunTaskClaimService service = new CostRunTaskClaimServiceImpl(mapper.proxy());

    boolean updated = service.markSuccess(5L, "worker-a", "{\"ok\":true}");

    CostRunTask stored = mapper.snapshot(5L);
    assertThat(updated).isTrue();
    assertThat(stored.getStatus()).isEqualTo("SUCCESS");
    assertThat(stored.getProgress()).isEqualTo(100);
    assertThat(stored.getWorkerId()).isNull();
    assertThat(stored.getResultSummaryJson()).isEqualTo("{\"ok\":true}");
  }

  private CostRunTask task(
      Long id,
      String status,
      String workerId,
      LocalDateTime lockExpireTime,
      int retryCount,
      int maxRetryCount) {
    CostRunTask task = new CostRunTask();
    task.setId(id);
    task.setBatchNo("BATCH-1");
    task.setScene("QUOTE");
    task.setStatus(status);
    task.setWorkerId(workerId);
    task.setLockExpireTime(lockExpireTime);
    task.setRetryCount(retryCount);
    task.setMaxRetryCount(maxRetryCount);
    task.setProgress(0);
    return task;
  }

  private static class FakeTaskMapper {

    private final Map<Long, CostRunTask> tasks = new LinkedHashMap<>();
    private final CyclicBarrier selectBarrier;
    private final AtomicInteger selectCount = new AtomicInteger();

    FakeTaskMapper(List<CostRunTask> seedTasks, CyclicBarrier selectBarrier) {
      for (CostRunTask task : seedTasks) {
        tasks.put(task.getId(), copy(task));
      }
      this.selectBarrier = selectBarrier;
    }

    CostRunTaskMapper proxy() {
      return (CostRunTaskMapper)
          Proxy.newProxyInstance(
              CostRunTaskMapper.class.getClassLoader(),
              new Class<?>[] {CostRunTaskMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "selectClaimCandidates" -> selectClaimCandidates(args);
                    case "claimTask" -> claimTask(args);
                    case "markRetryable" -> markRetryable(args);
                    case "markFailure" -> markFailure(args);
                    case "markSuccess" -> markSuccess(args);
                    case "toString" -> "FakeCostRunTaskMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }

    CostRunTask snapshot(Long id) {
      synchronized (tasks) {
        return copy(tasks.get(id));
      }
    }

    private List<CostRunTask> selectClaimCandidates(Object[] args) throws Exception {
      LocalDateTime now = (LocalDateTime) args[1];
      int limit = (Integer) args[2];
      List<CostRunTask> selected;
      synchronized (tasks) {
        selected = tasks.values().stream()
            .filter(task -> claimable(task, now))
            .limit(limit)
            .map(FakeTaskMapper::copy)
            .toList();
      }
      if (selectBarrier != null && selectCount.incrementAndGet() <= 2) {
        selectBarrier.await(5, TimeUnit.SECONDS);
      }
      return selected;
    }

    private int claimTask(Object[] args) {
      Long taskId = (Long) args[0];
      String workerId = (String) args[1];
      LocalDateTime lockedAt = (LocalDateTime) args[2];
      LocalDateTime lockExpireTime = (LocalDateTime) args[3];
      synchronized (tasks) {
        CostRunTask task = tasks.get(taskId);
        if (!claimable(task, lockedAt)) {
          return 0;
        }
        task.setStatus("RUNNING");
        task.setWorkerId(workerId);
        task.setLockedAt(lockedAt);
        task.setLockExpireTime(lockExpireTime);
        task.setStartedAt(task.getStartedAt() == null ? lockedAt : task.getStartedAt());
        task.setFinishedAt(null);
        task.setProgress(1);
        return 1;
      }
    }

    private int markRetryable(Object[] args) {
      Long taskId = (Long) args[0];
      String workerId = (String) args[1];
      String errorMessage = (String) args[2];
      String errorStack = (String) args[3];
      LocalDateTime finishedAt = (LocalDateTime) args[4];
      synchronized (tasks) {
        CostRunTask task = tasks.get(taskId);
        if (!ownedRunning(task, workerId)
            || task.getRetryCount() + 1 >= task.getMaxRetryCount()) {
          return 0;
        }
        task.setStatus("RETRYABLE");
        task.setWorkerId(null);
        task.setLockedAt(null);
        task.setLockExpireTime(null);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(errorMessage);
        task.setErrorStack(errorStack);
        task.setFinishedAt(finishedAt);
        return 1;
      }
    }

    private int markFailure(Object[] args) {
      Long taskId = (Long) args[0];
      String workerId = (String) args[1];
      String errorMessage = (String) args[2];
      String errorStack = (String) args[3];
      LocalDateTime finishedAt = (LocalDateTime) args[4];
      synchronized (tasks) {
        CostRunTask task = tasks.get(taskId);
        if (!ownedRunning(task, workerId)) {
          return 0;
        }
        task.setStatus("FAILED");
        task.setWorkerId(null);
        task.setLockedAt(null);
        task.setLockExpireTime(null);
        if (task.getRetryCount() < task.getMaxRetryCount()) {
          task.setRetryCount(task.getRetryCount() + 1);
        }
        task.setErrorMessage(errorMessage);
        task.setErrorStack(errorStack);
        task.setFinishedAt(finishedAt);
        return 1;
      }
    }

    private int markSuccess(Object[] args) {
      Long taskId = (Long) args[0];
      String workerId = (String) args[1];
      String resultSummaryJson = (String) args[2];
      LocalDateTime finishedAt = (LocalDateTime) args[3];
      synchronized (tasks) {
        CostRunTask task = tasks.get(taskId);
        if (!ownedRunning(task, workerId)) {
          return 0;
        }
        task.setStatus("SUCCESS");
        task.setProgress(100);
        task.setWorkerId(null);
        task.setLockedAt(null);
        task.setLockExpireTime(null);
        task.setResultSummaryJson(resultSummaryJson);
        task.setFinishedAt(finishedAt);
        return 1;
      }
    }

    private boolean claimable(CostRunTask task, LocalDateTime now) {
      if (task == null) {
        return false;
      }
      if ("PENDING".equals(task.getStatus()) || "RETRYABLE".equals(task.getStatus())) {
        return true;
      }
      return "RUNNING".equals(task.getStatus())
          && task.getLockExpireTime() != null
          && !task.getLockExpireTime().isAfter(now);
    }

    private boolean ownedRunning(CostRunTask task, String workerId) {
      return task != null
          && "RUNNING".equals(task.getStatus())
          && Objects.equals(workerId, task.getWorkerId());
    }

    private static CostRunTask copy(CostRunTask source) {
      if (source == null) {
        return null;
      }
      CostRunTask copy = new CostRunTask();
      copy.setId(source.getId());
      copy.setBatchNo(source.getBatchNo());
      copy.setScene(source.getScene());
      copy.setStatus(source.getStatus());
      copy.setWorkerId(source.getWorkerId());
      copy.setLockedAt(source.getLockedAt());
      copy.setLockExpireTime(source.getLockExpireTime());
      copy.setRetryCount(source.getRetryCount());
      copy.setMaxRetryCount(source.getMaxRetryCount());
      copy.setProgress(source.getProgress());
      copy.setErrorMessage(source.getErrorMessage());
      copy.setErrorStack(source.getErrorStack());
      copy.setResultSummaryJson(source.getResultSummaryJson());
      copy.setStartedAt(source.getStartedAt());
      copy.setFinishedAt(source.getFinishedAt());
      return copy;
    }
  }
}
