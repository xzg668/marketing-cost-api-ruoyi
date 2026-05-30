package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunTaskProgressServiceImplTest {

  @Test
  void refreshBatchProgressAggregatesTaskStatuses() {
    FakeBatchMapper batchMapper = new FakeBatchMapper();
    FakeTaskMapper taskMapper =
        new FakeTaskMapper(
            List.of(
                count("SUCCESS", 2),
                count("FAILED", 1),
                count("RUNNING", 1),
                count("PENDING", 1)));
    CostRunTaskProgressServiceImpl service =
        new CostRunTaskProgressServiceImpl(batchMapper.proxy(), taskMapper.proxy());

    CostRunBatchProgressSnapshot snapshot = service.refreshBatchProgress(" BATCH-1 ");

    assertThat(snapshot.getBatchNo()).isEqualTo("BATCH-1");
    assertThat(snapshot.getStatus()).isEqualTo("RUNNING");
    assertThat(snapshot.getTotalCount()).isEqualTo(5);
    assertThat(snapshot.getSuccessCount()).isEqualTo(2);
    assertThat(snapshot.getFailedCount()).isEqualTo(1);
    assertThat(snapshot.getRunningCount()).isEqualTo(1);
    assertThat(snapshot.getPendingCount()).isEqualTo(1);
    assertThat(snapshot.getProgress()).isEqualTo(60);
    assertThat(batchMapper.last.batchNo).isEqualTo("BATCH-1");
    assertThat(batchMapper.last.status).isEqualTo("RUNNING");
    assertThat(batchMapper.last.totalCount).isEqualTo(5);
    assertThat(batchMapper.last.successCount).isEqualTo(2);
    assertThat(batchMapper.last.failedCount).isEqualTo(1);
    assertThat(batchMapper.last.skippedCount).isZero();
    assertThat(batchMapper.last.progress).isEqualTo(60);
    assertThat(batchMapper.last.startedAt).isNotNull();
    assertThat(batchMapper.last.finishedAt).isNull();
  }

  @Test
  void finishedBatchWithMixedResultsBecomesPartialFailed() {
    FakeBatchMapper batchMapper = new FakeBatchMapper();
    FakeTaskMapper taskMapper =
        new FakeTaskMapper(List.of(count("SUCCESS", 1), count("FAILED", 1)));
    CostRunTaskProgressServiceImpl service =
        new CostRunTaskProgressServiceImpl(batchMapper.proxy(), taskMapper.proxy());

    CostRunBatchProgressSnapshot snapshot = service.refreshBatchProgress("BATCH-2");

    assertThat(snapshot.getStatus()).isEqualTo("PARTIAL_FAILED");
    assertThat(snapshot.getProgress()).isEqualTo(100);
    assertThat(batchMapper.last.batchNo).isEqualTo("BATCH-2");
    assertThat(batchMapper.last.status).isEqualTo("PARTIAL_FAILED");
    assertThat(batchMapper.last.finishedAt).isNotNull();
  }

  @Test
  void emptyBatchStaysPending() {
    FakeBatchMapper batchMapper = new FakeBatchMapper();
    FakeTaskMapper taskMapper = new FakeTaskMapper(List.of());
    CostRunTaskProgressServiceImpl service =
        new CostRunTaskProgressServiceImpl(batchMapper.proxy(), taskMapper.proxy());

    CostRunBatchProgressSnapshot snapshot = service.refreshBatchProgress("BATCH-EMPTY");

    assertThat(snapshot.getStatus()).isEqualTo("PENDING");
    assertThat(snapshot.getProgress()).isZero();
    assertThat(batchMapper.last.status).isEqualTo("PENDING");
    assertThat(batchMapper.last.startedAt).isNull();
    assertThat(batchMapper.last.finishedAt).isNull();
  }

  private CostRunTaskStatusCount count(String status, long count) {
    CostRunTaskStatusCount row = new CostRunTaskStatusCount();
    row.setStatus(status);
    row.setCount(count);
    return row;
  }

  private static class FakeTaskMapper {
    private final List<CostRunTaskStatusCount> rows;

    FakeTaskMapper(List<CostRunTaskStatusCount> rows) {
      this.rows = rows;
    }

    CostRunTaskMapper proxy() {
      return (CostRunTaskMapper)
          Proxy.newProxyInstance(
              CostRunTaskMapper.class.getClassLoader(),
              new Class<?>[] {CostRunTaskMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "selectStatusCounts" -> rows;
                    case "toString" -> "FakeCostRunTaskMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private static class FakeBatchMapper {
    private UpdateArgs last;

    CostRunBatchMapper proxy() {
      return (CostRunBatchMapper)
          Proxy.newProxyInstance(
              CostRunBatchMapper.class.getClassLoader(),
              new Class<?>[] {CostRunBatchMapper.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "updateProgress" -> {
                      last =
                          new UpdateArgs(
                              (String) args[0],
                              (String) args[1],
                              (Integer) args[2],
                              (Integer) args[3],
                              (Integer) args[4],
                              (Integer) args[5],
                              (Integer) args[6],
                              (LocalDateTime) args[7],
                              (LocalDateTime) args[8]);
                      yield 1;
                    }
                    case "toString" -> "FakeCostRunBatchMapper";
                    default -> throw new UnsupportedOperationException(method.toString());
                  });
    }
  }

  private record UpdateArgs(
      String batchNo,
      String status,
      int totalCount,
      int successCount,
      int failedCount,
      int skippedCount,
      int progress,
      LocalDateTime startedAt,
      LocalDateTime finishedAt) {}
}
