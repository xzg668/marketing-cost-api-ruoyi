package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuoteCostRunBatchPrerequisiteServiceTest {

  @Test
  void oneQuoteBatchSynchronizesMaterialMasterExactlyOnce() {
    FakeBatchStore store = new FakeBatchStore();
    CountingMaterialSync sync = new CountingMaterialSync();
    QuoteCostRunBatchPrerequisiteService service =
        new QuoteCostRunBatchPrerequisiteService(store.mapper(), store.taskMapper(), sync);

    CostRunBatchPrerequisiteService.PreparationSummary first =
        service.preparePendingQuoteBatches("worker-1", 20, 10);
    CostRunBatchPrerequisiteService.PreparationSummary second =
        service.preparePendingQuoteBatches("worker-1", 20, 10);

    assertThat(first).isEqualTo(new CostRunBatchPrerequisiteService.PreparationSummary(1, 1, 1, 0));
    assertThat(second).isEqualTo(CostRunBatchPrerequisiteService.PreparationSummary.empty());
    assertThat(sync.attempts.get()).isEqualTo(1);
    assertThat(store.status).isEqualTo("SUCCESS");
    assertThat(store.controlVersion).isEqualTo(2);
  }

  @Test
  void twoWorkersCompetingForSameOaStillSynchronizeExactlyOnce() throws Exception {
    FakeBatchStore store = new FakeBatchStore();
    store.selectBarrier = new CyclicBarrier(2);
    CountingMaterialSync sync = new CountingMaterialSync();
    QuoteCostRunBatchPrerequisiteService service =
        new QuoteCostRunBatchPrerequisiteService(store.mapper(), store.taskMapper(), sync);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<CostRunBatchPrerequisiteService.PreparationSummary> first =
          pool.submit(() -> service.preparePendingQuoteBatches("worker-1", 20, 10));
      Future<CostRunBatchPrerequisiteService.PreparationSummary> second =
          pool.submit(() -> service.preparePendingQuoteBatches("worker-2", 20, 10));

      List<CostRunBatchPrerequisiteService.PreparationSummary> summaries =
          List.of(first.get(), second.get());

      assertThat(summaries).extracting(CostRunBatchPrerequisiteService.PreparationSummary::claimedCount)
          .containsExactlyInAnyOrder(1, 0);
      assertThat(summaries).extracting(CostRunBatchPrerequisiteService.PreparationSummary::successCount)
          .containsExactlyInAnyOrder(1, 0);
      assertThat(sync.attempts.get()).isEqualTo(1);
      assertThat(store.status).isEqualTo("SUCCESS");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void failedPreparationBlocksAutomaticExecutionAndCanRetryAfterResubmit() {
    FakeBatchStore store = new FakeBatchStore();
    CountingMaterialSync sync = new CountingMaterialSync();
    sync.fail = true;
    QuoteCostRunBatchPrerequisiteService service =
        new QuoteCostRunBatchPrerequisiteService(store.mapper(), store.taskMapper(), sync);

    CostRunBatchPrerequisiteService.PreparationSummary failed =
        service.preparePendingQuoteBatches("worker-1", 20, 10);
    CostRunBatchPrerequisiteService.PreparationSummary notAutomaticallyRetried =
        service.preparePendingQuoteBatches("worker-1", 20, 10);

    assertThat(failed.failedCount()).isEqualTo(1);
    assertThat(notAutomaticallyRetried)
        .isEqualTo(CostRunBatchPrerequisiteService.PreparationSummary.empty());
    assertThat(store.status).isEqualTo("FAILED");
    assertThat(store.errorMessage).contains("主档同步失败");
    assertThat(store.failedTaskWrites.get()).isEqualTo(1);

    store.resetForResubmit();
    sync.fail = false;
    CostRunBatchPrerequisiteService.PreparationSummary retried =
        service.preparePendingQuoteBatches("worker-1", 20, 10);

    assertThat(retried.successCount()).isEqualTo(1);
    assertThat(sync.attempts.get()).isEqualTo(2);
    assertThat(store.status).isEqualTo("SUCCESS");
    assertThat(store.errorMessage).isNull();
  }

  @Test
  void workerIdentityIsRequiredBeforeAnyDatabaseMutation() {
    FakeBatchStore store = new FakeBatchStore();
    QuoteCostRunBatchPrerequisiteService service =
        new QuoteCostRunBatchPrerequisiteService(
            store.mapper(), store.taskMapper(), new CountingMaterialSync());

    assertThatThrownBy(() -> service.preparePendingQuoteBatches(" ", 20, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workerId");
    assertThat(store.transitionAttempts.get()).isZero();
  }

  private static class CountingMaterialSync implements MaterialMasterSyncService {

    private final AtomicInteger attempts = new AtomicInteger();
    private volatile boolean fail;

    @Override
    public SyncResult syncByOaNo(String oaNo) {
      attempts.incrementAndGet();
      if (fail) {
        throw new IllegalStateException("U9 暂时不可用");
      }
      return new SyncResult(47, 47, 47, "MM-TEST-1");
    }
  }

  private static class FakeBatchStore {

    private final AtomicInteger transitionAttempts = new AtomicInteger();
    private final AtomicInteger failedTaskWrites = new AtomicInteger();
    private String status = "PENDING";
    private int controlVersion;
    private String errorMessage;
    private CyclicBarrier selectBarrier;

    CostRunBatchMapper mapper() {
      return (CostRunBatchMapper)
          Proxy.newProxyInstance(
              CostRunBatchMapper.class.getClassLoader(),
              new Class<?>[] {CostRunBatchMapper.class},
              (proxy, method, args) -> {
                if ("selectQuotePrerequisiteCandidates".equals(method.getName())) {
                  CostRunBatch snapshot;
                  synchronized (this) {
                    snapshot = "PENDING".equals(status) ? snapshot() : null;
                  }
                  awaitSelectBarrier();
                  return snapshot == null ? List.of() : List.of(snapshot);
                }
                if ("transitionPrerequisite".equals(method.getName())) {
                  return transition(args);
                }
                if ("toString".equals(method.getName())) {
                  return "FakeCostRunBatchMapper";
                }
                throw new UnsupportedOperationException(method.toString());
              });
    }

    CostRunTaskMapper taskMapper() {
      return (CostRunTaskMapper)
          Proxy.newProxyInstance(
              CostRunTaskMapper.class.getClassLoader(),
              new Class<?>[] {CostRunTaskMapper.class},
              (proxy, method, args) -> {
                if ("markQuoteTasksFailedByPrerequisite".equals(method.getName())) {
                  failedTaskWrites.incrementAndGet();
                  return 47;
                }
                if ("toString".equals(method.getName())) {
                  return "FakeCostRunTaskMapper";
                }
                throw new UnsupportedOperationException(method.toString());
              });
    }

    private synchronized int transition(Object[] args) {
      transitionAttempts.incrementAndGet();
      String batchNo = (String) args[0];
      int executionNo = (Integer) args[1];
      int expectedControlVersion = (Integer) args[2];
      String expectedStatus = (String) args[3];
      String nextStatus = (String) args[4];
      if (!"CRQ-TEST-1".equals(batchNo)
          || executionNo != 1
          || controlVersion != expectedControlVersion
          || !status.equals(expectedStatus)) {
        return 0;
      }
      status = nextStatus;
      controlVersion++;
      errorMessage = "FAILED".equals(nextStatus) ? (String) args[5] : null;
      return 1;
    }

    private synchronized CostRunBatch snapshot() {
      CostRunBatch batch = new CostRunBatch();
      batch.setBatchNo("CRQ-TEST-1");
      batch.setScene("QUOTE");
      batch.setSourceNo("OA-TEST-47");
      batch.setPricingMonth("2026-08");
      batch.setExecutionNo(1);
      batch.setPrerequisiteStatus(status);
      batch.setControlVersion(controlVersion);
      batch.setStatus("PENDING");
      batch.setUpdatedAt(LocalDateTime.now());
      return batch;
    }

    private void awaitSelectBarrier() throws Exception {
      CyclicBarrier barrier = selectBarrier;
      if (barrier != null) {
        barrier.await();
      }
    }

    private synchronized void resetForResubmit() {
      status = "PENDING";
      controlVersion++;
      errorMessage = null;
      selectBarrier = null;
    }
  }
}
