package com.sanhua.marketingcost.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CostRunProgressStore 线程安全测试。
 * 覆盖：TOCTOU 竞态修复、不可变 ProgressState、并发防重、内存清理。
 */
class CostRunProgressStoreTest {

  private CostRunProgressStore store;

  @BeforeEach
  void setUp() {
    store = new CostRunProgressStore();
  }

  // ========== 基本功能 ==========

  @Test
  @DisplayName("初始状态应为 IDLE")
  void testInitialStateIsIdle() {
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("IDLE", resp.getStatus());
    assertEquals(0, resp.getPercent());
  }

  @Test
  @DisplayName("start 后状态应为 RUNNING")
  void testStartSetsRunning() {
    boolean started = store.start("OA-001");
    assertTrue(started);
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("RUNNING", resp.getStatus());
    assertEquals(0, resp.getPercent());
  }

  @Test
  @DisplayName("update 应更新百分比并保持 RUNNING")
  void testUpdatePercent() {
    store.start("OA-001");
    store.update("OA-001", 50);
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("RUNNING", resp.getStatus());
    assertEquals(50, resp.getPercent());
  }

  @Test
  @DisplayName("complete 应设置 100% 和 DONE")
  void testComplete() {
    store.start("OA-001");
    store.complete("OA-001");
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("DONE", resp.getStatus());
    assertEquals(100, resp.getPercent());
  }

  @Test
  @DisplayName("fail 应设置 ERROR 和错误消息")
  void testFail() {
    store.start("OA-001");
    store.fail("OA-001", "数据库异常");
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("ERROR", resp.getStatus());
    assertEquals("数据库异常", resp.getMessage());
  }

  @Test
  @DisplayName("T18(c) fail 保留当前 percent，前端可卡在失败位置")
  void testFailKeepsPercent() {
    store.start("OA-FAIL");
    store.update("OA-FAIL", 5);  // 模拟主档同步完成
    store.fail("OA-FAIL", "主档同步失败");
    CostRunProgressResponse resp = store.get("OA-FAIL");
    assertEquals("ERROR", resp.getStatus());
    assertEquals(5, resp.getPercent(), "fail 应保留 prev percent，不能回退到 0");
  }

  @Test
  @DisplayName("T18(g) update 只推不退：percent 倒退被忽略")
  void testUpdateMonotonic() {
    store.start("OA-MONO");
    store.update("OA-MONO", 60);
    store.update("OA-MONO", 30); // 倒退，应被忽略
    assertEquals(60, store.get("OA-MONO").getPercent());
    store.update("OA-MONO", 80);
    assertEquals(80, store.get("OA-MONO").getPercent());
  }

  @Test
  @DisplayName("remove 后状态应回到 IDLE")
  void testRemoveResetsToIdle() {
    store.start("OA-001");
    store.complete("OA-001");
    store.remove("OA-001");
    CostRunProgressResponse resp = store.get("OA-001");
    assertEquals("IDLE", resp.getStatus());
  }

  @Test
  @DisplayName("null oaNo 应安全处理")
  void testNullOaNoSafe() {
    assertFalse(store.start(null));
    store.update(null, 50);
    store.complete(null);
    store.fail(null, "err");
    store.remove(null);
    CostRunProgressResponse resp = store.get(null);
    assertEquals("IDLE", resp.getStatus());
  }

  // ========== 并发防重 ==========

  @Test
  @DisplayName("同一 OA 单号 RUNNING 中不允许重复 start")
  void testRejectDuplicateStart() {
    assertTrue(store.start("OA-001"));
    assertFalse(store.start("OA-001"));
  }

  @Test
  @DisplayName("DONE 状态可以重新 start")
  void testCanRestartAfterDone() {
    store.start("OA-001");
    store.complete("OA-001");
    assertTrue(store.start("OA-001"));
    assertEquals("RUNNING", store.get("OA-001").getStatus());
  }

  @Test
  @DisplayName("ERROR 状态可以重新 start")
  void testCanRestartAfterError() {
    store.start("OA-001");
    store.fail("OA-001", "err");
    assertTrue(store.start("OA-001"));
    assertEquals("RUNNING", store.get("OA-001").getStatus());
  }

  // ========== 并发线程安全 ==========

