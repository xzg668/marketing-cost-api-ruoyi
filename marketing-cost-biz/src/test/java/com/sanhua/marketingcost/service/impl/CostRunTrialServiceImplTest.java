package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.mockito.ArgumentCaptor;
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
  private CostRunPartItemMapper costRunPartItemMapper;
  private CostRunPartItemService partItemService;
  private CostRunCostItemService costItemService;
  private CostRunResultService resultService;
  private CostRunProgressStore progressStore;
  private TransactionTemplate transactionTemplate;
  private MaterialMasterSyncService materialMasterSyncService;
  private MaterialPriceRouterService materialPriceRouterService;
  private LinkedPriceEnsureService linkedPriceEnsureService;
  private PricePrepareReadinessService pricePrepareReadinessService;
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
    costRunPartItemMapper = mock(CostRunPartItemMapper.class);
    partItemService = mock(CostRunPartItemService.class);
    costItemService = mock(CostRunCostItemService.class);
    resultService = mock(CostRunResultService.class);
    progressStore = new CostRunProgressStore();
    transactionTemplate = mock(TransactionTemplate.class);
    materialMasterSyncService = mock(MaterialMasterSyncService.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    pricePrepareReadinessService = mock(PricePrepareReadinessService.class);
    when(pricePrepareReadinessService.check(anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-OK", LocalDate.now().toString().substring(0, 7), "SUCCESS"));
    service = new CostRunTrialServiceImpl(
        oaFormMapper, oaFormItemMapper, costRunPartItemMapper,
        partItemService, costItemService, resultService,
        progressStore, transactionTemplate,
        materialMasterSyncService,
        materialPriceRouterService,
        linkedPriceEnsureService,
        pricePrepareReadinessService);
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
  @DisplayName("LPE-08：成本试算先按需确保本次联动价，再用同一 oaNo 计算部品明细")
  void costTrialEnsuresLinkedCalcBeforePartPricing() throws Exception {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-V3"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));

    OaForm form = new OaForm();
    form.setId(100L);
    form.setOaNo("OA-V3");
    form.setBusinessUnitType("COMMERCIAL");
    form.setApplyDate(currentDate.minusMonths(1));
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(200L);
    formItem.setMaterialNo("MAT-LINKED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));

    CostRunPartItemDto linkedBase = part("MAT-LINKED");
    CostRunPartItemDto fixedBase = part("MAT-FIXED");
    when(costRunPartItemMapper.selectBaseByOaNo("OA-V3")).thenReturn(List.of(linkedBase, fixedBase));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-LINKED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-LINKED", PriceTypeEnum.LINKED)));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-FIXED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-FIXED", PriceTypeEnum.FIXED)));
    when(linkedPriceEnsureService.ensure(any())).thenReturn(new LinkedPriceEnsureResult());

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
    InOrder inOrder = inOrder(materialMasterSyncService, linkedPriceEnsureService, partItemService);
    inOrder.verify(materialMasterSyncService).syncByOaNo("OA-V3");
    inOrder.verify(linkedPriceEnsureService).ensure(any());
    inOrder.verify(partItemService)
        .listByOaNo(eq("OA-V3"), any(java.util.function.IntConsumer.class));

    ArgumentCaptor<LinkedPriceEnsureRequest> requestCaptor =
        ArgumentCaptor.forClass(LinkedPriceEnsureRequest.class);
    verify(linkedPriceEnsureService).ensure(requestCaptor.capture());
    LinkedPriceEnsureRequest request = requestCaptor.getValue();
    assertEquals("OA-V3", request.getOaNo());
    assertEquals("COMMERCIAL", request.getBusinessUnitType());
    assertEquals(currentPeriod, request.getPricingMonth());
    assertEquals(Set.of("MAT-LINKED"), request.getItemCodes());
  }

  @Test
  @DisplayName("LPE-08：没有联动价路由时不调用 ensure，仍继续原实时成本流程")
  void costTrialSkipsEnsureWhenNoLinkedRoute() throws Exception {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-NO-LINKED"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));

    OaForm form = new OaForm();
    form.setId(101L);
    form.setOaNo("OA-NO-LINKED");
    form.setBusinessUnitType("COMMERCIAL");
    form.setApplyDate(currentDate.minusMonths(1));
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(201L);
    formItem.setMaterialNo("MAT-FIXED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-NO-LINKED"))
        .thenReturn(List.of(part("MAT-FIXED")));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-FIXED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-FIXED", PriceTypeEnum.FIXED)));
    when(partItemService.listByOaNo(eq("OA-NO-LINKED"), any(java.util.function.IntConsumer.class)))
        .thenReturn(List.of(part("MAT-FIXED")));
    when(costItemService.listByMaterialCodes(
        eq("OA-NO-LINKED"),
        eq("MAT-FIXED"),
        eq(Set.of("MAT-FIXED")),
        any(java.util.function.IntConsumer.class)))
        .thenReturn(List.<CostRunCostItemDto>of());

    CostRunTrialResponse response = service.run("OA-NO-LINKED").get();

    assertEquals(1, response.getProductCount());
    verify(linkedPriceEnsureService, never()).ensure(any());
    verify(partItemService)
        .listByOaNo(eq("OA-NO-LINKED"), any(java.util.function.IntConsumer.class));
  }

  @Test
  @DisplayName("PPR-09：价格准备未完成时当前阶段只写进度提示并继续实时成本")
  void costTrialContinuesWithPricePrepareWarning() throws Exception {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-PPR-WARN"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(pricePrepareReadinessService.check("OA-PPR-WARN", currentPeriod))
        .thenReturn(PricePrepareReadinessResult.notReady(
            "NOT_PREPARED",
            true,
            false,
            "当前期间 " + currentPeriod + " 尚未执行价格准备，实时成本将继续，结果可能缺价",
            null,
            currentPeriod,
            null,
            0,
            List.of()));

    OaForm form = new OaForm();
    form.setId(103L);
    form.setOaNo("OA-PPR-WARN");
    form.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(203L);
    formItem.setMaterialNo("MAT-FIXED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-PPR-WARN"))
        .thenReturn(List.of(part("MAT-FIXED")));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-FIXED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-FIXED", PriceTypeEnum.FIXED)));
    when(partItemService.listByOaNo(eq("OA-PPR-WARN"), any(java.util.function.IntConsumer.class)))
        .thenReturn(List.of(part("MAT-FIXED")));
    when(costItemService.listByMaterialCodes(
        eq("OA-PPR-WARN"),
        eq("MAT-FIXED"),
        eq(Set.of("MAT-FIXED")),
        any(java.util.function.IntConsumer.class)))
        .thenReturn(List.<CostRunCostItemDto>of());

    CostRunTrialResponse response = service.run("OA-PPR-WARN").get();

    assertEquals(1, response.getProductCount());
    assertEquals("NOT_PREPARED", response.getPricePrepareReadiness().getStatus());
    assertEquals("DONE", progressStore.get("OA-PPR-WARN").getStatus());
    assertTrue(progressStore.get("OA-PPR-WARN").getMessage().contains("尚未执行价格准备"));
    verify(partItemService)
        .listByOaNo(eq("OA-PPR-WARN"), any(java.util.function.IntConsumer.class));
  }

  @Test
  @DisplayName("PPR-09：正式阻断开关打开时价格准备未就绪会阻断实时成本")
  void costTrialBlocksWhenPricePrepareStrictModeBlocks() {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-PPR-BLOCK"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(pricePrepareReadinessService.check("OA-PPR-BLOCK", currentPeriod))
        .thenReturn(PricePrepareReadinessResult.notReady(
            "PARTIAL",
            false,
            true,
            "价格准备存在缺口 2 项，已阻断实时成本",
            "PPR-BLOCK",
            currentPeriod,
            "PARTIAL",
            2,
            List.of("MAT-1: 缺价")));

    OaForm form = new OaForm();
    form.setId(104L);
    form.setOaNo("OA-PPR-BLOCK");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(204L);
    formItem.setMaterialNo("MAT-FIXED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> service.run("OA-PPR-BLOCK").get());

    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("已阻断实时成本"));
    assertEquals("ERROR", progressStore.get("OA-PPR-BLOCK").getStatus());
    verify(linkedPriceEnsureService, never()).ensure(any());
    verify(partItemService, never()).listByOaNo(any(), any());
  }

  @Test
  @DisplayName("LPE-08：ensure 返回失败项时实时成本进入失败状态并停止后续取价")
  void costTrialFailsWhenEnsureReturnsFailedItems() {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-ENSURE-FAIL"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));

    OaForm form = new OaForm();
    form.setId(102L);
    form.setOaNo("OA-ENSURE-FAIL");
    form.setBusinessUnitType("COMMERCIAL");
    form.setApplyDate(currentDate.minusMonths(1));
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(202L);
    formItem.setMaterialNo("MAT-LINKED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-ENSURE-FAIL"))
        .thenReturn(List.of(part("MAT-LINKED")));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-LINKED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-LINKED", PriceTypeEnum.LINKED)));
    LinkedPriceEnsureResult failed = new LinkedPriceEnsureResult();
    failed.addFailedItem("MAT-LINKED", "公式变量缺失");
    when(linkedPriceEnsureService.ensure(any())).thenReturn(failed);

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> service.run("OA-ENSURE-FAIL").get());

    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("联动价按需确保失败"));
    assertTrue(progressStore.get("OA-ENSURE-FAIL").getMessage().contains("公式变量缺失"));
    verify(partItemService, never()).listByOaNo(any(), any());
  }

  private static CostRunPartItemDto part(String partCode) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(partCode);
    dto.setProductCode(partCode);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceTypeRoute route(String materialCode, PriceTypeEnum priceType) {
    return new PriceTypeRoute(materialCode, null, priceType, 1, null, null, "manual",
        priceType.getDbText());
  }
}
