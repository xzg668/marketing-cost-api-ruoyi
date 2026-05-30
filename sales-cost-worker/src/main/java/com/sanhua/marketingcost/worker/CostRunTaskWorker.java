package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.service.CostRunTaskClaimService;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CostRunTaskWorker {

  private static final Logger log = LoggerFactory.getLogger(CostRunTaskWorker.class);

  private final CostRunTaskClaimService taskClaimService;
  private final CostRunTaskProgressService progressService;
  private final MonthlyRepriceProgressService monthlyRepriceProgressService;
  private final CostRunTaskExecutorRegistry executorRegistry;
  private final CostRunWorkerProperties properties;

  public CostRunTaskWorker(
      CostRunTaskClaimService taskClaimService,
      CostRunTaskProgressService progressService,
      ObjectProvider<MonthlyRepriceProgressService> monthlyRepriceProgressService,
      CostRunTaskExecutorRegistry executorRegistry,
      CostRunWorkerProperties properties) {
    this.taskClaimService = taskClaimService;
    this.progressService = progressService;
    this.monthlyRepriceProgressService = monthlyRepriceProgressService.getIfAvailable();
    this.executorRegistry = executorRegistry;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${cost.run.worker.poll-interval-ms:5000}")
  public void scheduledScan() {
    scanOnce();
  }

  public int scanOnce() {
    if (!properties.isEnabled()) {
      log.debug("cost run worker disabled");
      return 0;
    }
    String workerId = properties.resolvedWorkerId();
    List<CostRunTask> tasks =
        taskClaimService.claimTasks(
            workerId,
            properties.getScenes(),
            properties.getClaimBatchSize(),
            properties.getLockTimeoutMinutes());
    if (tasks.isEmpty()) {
      log.debug("cost run worker claim empty: workerId={}", workerId);
      return 0;
    }
    CostRunTask first = tasks.get(0);
    CostRunTaskBatchPressurePlan pressurePlan = CostRunTaskBatchPressurePlan.from(tasks);
    log.info(
        "cost run worker claimed tasks: workerId={} count={} firstTaskId={} firstBatchNo={} firstScene={}",
        workerId, tasks.size(), first.getId(), first.getBatchNo(), first.getScene());
    logBatchPressurePlan(workerId, pressurePlan);
    long startedNanos = System.nanoTime();
    List<TaskExecutionMetric> metrics = processTasks(workerId, tasks);
    logMetrics(workerId, tasks.size(), metrics, elapsedMillis(startedNanos));
    for (String batchNo : touchedBatchNos(tasks)) {
      refreshProgress(batchNo);
    }
    return tasks.size();
  }

  private List<TaskExecutionMetric> processTasks(String workerId, List<CostRunTask> tasks) {
    int threads = Math.min(properties.getThreads(), tasks.size());
    if (threads <= 1) {
      List<TaskExecutionMetric> metrics = new ArrayList<>(tasks.size());
      for (CostRunTask task : tasks) {
        metrics.add(processTask(workerId, task));
      }
      return metrics;
    }
    ExecutorService executor = Executors.newFixedThreadPool(threads, workerThreadFactory(workerId));
    try {
      List<Future<TaskExecutionMetric>> futures = executor.invokeAll(
          tasks.stream().map(task -> (java.util.concurrent.Callable<TaskExecutionMetric>)
              () -> processTask(workerId, task)).toList());
      List<TaskExecutionMetric> metrics = new ArrayList<>(futures.size());
      for (Future<TaskExecutionMetric> future : futures) {
        metrics.add(future.get());
      }
      return metrics;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("cost run worker interrupted: workerId={}", workerId, ex);
      return List.of();
    } catch (ExecutionException ex) {
      log.error("cost run worker execution wrapper failed: workerId={}", workerId, ex);
      return List.of();
    } finally {
      executor.shutdownNow();
    }
  }

  TaskExecutionMetric processTask(String workerId, CostRunTask task) {
    long startedNanos = System.nanoTime();
    try {
      CostRunTaskScene scene = CostRunTaskScene.fromCode(task.getScene());
      CostRunTaskExecutionResult result = executorRegistry.get(scene).execute(task, workerId);
      boolean marked =
          taskClaimService.markSuccess(
              task.getId(),
              workerId,
              result == null ? null : result.resultSummaryJson());
      if (!marked) {
        throw new IllegalStateException("任务所有权已变更，无法标记成功");
      }
      refreshMonthlyRepriceProgress(task);
      return new TaskExecutionMetric(task.getId(), elapsedMillis(startedNanos), true);
    } catch (RuntimeException ex) {
      log.error(
          "cost run task failed: workerId={} taskId={} batchNo={} scene={}",
          workerId, task.getId(), task.getBatchNo(), task.getScene(), ex);
      recordFailure(workerId, task, ex);
      refreshMonthlyRepriceProgress(task);
      return new TaskExecutionMetric(task.getId(), elapsedMillis(startedNanos), false);
    }
  }

  private void recordFailure(String workerId, CostRunTask task, RuntimeException ex) {
    String errorMessage = ex.getMessage();
    String errorStack = stackTrace(ex);
    if (isRetryable(ex)) {
      taskClaimService.recordFailure(task.getId(), workerId, true, errorMessage, errorStack);
      return;
    }
    taskClaimService.markFailure(task.getId(), workerId, errorMessage, errorStack);
  }

  private boolean isRetryable(RuntimeException ex) {
    return !(ex instanceof IllegalArgumentException);
  }

  private ThreadFactory workerThreadFactory(String workerId) {
    AtomicInteger index = new AtomicInteger();
    String safeWorkerId = workerId.replaceAll("[^A-Za-z0-9_.-]", "_");
    return runnable -> {
      Thread thread = new Thread(runnable);
      thread.setName("cost-run-" + safeWorkerId + "-" + index.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private void refreshProgress(String batchNo) {
    try {
      progressService.refreshBatchProgress(batchNo);
    } catch (RuntimeException ex) {
      log.error("cost run batch progress refresh failed: batchNo={}", batchNo, ex);
    }
  }

  private void refreshMonthlyRepriceProgress(CostRunTask task) {
    if (monthlyRepriceProgressService == null
        || task == null
        || !CostRunTaskScene.MONTHLY_REPRICE.name().equals(task.getScene())
        || task.getSourceNo() == null
        || task.getSourceNo().isBlank()) {
      return;
    }
    try {
      monthlyRepriceProgressService.refreshProgress(task.getSourceNo());
    } catch (RuntimeException ex) {
      log.error("monthly reprice progress refresh failed: repriceNo={}", task.getSourceNo(), ex);
    }
  }

  private Set<String> touchedBatchNos(List<CostRunTask> tasks) {
    Set<String> batchNos = new LinkedHashSet<>();
    for (CostRunTask task : tasks) {
      if (task.getBatchNo() != null && !task.getBatchNo().isBlank()) {
        batchNos.add(task.getBatchNo().trim());
      }
    }
    return batchNos;
  }

  private void logMetrics(
      String workerId, int claimedCount, List<TaskExecutionMetric> metrics, long totalMillis) {
    if (metrics.isEmpty()) {
      return;
    }
    int failedCount = (int) metrics.stream().filter(metric -> !metric.success()).count();
    double avgMillis =
        metrics.stream().mapToLong(TaskExecutionMetric::durationMillis).average().orElse(0D);
    log.info(
        "cost run worker metrics: workerId={} claimed={} success={} failed={} totalMs={} avgTaskMs={} p95TaskMs={} p99TaskMs={} threads={} claimBatchSize={} scenes={}",
        workerId,
        claimedCount,
        metrics.size() - failedCount,
        failedCount,
        totalMillis,
        String.format("%.2f", avgMillis),
        percentile(metrics, 0.95D),
        percentile(metrics, 0.99D),
        properties.getThreads(),
        properties.getClaimBatchSize(),
        properties.getScenes());
  }

  private void logBatchPressurePlan(String workerId, CostRunTaskBatchPressurePlan plan) {
    log.info(
        "cost run worker batch pressure: workerId={} tasks={} scenes={} sources={} oaGroups={} products={} monthlyContextKeys={} oaCacheKeys={} bomCacheKeys={} contextViolations={} threads={} claimBatchSize={} writeBatchSize={}",
        workerId,
        plan.taskCount(),
        plan.sceneCount(),
        plan.sourceCount(),
        plan.oaGroupCount(),
        plan.productCount(),
        plan.monthlyRepriceContextKeys().size(),
        plan.oaContextKeys().size(),
        plan.bomContextKeys().size(),
        plan.contextViolations().size(),
        properties.getThreads(),
        properties.getClaimBatchSize(),
        properties.getWriteBatchSize());
    if (plan.hasContextViolations()) {
      log.warn(
          "cost run worker monthly reprice context drift: workerId={} violations={}",
          workerId,
          plan.contextViolations().stream().limit(5).toList());
    }
  }

  private long percentile(List<TaskExecutionMetric> metrics, double percentile) {
    List<Long> values = metrics.stream()
        .map(TaskExecutionMetric::durationMillis)
        .sorted(Comparator.naturalOrder())
        .toList();
    if (values.isEmpty()) {
      return 0L;
    }
    int index = (int) Math.ceil(values.size() * percentile) - 1;
    return values.get(Math.max(0, Math.min(index, values.size() - 1)));
  }

  private String stackTrace(RuntimeException ex) {
    StringWriter writer = new StringWriter();
    ex.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }

  private long elapsedMillis(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }

  record TaskExecutionMetric(Long taskId, long durationMillis, boolean success) {
  }
}