  @Test
  @DisplayName("并发 start 同一 OA 单号，只有一个线程成功")
  void testConcurrentStartOnlyOneSucceeds() throws InterruptedException {
    int threadCount = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threadCount; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          go.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        results.add(store.start("OA-RACE"));
      });
    }

    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    long successCount = results.stream().filter(Boolean::booleanValue).count();
    assertEquals(1, successCount, "只有一个线程应该成功 start");
    assertEquals("RUNNING", store.get("OA-RACE").getStatus());
  }

  @Test
  @DisplayName("并发读写不会抛异常或产生不一致状态")
  void testConcurrentReadWriteNoException() throws InterruptedException {
    int threadCount = 10;
    int iterations = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    store.start("OA-RW");

    for (int i = 0; i < threadCount; i++) {
      final int tid = i;
      pool.submit(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            if (tid % 3 == 0) {
              store.update("OA-RW", j % 100);
            } else if (tid % 3 == 1) {
              CostRunProgressResponse resp = store.get("OA-RW");
              assertNotNull(resp.getStatus());
            } else {
              store.update("OA-RW", (j * tid) % 100);
            }
          }
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await(15, TimeUnit.SECONDS);
    pool.shutdown();

    CostRunProgressResponse resp = store.get("OA-RW");
    assertNotNull(resp.getStatus());
    assertTrue(resp.getPercent() >= 0 && resp.getPercent() <= 100);
  }

  // ========== T17 排队 ==========

  @Test
  @DisplayName("T17 enqueue：多 OA 入队后 queuePos 按入队顺序 1/2/3 递增；queueDepth=入队总数")
  void testEnqueueQueuePos() {
    assertTrue(store.enqueue("OA-A"));
    assertTrue(store.enqueue("OA-B"));
    assertTrue(store.enqueue("OA-C"));
    assertEquals(1, store.getQueuePos("OA-A"));
    assertEquals(2, store.getQueuePos("OA-B"));
    assertEquals(3, store.getQueuePos("OA-C"));
    assertEquals(3, store.getQueueDepth());
    assertEquals("QUEUED", store.get("OA-A").getStatus());
    assertEquals(1, store.get("OA-A").getQueuePos());
  }

  @Test
  @DisplayName("T17 markRunning：QUEUED→RUNNING 后 queuePos=0；后续排队的位置前移")
  void testMarkRunningShiftsQueue() {
    store.enqueue("OA-A");
    store.enqueue("OA-B");
    store.enqueue("OA-C");
    store.markRunning("OA-A");
    assertEquals(0, store.getQueuePos("OA-A"));
    // B 现在变成排队第 1，C 排队第 2
    assertEquals(1, store.getQueuePos("OA-B"));
    assertEquals(2, store.getQueuePos("OA-C"));
    assertEquals(2, store.getQueueDepth());
  }

  @Test
  @DisplayName("T17 enqueue 防重：已 QUEUED/RUNNING 的 OA 第二次 enqueue 返 false")
  void testEnqueueDuplicate() {
    assertTrue(store.enqueue("OA-A"));
    assertFalse(store.enqueue("OA-A"));   // QUEUED 中
    store.markRunning("OA-A");
    assertFalse(store.enqueue("OA-A"));   // RUNNING 中
    store.complete("OA-A");
    assertTrue(store.enqueue("OA-A"));    // DONE 后可重入
  }

  @Test
  @DisplayName("不可变 ProgressState — get 读到的状态字段一致")
  void testImmutableStateConsistency() throws InterruptedException {
    store.start("OA-IMM");
    int threadCount = 8;
    int iterations = 1000;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<String> violations = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threadCount; i++) {
      final int tid = i;
      pool.submit(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            if (tid < 4) {
              if (j % 2 == 0) {
                store.update("OA-IMM", j % 100);
              } else {
                store.complete("OA-IMM");
              }
            } else {
              CostRunProgressResponse resp = store.get("OA-IMM");
              // DONE 状态必须是 100%，RUNNING 不能是 100%（除非刚好更新到100）
              if ("DONE".equals(resp.getStatus()) && resp.getPercent() != 100) {
                violations.add("DONE but percent=" + resp.getPercent());
              }
            }
          }
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await(15, TimeUnit.SECONDS);
    pool.shutdown();
    assertTrue(violations.isEmpty(), "状态不一致: " + violations);
  }
}
