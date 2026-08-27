package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.service.CostRunTaskClaimService;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CostRunTaskWorkerTest {

  @Test
  void disabledWorkerDoesNotClaimTasks() {
    FakeClaimService claimService = new FakeClaimService();
    FakeProgressService progressService = new FakeProgressService();
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    CostRunWorkerProperties properties = new CostRunWorkerProperties();
    properties.setEnabled(false);
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            progressService,
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            properties);

    assertThat(worker.scanOnce()).isZero();
    assertThat(claimService.callCount).isZero();
    assertThat(executor.executedTaskIds).isEmpty();
    assertThat(progressService.refreshedBatchNos).isEmpty();
  }

  @Test
  void enabledWorkerClaimsQuoteTasksWithConfiguredOptions() {
    FakeClaimService claimService = new FakeClaimService();
    FakeProgressService progressService = new FakeProgressService();
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    CostRunWorkerProperties properties = enabledProperties();
    properties.setClaimBatchSize(2);
    properties.setLockTimeoutMinutes(15);
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            progressService,
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            properties);
    claimService.tasks = List.of(task(1L, "BATCH-1", "QUOTE"), task(2L, "BATCH-1", "QUOTE"));

    assertThat(worker.scanOnce()).isEqualTo(2);
    assertThat(claimService.callCount).isEqualTo(1);
    assertThat(claimService.lastWorkerId).isEqualTo("worker-node-1");
    assertThat(claimService.lastScenes).containsExactly(CostRunTaskScene.QUOTE);
    assertThat(claimService.lastBatchSize).isEqualTo(2);
    assertThat(claimService.lastLockTimeoutMinutes).isEqualTo(15);
    assertThat(executor.executedTaskIds).containsExactly(1L, 2L);
    assertThat(claimService.successTaskIds).containsExactly(1L, 2L);
    assertThat(claimService.successSummaries)
        .containsExactly("{\"executor\":\"QUOTE\"}", "{\"executor\":\"QUOTE\"}");
    assertThat(claimService.successVersionIds).containsExactly(101L, 102L);
    assertThat(claimService.successRunNos).containsExactly("COST-1", "COST-2");
    assertThat(progressService.refreshedBatchNos).containsExactly("BATCH-1");
  }

  @Test
  void unknownSceneIsMarkedAsFinalFailure() {
    FakeClaimService claimService = new FakeClaimService();
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            new FakeProgressService(),
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(new FakeExecutor(CostRunTaskScene.QUOTE))),
            enabledProperties());

    CostRunTask task = task(3L, "BATCH-2", "UNKNOWN");
    CostRunTaskWorker.TaskExecutionMetric metric = worker.processTask("worker-node-1", task);

    assertThat(metric.success()).isFalse();
    assertThat(claimService.finalFailureTaskIds).containsExactly(3L);
    assertThat(claimService.retryableFailureTaskIds).isEmpty();
    assertThat(claimService.lastErrorMessage).contains("不支持");
  }

  @Test
  void retryableExecutorFailureIsRecordedWithRetryPolicy() {
    FakeClaimService claimService = new FakeClaimService();
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    executor.failure = new CostRunTaskExecutionFailedException("临时失败", true);
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            new FakeProgressService(),
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            enabledProperties());

    CostRunTaskWorker.TaskExecutionMetric metric =
        worker.processTask("worker-node-1", task(4L, "BATCH-3", "QUOTE"));

    assertThat(metric.success()).isFalse();
    assertThat(claimService.retryableFailureTaskIds).containsExactly(4L);
    assertThat(claimService.finalFailureTaskIds).isEmpty();
    assertThat(claimService.lastErrorMessage).isEqualTo("临时失败");
    assertThat(claimService.lastErrorStack).contains("CostRunTaskExecutionFailedException");
  }

  @Test
  void businessGapIsMarkedCollaborationWithoutRetrying() {
    FakeClaimService claimService = new FakeClaimService();
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    executor.failure =
        new CostRunTaskCollaborationRequiredException(
            "缺少价格", "{\"pipelineStatus\":\"BLOCKED\"}");
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            new FakeProgressService(),
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            enabledProperties());

    CostRunTaskWorker.TaskExecutionMetric metric =
        worker.processTask("worker-node-1", task(5L, "BATCH-3", "QUOTE"));

    assertThat(metric.success()).isTrue();
    assertThat(claimService.collaborationTaskIds).containsExactly(5L);
    assertThat(claimService.retryableFailureTaskIds).isEmpty();
    assertThat(claimService.finalFailureTaskIds).isEmpty();
  }

  @Test
  void workerUsesConfiguredThreadsForClaimedTasks() {
    FakeClaimService claimService = new FakeClaimService();
    FakeProgressService progressService = new FakeProgressService();
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    executor.delayMillis = 20L;
    CostRunWorkerProperties properties = enabledProperties();
    properties.setThreads(4);
    properties.setClaimBatchSize(20);
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            progressService,
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            properties);
    claimService.tasks =
        java.util.stream.LongStream.rangeClosed(1L, 20L)
            .mapToObj(id -> task(id, "BATCH-4", "QUOTE"))
            .toList();

    assertThat(worker.scanOnce()).isEqualTo(20);
    assertThat(executor.executedTaskIds).containsExactlyInAnyOrderElementsOf(
        java.util.stream.LongStream.rangeClosed(1L, 20L).boxed().toList());
    assertThat(executor.maxConcurrency.get()).isGreaterThan(1);
    assertThat(progressService.refreshedBatchNos).containsExactly("BATCH-4");
  }

  @Test
  void fortySevenQuoteProductsUseOneBatchPreparationAndFourProductThreads() {
    FakeClaimService claimService = new FakeClaimService();
    claimService.taskPages = List.of(
        tasks(1L, 20L, "BATCH-47", "QUOTE"),
        tasks(21L, 40L, "BATCH-47", "QUOTE"),
        tasks(41L, 47L, "BATCH-47", "QUOTE"));
    FakeExecutor executor = new FakeExecutor(CostRunTaskScene.QUOTE);
    executor.delayMillis = 10L;
    CostRunWorkerProperties properties = enabledProperties();
    properties.setThreads(4);
    properties.setClaimBatchSize(20);
    CostRunTaskWorker worker =
        new CostRunTaskWorker(
            claimService,
            new FakeProgressService(),
            emptyProvider(),
            new CostRunTaskExecutorRegistry(List.of(executor)),
            properties);

    assertThat(worker.scanOnce()).isEqualTo(20);
    assertThat(worker.scanOnce()).isEqualTo(20);
    assertThat(worker.scanOnce()).isEqualTo(7);

    assertThat(executor.executedTaskIds).hasSize(47);
    assertThat(executor.maxConcurrency.get()).isEqualTo(4);
  }

  private CostRunWorkerProperties enabledProperties() {
    CostRunWorkerProperties properties = new CostRunWorkerProperties();
    properties.setEnabled(true);
    properties.setId("worker-node-1");
    properties.setThreads(1);
    properties.setScenes(EnumSet.of(CostRunTaskScene.QUOTE));
    return properties;
  }

  private CostRunTask task(Long id, String batchNo, String scene) {
    CostRunTask task = new CostRunTask();
    task.setId(id);
    task.setBatchNo(batchNo);
    task.setScene(scene);
    return task;
  }

  private List<CostRunTask> tasks(long firstId, long lastId, String batchNo, String scene) {
    return java.util.stream.LongStream.rangeClosed(firstId, lastId)
        .mapToObj(id -> task(id, batchNo, scene))
        .toList();
  }

  private ObjectProvider<MonthlyRepriceProgressService> emptyProvider() {
    return new ObjectProvider<MonthlyRepriceProgressService>() {
      @Override
      public MonthlyRepriceProgressService getObject(Object... args) {
        return null;
      }

      @Override
      public MonthlyRepriceProgressService getIfAvailable() {
        return null;
      }

      @Override
      public MonthlyRepriceProgressService getIfUnique() {
        return null;
      }

      @Override
      public MonthlyRepriceProgressService getObject() {
        return null;
      }
    };
  }

  private static class FakeClaimService implements CostRunTaskClaimService {

    private int callCount;
    private String lastWorkerId;
    private Set<CostRunTaskScene> lastScenes;
    private int lastBatchSize;
    private int lastLockTimeoutMinutes;
    private List<CostRunTask> tasks = List.of();
    private List<List<CostRunTask>> taskPages = List.of();
    private int taskPageIndex;
    private final List<Long> successTaskIds = new ArrayList<>();
    private final List<String> successSummaries = new ArrayList<>();
    private final List<Long> successVersionIds = new ArrayList<>();
    private final List<String> successRunNos = new ArrayList<>();
    private final List<Long> retryableFailureTaskIds = new ArrayList<>();
    private final List<Long> finalFailureTaskIds = new ArrayList<>();
    private final List<Long> collaborationTaskIds = new ArrayList<>();
    private String lastErrorMessage;
    private String lastErrorStack;

    @Override
    public List<CostRunTask> claimTasks(
        String workerId, Set<CostRunTaskScene> scenes, int batchSize, int lockTimeoutMinutes) {
      callCount++;
      lastWorkerId = workerId;
      lastScenes = scenes;
      lastBatchSize = batchSize;
      lastLockTimeoutMinutes = lockTimeoutMinutes;
      if (taskPageIndex < taskPages.size()) {
        return taskPages.get(taskPageIndex++);
      }
      return tasks;
    }

    @Override
    public boolean markSuccess(
        Long taskId,
        String workerId,
        Long costRunVersionId,
        String costRunNo,
        String resultSummaryJson) {
      successTaskIds.add(taskId);
      successVersionIds.add(costRunVersionId);
      successRunNos.add(costRunNo);
      successSummaries.add(resultSummaryJson);
      return true;
    }

    @Override
    public boolean markCollaboration(
        Long taskId, String workerId, String resultSummaryJson, String message) {
      collaborationTaskIds.add(taskId);
      lastErrorMessage = message;
      return true;
    }

    @Override
    public boolean markRetryable(
        Long taskId, String workerId, String errorMessage, String errorStack) {
      retryableFailureTaskIds.add(taskId);
      lastErrorMessage = errorMessage;
      lastErrorStack = errorStack;
      return true;
    }

    @Override
    public boolean markFailure(Long taskId, String workerId, String errorMessage, String errorStack) {
      finalFailureTaskIds.add(taskId);
      lastErrorMessage = errorMessage;
      lastErrorStack = errorStack;
      return true;
    }

    @Override
    public boolean recordFailure(
        Long taskId, String workerId, boolean retryable, String errorMessage, String errorStack) {
      if (retryable) {
        return markRetryable(taskId, workerId, errorMessage, errorStack);
      }
      return markFailure(taskId, workerId, errorMessage, errorStack);
    }
  }

  private static class FakeProgressService implements CostRunTaskProgressService {

    private final List<String> refreshedBatchNos = new ArrayList<>();

    @Override
    public CostRunBatchProgressSnapshot refreshBatchProgress(String batchNo) {
      refreshedBatchNos.add(batchNo);
      return new CostRunBatchProgressSnapshot();
    }

    @Override
    public CostRunBatchProgressSnapshot getBatchProgress(String batchNo) {
      return new CostRunBatchProgressSnapshot();
    }
  }

  private static class FakeExecutor implements CostRunTaskExecutor {

    private final CostRunTaskScene scene;
    private final List<Long> executedTaskIds = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger runningCount = new AtomicInteger();
    private final AtomicInteger maxConcurrency = new AtomicInteger();
    private RuntimeException failure;
    private long delayMillis;

    private FakeExecutor(CostRunTaskScene scene) {
      this.scene = scene;
    }

    @Override
    public CostRunTaskScene scene() {
      return scene;
    }

    @Override
    public CostRunTaskExecutionResult execute(CostRunTask task, String workerId) {
      int current = runningCount.incrementAndGet();
      maxConcurrency.accumulateAndGet(current, Math::max);
      try {
        executedTaskIds.add(task.getId());
        if (delayMillis > 0L) {
          Thread.sleep(delayMillis);
        }
        if (failure != null) {
          throw failure;
        }
        return new CostRunTaskExecutionResult(
            "{\"executor\":\"" + scene.name() + "\"}",
            100L + task.getId(),
            "COST-" + task.getId());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("测试线程被中断", ex);
      } finally {
        runningCount.decrementAndGet();
      }
    }
  }
}
