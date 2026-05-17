package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionCallback;
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
  private MaterialMasterSyncService materialMasterSyncService;
  private PriceLinkedCalcService priceLinkedCalcService;
  private CostRunTrialServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    partItemService = mock(CostRunPartItemService.class);
    costItemService = mock(CostRunCostItemService.class);
    resultService = mock(CostRunResultService.class);
    progressStore = new CostRunProgressStore();
    transactionTemplate = mock(TransactionTemplate.class);
    materialMasterSyncService = mock(MaterialMasterSyncService.class);
    priceLinkedCalcService = mock(PriceLinkedCalcService.class);
    service = new CostRunTrialServiceImpl(
        oaFormMapper, oaFormItemMapper,
        partItemService, costItemService, resultService,
        progressStore, transactionTemplate,
        materialMasterSyncService,
        priceLinkedCalcService);
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
  @DisplayName("T17：service.run 自身不再防重，两次提交都进入 doRun（防重移至 controller.enqueue）")
  void testServiceRunNoLongerOwnsDedup() throws Exception {
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
    // T17 起：防重责任在 controller.enqueue，service.run 不再拦截，两次都执行
    verify(transactionTemplate, times(2)).execute(any());
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

  @Test
  @DisplayName("V3-10：成本试算先自动刷新本 OA 联动价，再用同一 oaNo 计算部品明细")
  void costTrialRefreshesLinkedCalcBeforePartPricing() throws Exception {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-V3"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(priceLinkedCalcService.refresh("OA-V3")).thenReturn(1);

    OaForm form = new OaForm();
    form.setId(100L);
    form.setOaNo("OA-V3");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(200L);
    formItem.setMaterialNo("MAT-LINKED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));

    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setOaNo("OA-V3");
    part.setProductCode("MAT-LINKED");
    part.setPartCode("MAT-LINKED");
    part.setPartQty(BigDecimal.ONE);
    part.setUnitPrice(new BigDecimal("72.000000"));
    part.setPriceSource("联动价");
    when(partItemService.listByOaNo(eq("OA-V3"), any(java.util.function.IntConsumer.class)))
        .thenReturn(List.of(part));
    when(costItemService.listByMaterialCodes(
        eq("OA-V3"),
        eq("MAT-LINKED"),
        eq(Set.of("MAT-LINKED")),
        any(java.util.function.IntConsumer.class)))
        .thenReturn(List.<CostRunCostItemDto>of());

    CostRunTrialResponse response = service.run("OA-V3").get();

    assertEquals(1, response.getProductCount());
    assertEquals(1, response.getPartCount());
    InOrder inOrder = inOrder(materialMasterSyncService, priceLinkedCalcService, partItemService);
    inOrder.verify(materialMasterSyncService).syncByOaNo("OA-V3");
    inOrder.verify(priceLinkedCalcService).refresh("OA-V3");
    inOrder.verify(partItemService)
        .listByOaNo(eq("OA-V3"), any(java.util.function.IntConsumer.class));
  }
}
