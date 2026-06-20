package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.config.CostRunExecutionProperties;
import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
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
  private CostRunBatchMapper costRunBatchMapper;
  private CostRunPartItemMapper costRunPartItemMapper;
  private CostRunEngine costRunEngine;
  private CostRunResultWriter costRunResultWriter;
  private CostRunProgressStore progressStore;
  private CostRunTaskSubmissionService taskSubmissionService;
  private CostRunTaskProgressService taskProgressService;
  private TransactionTemplate transactionTemplate;
  private MaterialMasterSyncService materialMasterSyncService;
  private MaterialPriceRouterService materialPriceRouterService;
  private LinkedPriceEnsureService linkedPriceEnsureService;
  private PricePrepareReadinessService pricePrepareReadinessService;
  private QuoteProductBomCostingBuildService costingBuildService;
  private PricePrepareService pricePrepareService;
  private QuoteBomStatusService quoteBomStatusService;
  private CostRunExecutionProperties executionProperties;
  private QuoteCostRunVersionService quoteCostRunVersionService;
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
    costRunBatchMapper = mock(CostRunBatchMapper.class);
    costRunPartItemMapper = mock(CostRunPartItemMapper.class);
    costRunEngine = mock(CostRunEngine.class);
    costRunResultWriter = mock(CostRunResultWriter.class);
    progressStore = new CostRunProgressStore();
    taskSubmissionService = mock(CostRunTaskSubmissionService.class);
    taskProgressService = mock(CostRunTaskProgressService.class);
    transactionTemplate = mock(TransactionTemplate.class);
    materialMasterSyncService = mock(MaterialMasterSyncService.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    linkedPriceEnsureService = mock(LinkedPriceEnsureService.class);
    pricePrepareReadinessService = mock(PricePrepareReadinessService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    pricePrepareService = mock(PricePrepareService.class);
    quoteBomStatusService = mock(QuoteBomStatusService.class);
    executionProperties = new CostRunExecutionProperties();
    quoteCostRunVersionService = mock(QuoteCostRunVersionService.class);
    executionProperties.setMode("API_SYNC");
    when(pricePrepareReadinessService.check(anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-OK", LocalDate.now().toString().substring(0, 7), "SUCCESS"));
    OaForm defaultForm = new OaForm();
    defaultForm.setId(1L);
    defaultForm.setOaNo("OA-DEFAULT");
    defaultForm.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(defaultForm);
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of());
    PricePrepareGenerateResult prepareResult = new PricePrepareGenerateResult();
    prepareResult.setPrepareNo("PPR-GENERATED");
    prepareResult.setStatus("SUCCESS");
    when(pricePrepareService.generate(any())).thenReturn(prepareResult);
    when(quoteBomStatusService.checkForCostRun(anyString())).thenReturn(bomResponse("MAT-READY", "SYNCED"));
    when(quoteCostRunVersionService.createTrial(anyString(), any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          QuoteCostRunVersion version = new QuoteCostRunVersion();
          version.setId(9000L + ((Long) invocation.getArgument(1)));
          version.setCostRunNo("TRIAL-" + invocation.getArgument(1));
          version.setOaNo(invocation.getArgument(0));
          version.setOaFormItemId(invocation.getArgument(1));
          version.setProductCode(invocation.getArgument(2));
          version.setPricingMonth(invocation.getArgument(3));
          version.setResultPeriod(invocation.getArgument(4));
          version.setPricePrepareNo((String) invocation.getArgument(5));
          version.setPriceTypeConfirmNo((String) invocation.getArgument(6));
          version.setBomConfirmNo((String) invocation.getArgument(7));
          version.setBusinessUnitType((String) invocation.getArgument(8));
          return version;
        });
    service = new CostRunTrialServiceImpl(
        oaFormMapper, oaFormItemMapper, costRunBatchMapper, costRunPartItemMapper,
        costRunEngine, costRunResultWriter,
        progressStore, taskSubmissionService, taskProgressService, transactionTemplate,
        materialMasterSyncService,
        materialPriceRouterService,
        linkedPriceEnsureService,
        pricePrepareReadinessService,
        costingBuildService,
        pricePrepareService,
        quoteBomStatusService,
        executionProperties,
        quoteCostRunVersionService);
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

  @Test
  @DisplayName("T24：默认执行模式为 API_SYNC，仍进入当前事务链路")
  void defaultExecutionModeKeepsApiSync() throws Exception {
    when(transactionTemplate.execute(any())).thenReturn(new CostRunTrialResponse(1, 0, 0));

    CostRunTrialResponse response = service.run("OA-API-SYNC").get();

    assertEquals(1, response.getProductCount());
    verify(transactionTemplate).execute(any());
  }

  @Test
  @DisplayName("T31：TASK_WORKER 只提交 QUOTE 任务，不在 API 进程直接跑 CostRunEngine")
  void taskWorkerModeSubmitsQuoteTaskWithoutRunningEngine() throws Exception {
    executionProperties.setMode("TASK_WORKER");
    when(taskSubmissionService.submitQuote("OA-TASK"))
        .thenReturn(CostRunTaskSubmissionResult.of(
            "CRQ-001", "QUOTE", "OA-TASK", "PENDING", 2, 2, 0, false));

    CostRunTrialResponse response = service.run("OA-TASK").get();

    assertEquals("CRQ-001", response.getBatchNo());
    assertEquals("TASK_WORKER", response.getExecutionMode());
    assertEquals(2, response.getProductCount());
    assertEquals(2, response.getTaskCount());
    assertEquals("QUEUED", progressStore.get("OA-TASK").getStatus());
    verify(taskSubmissionService).submitQuote("OA-TASK");
    verify(transactionTemplate, never()).execute(any());
    verify(costRunEngine, never()).run(any());
  }

  @Test
  @DisplayName("T37：TASK_WORKER 带灰度业务单元时，未命中业务单元回退 API_SYNC")
  void taskWorkerGrayBusinessUnitFallsBackToApiSyncWhenNotMatched() throws Exception {
    executionProperties.setMode("TASK_WORKER");
    executionProperties.setGrayBusinessUnits(Set.of("COMMERCIAL"));
    when(transactionTemplate.execute(any())).thenReturn(new CostRunTrialResponse(1, 0, 0));

    CostRunTrialResponse response =
        service.run("OA-GRAY-BU-MISS", "alice", "HOUSEHOLD").get();

    assertEquals(1, response.getProductCount());
    verify(transactionTemplate).execute(any());
    verify(taskSubmissionService, never()).submitQuote(anyString());
  }

  @Test
  @DisplayName("T37：TASK_WORKER 带灰度业务单元时，命中业务单元提交 QUOTE 任务")
  void taskWorkerGrayBusinessUnitSubmitsQuoteTaskWhenMatched() throws Exception {
    executionProperties.setMode("TASK_WORKER");
    executionProperties.setGrayBusinessUnits(Set.of("COMMERCIAL"));
    when(taskSubmissionService.submitQuote("OA-GRAY-BU-HIT"))
        .thenReturn(CostRunTaskSubmissionResult.of(
            "CRQ-GRAY-BU", "QUOTE", "OA-GRAY-BU-HIT", "PENDING", 1, 1, 0, false));

    CostRunTrialResponse response =
        service.run("OA-GRAY-BU-HIT", "alice", "COMMERCIAL").get();

    assertEquals("CRQ-GRAY-BU", response.getBatchNo());
    assertEquals("TASK_WORKER", response.getExecutionMode());
    verify(taskSubmissionService).submitQuote("OA-GRAY-BU-HIT");
    verify(transactionTemplate, never()).execute(any());
  }

  @Test
  @DisplayName("T37：TASK_WORKER 带灰度用户时，命中用户提交 QUOTE 任务")
  void taskWorkerGrayUserSubmitsQuoteTaskWhenMatched() throws Exception {
    executionProperties.setMode("TASK_WORKER");
    executionProperties.setGrayUsers(Set.of("alice"));
    when(taskSubmissionService.submitQuote("OA-GRAY-USER"))
        .thenReturn(CostRunTaskSubmissionResult.of(
            "CRQ-GRAY-USER", "QUOTE", "OA-GRAY-USER", "PENDING", 1, 1, 0, false));

    CostRunTrialResponse response =
        service.run("OA-GRAY-USER", "ALICE", "HOUSEHOLD").get();

    assertEquals("CRQ-GRAY-USER", response.getBatchNo());
    assertEquals("TASK_WORKER", response.getExecutionMode());
    verify(taskSubmissionService).submitQuote("OA-GRAY-USER");
    verify(transactionTemplate, never()).execute(any());
  }

  @Test
  @DisplayName("T31：TASK_WORKER 进度查询按 oaNo 映射到最新 QUOTE 批次")
  void taskWorkerProgressUsesLatestQuoteBatch() {
    executionProperties.setMode("TASK_WORKER");
    CostRunBatch batch = new CostRunBatch();
    batch.setBatchNo("CRQ-001");
    when(costRunBatchMapper.selectOne(any())).thenReturn(batch);
    CostRunBatchProgressSnapshot snapshot = new CostRunBatchProgressSnapshot();
    snapshot.setBatchNo("CRQ-001");
    snapshot.setStatus("SUCCESS");
    snapshot.setProgress(100);
    when(taskProgressService.refreshBatchProgress("CRQ-001")).thenReturn(snapshot);

    var response = service.progress(" OA-TASK ");

    assertEquals("DONE", response.getStatus());
    assertEquals(100, response.getPercent());
    verify(taskProgressService).refreshBatchProgress("CRQ-001");
  }

  @Test
  @DisplayName("T37：TASK_WORKER 灰度未命中时，进度仍读 API_SYNC 内存进度")
  void progressUsesApiSyncStoreWhenTaskWorkerGrayFilterMisses() {
    executionProperties.setMode("TASK_WORKER");
    executionProperties.setGrayUsers(Set.of("alice"));
    progressStore.enqueue("OA-GRAY-PROGRESS");

    var response = service.progress("OA-GRAY-PROGRESS", "bob", "HOUSEHOLD");

    assertEquals("QUEUED", response.getStatus());
    verify(costRunBatchMapper, never()).selectOne(any());
    verify(taskProgressService, never()).refreshBatchProgress(anyString());
  }

  @Test
  @DisplayName("T31：DUAL_COMPARE 先执行旧链路写正式结果，再提交 QUOTE 影子任务")
  void dualCompareRunsApiSyncThenSubmitsShadowTask() throws Exception {
    executionProperties.setMode("DUAL_COMPARE");
    when(transactionTemplate.execute(any())).thenReturn(new CostRunTrialResponse(1, 5, 10));
    when(taskSubmissionService.submitQuote("OA-DUAL"))
        .thenReturn(CostRunTaskSubmissionResult.of(
            "CRQ-DUAL", "QUOTE", "OA-DUAL", "PENDING", 1, 1, 0, false));

    CostRunTrialResponse response = service.run("OA-DUAL").get();

    assertEquals(1, response.getProductCount());
    assertEquals(5, response.getPartCount());
    assertEquals(10, response.getCostItemCount());
    assertEquals("CRQ-DUAL", response.getBatchNo());
    assertEquals("DUAL_COMPARE", response.getExecutionMode());
    InOrder inOrder = inOrder(transactionTemplate, taskSubmissionService);
    inOrder.verify(transactionTemplate).execute(any());
    inOrder.verify(taskSubmissionService).submitQuote("OA-DUAL");
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
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-V3", "MAT-LINKED", List.of(part), List.of()));

    CostRunTrialResponse response = service.run("OA-V3").get();

    assertEquals(1, response.getProductCount());
    assertEquals(1, response.getPartCount());
    InOrder inOrder = inOrder(materialMasterSyncService, linkedPriceEnsureService, costRunEngine);
    inOrder.verify(materialMasterSyncService).syncByOaNo("OA-V3");
    inOrder.verify(linkedPriceEnsureService).ensure(any());
    inOrder.verify(costRunEngine).run(any(CostRunContext.class));

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
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-NO-LINKED", "MAT-FIXED", List.of(part("MAT-FIXED")), List.of()));

    CostRunTrialResponse response = service.run("OA-NO-LINKED").get();

    assertEquals(1, response.getProductCount());
    verify(linkedPriceEnsureService, never()).ensure(any());
    verify(costRunEngine).run(any(CostRunContext.class));
  }

  @Test
  @DisplayName("按 OA 明细行选择试算时只核算选中产品")
  void costTrialRunsOnlySelectedOaFormItems() throws Exception {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-SELECT"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    QuoteBomStatusItemResponse unselectedNoBom = new QuoteBomStatusItemResponse();
    unselectedNoBom.setOaFormItemId(301L);
    unselectedNoBom.setProductCode("MAT-UNSELECTED");
    unselectedNoBom.setBomStatus("NO_BOM");
    QuoteBomStatusItemResponse selectedReady = new QuoteBomStatusItemResponse();
    selectedReady.setOaFormItemId(302L);
    selectedReady.setProductCode("MAT-SELECTED");
    selectedReady.setBomStatus("SYNCED");
    QuoteBomStatusResponse statusResponse = new QuoteBomStatusResponse();
    statusResponse.setItems(List.of(unselectedNoBom, selectedReady));
    when(quoteBomStatusService.checkForCostRun("OA-SELECT")).thenReturn(statusResponse);

    OaForm form = new OaForm();
    form.setId(300L);
    form.setOaNo("OA-SELECT");
    form.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem first = new OaFormItem();
    first.setId(301L);
    first.setMaterialNo("MAT-UNSELECTED");
    OaFormItem second = new OaFormItem();
    second.setId(302L);
    second.setMaterialNo("MAT-SELECTED");
    second.setPackageMethod("BOX");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(first, second));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-SELECT"))
        .thenReturn(List.of(part("MAT-SELECTED")));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-SELECTED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-SELECTED", PriceTypeEnum.FIXED)));
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-SELECT", "MAT-SELECTED", List.of(part("MAT-SELECTED")), List.of()));

    CostRunTrialResponse response = service.run("OA-SELECT", List.of(302L)).get();

    assertEquals(1, response.getProductCount());
    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(costRunEngine).run(contextCaptor.capture());
    CostRunContext context = contextCaptor.getValue();
    assertEquals(302L, context.getOaFormItemId());
    assertEquals("MAT-SELECTED", context.getProductCode());
    assertEquals("BOX", context.getPackageMethod());
    assertEquals("TRIAL-302", context.getCostRunNo());
    assertEquals(9302L, context.getCostRunVersionId());
    verify(costingBuildService).buildByOaFormItem(302L, currentPeriod);
    verify(costingBuildService, never()).buildByOaFormItem(eq(301L), anyString());
    ArgumentCaptor<PricePrepareGenerateRequest> prepareRequestCaptor =
        ArgumentCaptor.forClass(PricePrepareGenerateRequest.class);
    verify(pricePrepareService).generate(prepareRequestCaptor.capture());
    PricePrepareGenerateRequest prepareRequest = prepareRequestCaptor.getValue();
    assertEquals("OA-SELECT", prepareRequest.getOaNo());
    assertEquals(302L, prepareRequest.getOaFormItemId());
    assertEquals("MAT-SELECTED", prepareRequest.getTopProductCode());
    assertEquals(currentPeriod, prepareRequest.getPeriodMonth());
    assertEquals("QUOTE", prepareRequest.getSourceType());
    verify(quoteCostRunVersionService).createTrial(
        eq("OA-SELECT"),
        eq(302L),
        eq("MAT-SELECTED"),
        eq(currentPeriod),
        eq(currentPeriod),
        any(),
        isNull(),
        isNull(),
        any());
    verify(costRunResultWriter).writeQuoteResult(any(), eq(form), eq(second));
    verify(oaFormMapper, never()).update(any(), any());
  }

  @Test
  @DisplayName("整单入口也必须等所有产品行都已核算后才更新 OA 表头状态")
  void fullOaRunDoesNotMarkFormCalculatedWhenItemAggregateIsIncomplete() throws Exception {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-PARTIAL"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(2, 2, 2, "BATCH-1"));

    OaForm form = new OaForm();
    form.setId(400L);
    form.setOaNo("OA-PARTIAL");
    form.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem first = new OaFormItem();
    first.setId(401L);
    first.setMaterialNo("MAT-401");
    OaFormItem second = new OaFormItem();
    second.setId(402L);
    second.setMaterialNo("MAT-402");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(first, second));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-PARTIAL")).thenReturn(List.of());
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-PARTIAL", "MAT-401", List.of(), List.of()));
    when(oaFormItemMapper.countRunnableItems(400L)).thenReturn(2L);
    when(oaFormItemMapper.countCalculatedRunnableItems(400L)).thenReturn(1L);

    CostRunTrialResponse response = service.run("OA-PARTIAL").get();

    assertEquals(2, response.getProductCount());
    verify(oaFormItemMapper).markCalculated(eq(401L), any());
    verify(oaFormItemMapper).markCalculated(eq(402L), any());
    verify(oaFormMapper, never()).update(any(), any());
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
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-PPR-WARN", "MAT-FIXED", List.of(part("MAT-FIXED")), List.of()));

    CostRunTrialResponse response = service.run("OA-PPR-WARN").get();

    assertEquals(1, response.getProductCount());
    assertEquals("NOT_PREPARED", response.getPricePrepareReadiness().getStatus());
    assertEquals("DONE", progressStore.get("OA-PPR-WARN").getStatus());
    assertTrue(progressStore.get("OA-PPR-WARN").getMessage().contains("尚未执行价格准备"));
    verify(costRunEngine).run(any(CostRunContext.class));
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
    verify(costRunEngine, never()).run(any());
  }

  @Test
  @DisplayName("T7：已沿用 BOM 可以进入成本试算")
  void reusedCurrentMonthBomAllowsCostTrial() throws Exception {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-BOM-REUSED"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(quoteBomStatusService.checkForCostRun("OA-BOM-REUSED"))
        .thenReturn(bomResponse("MAT-REUSED", "REUSED_CURRENT_MONTH"));

    OaForm form = new OaForm();
    form.setId(105L);
    form.setOaNo("OA-BOM-REUSED");
    form.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    OaFormItem formItem = new OaFormItem();
    formItem.setId(205L);
    formItem.setMaterialNo("MAT-REUSED");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-BOM-REUSED"))
        .thenReturn(List.of(part("MAT-REUSED")));
    when(materialPriceRouterService.listCandidates(
        eq("MAT-REUSED"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route("MAT-REUSED", PriceTypeEnum.FIXED)));
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-BOM-REUSED", "MAT-REUSED", List.of(part("MAT-REUSED")), List.of()));

    CostRunTrialResponse response = service.run("OA-BOM-REUSED").get();

    assertEquals(1, response.getProductCount());
    verify(quoteBomStatusService).checkForCostRun("OA-BOM-REUSED");
    verify(costRunEngine).run(any(CostRunContext.class));
  }

  @Test
  @DisplayName("T7：无 BOM 阻断成本试算")
  void noBomBlocksCostTrial() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-BOM-NO"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(quoteBomStatusService.checkForCostRun("OA-BOM-NO"))
        .thenReturn(bomResponse("1079900000536", "NO_BOM"));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> service.run("OA-BOM-NO").get());

    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("产品 BOM 未准备完成"));
    assertTrue(ex.getCause().getMessage().contains("1079900000536"));
    assertTrue(ex.getCause().getMessage().contains("无BOM"));
    verify(costRunEngine, never()).run(any());
    verify(oaFormMapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("T7：检查异常阻断成本试算")
  void checkFailedBlocksCostTrial() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
    when(materialMasterSyncService.syncByOaNo("OA-BOM-FAILED"))
        .thenReturn(new MaterialMasterSyncService.SyncResult(1, 1, 1, "BATCH-1"));
    when(quoteBomStatusService.checkForCostRun("OA-BOM-FAILED"))
        .thenReturn(bomResponse("MAT-FAILED", "CHECK_FAILED"));

    ExecutionException ex =
        assertThrows(ExecutionException.class, () -> service.run("OA-BOM-FAILED").get());

    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("检查异常"));
    verify(costRunEngine, never()).run(any());
  }

  @Test
  @DisplayName("LPE-08：ensure 返回失败项时实时成本提示失败并继续部品取价")
  void costTrialContinuesWhenEnsureReturnsFailedItems() throws Exception {
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

    CostRunPartItemDto errorPart = part("MAT-LINKED");
    errorPart.setPriceSource("ERROR");
    errorPart.setRemark("联动价按需确保失败：MAT-LINKED: 公式变量缺失");
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenReturn(engineResult("OA-ENSURE-FAIL", "MAT-LINKED", List.of(errorPart), List.of()));

    CostRunTrialResponse response = service.run("OA-ENSURE-FAIL").get();

    assertEquals(1, response.getProductCount());
    assertEquals(1, response.getPartCount());
    assertEquals("DONE", progressStore.get("OA-ENSURE-FAIL").getStatus());
    assertTrue(progressStore.get("OA-ENSURE-FAIL").getMessage().contains("公式变量缺失"));
    assertTrue(progressStore.get("OA-ENSURE-FAIL").getMessage().contains("继续进入部品取价"));
    verify(costRunEngine).run(any(CostRunContext.class));
    ArgumentCaptor<CostRunObjectResult> resultCaptor =
        ArgumentCaptor.forClass(CostRunObjectResult.class);
    verify(costRunResultWriter).writeQuoteResult(resultCaptor.capture(), eq(form), eq(formItem));
    CostRunPartItemDto writtenPart = resultCaptor.getValue().getPartItems().get(0);
    assertEquals("ERROR", writtenPart.getPriceSource());
    assertTrue(writtenPart.getRemark().contains("公式变量缺失"));
  }

  private static CostRunPartItemDto part(String partCode) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(partCode);
    dto.setProductCode(partCode);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static QuoteBomStatusResponse bomResponse(String productCode, String bomStatus) {
    QuoteBomStatusItemResponse item = new QuoteBomStatusItemResponse();
    item.setProductCode(productCode);
    item.setBomStatus(bomStatus);
    QuoteBomStatusResponse response = new QuoteBomStatusResponse();
    response.setItems(List.of(item));
    return response;
  }

  private static CostRunObjectResult engineResult(
      String oaNo,
      String productCode,
      List<CostRunPartItemDto> partItems,
      List<CostRunCostItemDto> costItems) {
    return CostRunObjectResult.of(
        CostRunContext.quote(oaNo, 1L, productCode, null, "客户A", "COMMERCIAL", "2026-05", oaNo + ":" + productCode),
        null,
        null,
        partItems,
        costItems);
  }

  private static PriceTypeRoute route(String materialCode, PriceTypeEnum priceType) {
    return new PriceTypeRoute(materialCode, null, priceType, 1, null, null, "manual",
        priceType.getDbText());
  }
}
