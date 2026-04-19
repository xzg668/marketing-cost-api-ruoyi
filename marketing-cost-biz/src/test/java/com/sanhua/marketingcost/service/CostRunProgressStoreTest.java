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
