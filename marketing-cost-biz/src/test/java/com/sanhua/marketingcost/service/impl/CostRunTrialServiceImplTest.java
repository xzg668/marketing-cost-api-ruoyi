package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CostRunTrialServiceImpl 单元测试。
 * 覆盖：并发防重、事务包裹、空参数处理。
 * 注意：transactionTemplate 统一 mock 为返回预设结果或抛异常，
 * 避免执行真实 doRun() 触发 MyBatis Plus lambda cache 问题。
 */
class CostRunTrialServiceImplTest {

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private CostRunPartItemService partItemService;
  private CostRunCostItemService costItemService;
  private CostRunResultService resultService;
  private CostRunProgressStore progressStore;
  private TransactionTemplate transactionTemplate;
  private CostRunTrialServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    partItemService = mock(CostRunPartItemService.class);
    costItemService = mock(CostRunCostItemService.class);
    resultService = mock(CostRunResultService.class);
    progressStore = new CostRunProgressStore();
    transactionTemplate = mock(TransactionTemplate.class);
    service = new CostRunTrialServiceImpl(
        oaFormMapper, oaFormItemMapper,
        partItemService, costItemService, resultService,
        progressStore, transactionTemplate);
  }

  // ========== 参数校验 ==========

  @Test
  @DisplayName("空 oaNo 应直接返回空响应")
  void testEmptyOaNoReturnsEmpty() throws Exception {
    CompletableFuture<CostRunTrialResponse> future = service.run("");
    assertNotNull(future.get());
  }

  @Test
  @DisplayName("null oaNo 应直接返回空响应")
  void testNullOaNoReturnsEmpty() throws Exception {
    CompletableFuture<CostRunTrialResponse> future = service.run(null);
    assertNotNull(future.get());
  }

  // ========== 并发防重 ==========

  @Test
  @DisplayName("同一 OA 单号重复提交应被拒绝")
  void testDuplicateRunRejected() throws Exception {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      Thread.sleep(100);
      return new CostRunTrialResponse();
    });

    CompletableFuture<Void> firstRun = CompletableFuture.runAsync(() -> {
      service.run("OA-DUP");
    });

    Thread.sleep(30);

    CompletableFuture<CostRunTrialResponse> secondRun = service.run("OA-DUP");
    CostRunTrialResponse resp = secondRun.get();
    assertNotNull(resp);
    firstRun.join();
    verify(transactionTemplate, times(1)).execute(any());
  }

  @Test
  @DisplayName("完成后可以重新提交")
  void testCanRerunAfterComplete() throws Exception {
    when(transactionTemplate.execute(any())).thenReturn(new CostRunTrialResponse(1, 0, 0));

    service.run("OA-RERUN").get();
    assertEquals("DONE", progressStore.get("OA-RERUN").getStatus());

    service.run("OA-RERUN").get();
    verify(transactionTemplate, times(2)).execute(any());
  }

  // ========== 事务与异常 ==========

  @Test
  @DisplayName("事务异常应设置 FAIL 状态并抛出异常")
  void testTransactionExceptionSetsFail() {
    when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("DB error"));

    ExecutionException ex = assertThrows(ExecutionException.class, () -> service.run("OA-ERR").get());
    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertEquals("DB error", ex.getCause().getMessage());
    assertEquals("ERROR", progressStore.get("OA-ERR").getStatus());
    assertEquals("DB error", progressStore.get("OA-ERR").getMessage());
  }

  @Test
  @DisplayName("OA 单号不存在应设置 FAIL 状态")
  void testOaNotFoundSetsFail() throws Exception {
    when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("OA单号不存在"));

    ExecutionException ex = assertThrows(ExecutionException.class, () -> service.run("OA-NOTFOUND").get());
    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertEquals("ERROR", progressStore.get("OA-NOTFOUND").getStatus());
    assertEquals("OA单号不存在", progressStore.get("OA-NOTFOUND").getMessage());
  }

  // ========== 正常流程 ==========

  @Test
  @DisplayName("正常试算应设置 DONE 状态")
  void testSuccessfulRunSetsDone() throws Exception {
    when(transactionTemplate.execute(any())).thenReturn(new CostRunTrialResponse(1, 5, 10));

    CompletableFuture<CostRunTrialResponse> future = service.run("OA-OK");
    CostRunTrialResponse resp = future.get();
    assertNotNull(resp);
    assertEquals("DONE", progressStore.get("OA-OK").getStatus());
    assertEquals(100, progressStore.get("OA-OK").getPercent());
  }

  @Test
  @DisplayName("oaNo 前后空格应被 trim")
  void testOaNoTrimmed() throws Exception {
    when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("OA单号不存在"));

    ExecutionException ex = assertThrows(ExecutionException.class, () -> service.run("  OA-TRIM  ").get());
    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertEquals("ERROR", progressStore.get("OA-TRIM").getStatus());
  }
}
