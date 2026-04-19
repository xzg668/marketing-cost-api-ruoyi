package com.sanhua.marketingcost.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * AsyncConfig 测试。
 * 覆盖：MDC 上下文传播到异步线程、线程池基本配置。
 */
class AsyncConfigTest {

  @Test
  @DisplayName("异步线程应继承调用线程的 MDC 上下文")
  void testMdcPropagation() throws InterruptedException {
    AsyncConfig config = new AsyncConfig();
    Executor executor = config.costRunExecutor();

    String traceId = "test-trace-" + System.nanoTime();
    MDC.put("traceId", traceId);

    CountDownLatch latch = new CountDownLatch(1);
    String[] capturedTraceId = new String[1];

    executor.execute(() -> {
      capturedTraceId[0] = MDC.get("traceId");
      latch.countDown();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(traceId, capturedTraceId[0],
        "异步线程应拿到调用线程的 traceId");

    MDC.clear();
  }

  @Test
  @DisplayName("异步线程执行完毕后 MDC 应被清理")
  void testMdcCleanedAfterExecution() throws InterruptedException {
    AsyncConfig config = new AsyncConfig();
    Executor executor = config.costRunExecutor();

    MDC.put("traceId", "will-be-cleaned");

    CountDownLatch firstDone = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    String[] secondTraceId = new String[1];

    // 第一个任务：带 MDC
    executor.execute(firstDone::countDown);
    assertTrue(firstDone.await(5, TimeUnit.SECONDS));

    // 清除调用线程 MDC，提交第二个任务
    MDC.clear();
    executor.execute(() -> {
      secondTraceId[0] = MDC.get("traceId");
      secondDone.countDown();
    });

    assertTrue(secondDone.await(5, TimeUnit.SECONDS));
    assertNull(secondTraceId[0],
        "第二个任务不应继承第一个任务的 MDC（应已被清理）");
  }

  @Test
  @DisplayName("无 MDC 上下文时异步线程不应报错")
  void testNoMdcContextSafe() throws InterruptedException {
    AsyncConfig config = new AsyncConfig();
    Executor executor = config.costRunExecutor();

    MDC.clear();

    CountDownLatch latch = new CountDownLatch(1);
    boolean[] noError = {true};

    executor.execute(() -> {
      try {
        String val = MDC.get("traceId");
        assertNull(val);
      } catch (Exception e) {
        noError[0] = false;
      } finally {
        latch.countDown();
      }
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertTrue(noError[0], "无 MDC 上下文时不应抛异常");
  }
}
