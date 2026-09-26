package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkflowStatusResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummaryResponse;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowRetryException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.costing.ProductCostingSuccessLookup;
import com.sanhua.marketingcost.service.costing.ProductCostingFailurePolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T7 统一产品核算流水线")
class ProductCostingPipelineImplTest {

  private static final String MONTH = CostPricingPeriodUtils.currentPricingMonth();
  private QuoteCostingWorkbenchService workbenchService;
  private QuotePricePrepareWorkbenchService priceService;
  private QuoteCostRunWorkbenchService costService;
  private ProductCostingStateService stateService;
  private QuoteCostingWorkspaceService workspaceService;
  private OaFormItemMapper itemMapper;
  private QuoteCostRunVersionMapper versionMapper;
  private ProductCostingPipelineImpl pipeline;
  private OaFormMapper formMapper;
  private CostInputRevisionService revisionService;
  private MaterialMasterSyncService syncService;
  private com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader technicalSources;

  @BeforeEach
  void setUp() {
    workbenchService = mock(QuoteCostingWorkbenchService.class);
    priceService = mock(QuotePricePrepareWorkbenchService.class);
    costService = mock(QuoteCostRunWorkbenchService.class);
    stateService = mock(ProductCostingStateService.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    itemMapper = mock(OaFormItemMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    formMapper = mock(OaFormMapper.class);
    revisionService = mock(CostInputRevisionService.class);
    syncService = mock(MaterialMasterSyncService.class);
    technicalSources = mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader.class);
    when(technicalSources.afterCosting(any())).thenReturn(new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse(
        11L, MONTH, "checked", java.time.LocalDateTime.now(), List.of(), null, List.of()));
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    when(formMapper.selectOne(any())).thenReturn(form);
    when(revisionService.currentRevision(any(), any(), eq(MONTH))).thenReturn("SOURCE-1");
    pipeline = new ProductCostingPipelineImpl(
        workbenchService, priceService, costService, stateService, syncService,
        new ProductCostingContextResolver(formMapper, itemMapper, revisionService),
        new ProductCostingSuccessLookup(workspaceService, itemMapper, versionMapper, algorithmVersion()),
        new ProductCostingFailurePolicy(), technicalSources,
        mock(com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService.class));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(null));
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.empty());
    when(stateService.bindCurrentPriceFingerprint("OA-1", 11L, MONTH, "PPR-OA-1"))
        .thenReturn("FULL-FP");
  }

  @Test void preparationChecksCurrentSourcesWithoutCreatingAnyCostVersion() {
    stubReadyStages(0);
    var result = pipeline.prepare(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("READY");
    assertThat(result.getSourceRevision()).isNotBlank();
    assertThat(result.getCostVersionId()).isNull();
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test void automaticContinuationAtMaterialNodeCannotPublishCost() {
    stubReadyStages(0);
    var policy = mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class);
    when(policy.view(anyLong())).thenReturn(new com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.View(
        "MATERIAL_REVIEW", "待资料确认", null, null, true, true));
    pipeline.setOaWorkflowAccess(policy);
    var result = pipeline.execute(request(false));
    assertThat(result.getErrorCode()).isEqualTo("MATERIAL_CONFIRMATION_REQUIRED");
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("完整产品一次执行直接形成 SUCCESS 版本")
  void completeProductSucceeds() {
    stubReadyStages(0);

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(result.getCostVersionId()).isEqualTo(88L);
    assertThat(result.getPricePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(result.isReusedSuccess()).isFalse();
    verify(costService).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("缺 BOM 停在 BOM 步骤且不进入取价")
  void missingBomStopsAtBom() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("BLOCKED", "BLOCKED", 0));
    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_BOM");
    assertThat(result.getCurrentStep()).isEqualTo("QUOTE_BOM");
    assertThat(result.getErrorCode()).isEqualTo("BOM_MISSING");
    verify(priceService, never()).generate(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("缺BOM时成本入口不再调用旧协作链或伪造自动继续")
  void missingBomDoesNotInvokeLegacyCollaboration() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(
        workbench("BLOCKED", "BLOCKED", 0));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_BOM");
    verify(workbenchService).launchWorkbench("OA-1", 11L);
    verify(priceService, never()).generate(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("电子图库BOM已发布但真实缺价时只停在价格步骤且不回退BOM")
  void electronicDrawingPublicationStopsOnlyAtRealPriceGap() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(
        workbench("DONE", "DONE", 0));
    QuotePricePrepareWorkbenchResponse prices = new QuotePricePrepareWorkbenchResponse();
    prices.setReadiness(PricePrepareReadinessResult.notReady(
        "NOT_READY", false, true, "缺 2 项正式价格", null, MONTH, "PARTIAL", 2, null));
    when(priceService.generate(anyString(), anyLong(), any())).thenReturn(prices);
    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_PRICE");
    assertThat(result.getCurrentStep()).isEqualTo("PRICE_PREPARE");
    assertThat(result.getGapCount()).isEqualTo(2);
    verify(workbenchService).launchWorkbench("OA-1", 11L);
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("电子图库可重试异常由流水线交给worker后台重试")
  void electronicDrawingRetryIsMarkedRetryable() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenThrow(
        new ElectronicDrawingWorkflowRetryException(
            "电子图库正在后台自动重试，报价员无需处理"));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.isRetryable()).isTrue();
    assertThat(result.getMessage()).isEqualTo("电子图库正在后台自动重试，报价员无需处理");
    assertThat(result.getMessage()).doesNotContain("HTTP", "Exception");
  }

  @Test
  @DisplayName("新品只有型号时仍进入流水线并停在BOM协作，而不是系统失败")
  void modelOnlyProductEntersPipeline() {
    OaFormItem newProduct = item(null);
    newProduct.setMaterialNo(null);
    newProduct.setSunlModel("MODEL-NEW-1");
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(newProduct);
    when(workbenchService.launchWorkbench("OA-1", 11L))
        .thenReturn(workbench("BLOCKED", "BLOCKED", 0));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_BOM");
    assertThat(result.getProductCode()).isEqualTo("MODEL:MODEL-NEW-1");
    verify(priceService, never()).generate(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("最终 BOM 构建发现准备未就绪时转待协作，不记系统失败")
  void preparationNotReadyBecomesBomCollaboration() {
    when(workbenchService.launchWorkbench("OA-1", 11L))
        .thenThrow(new QuoteIngestException("BOM 准备结果尚未就绪，不能生成结算行"));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_BOM");
    assertThat(result.getErrorCode()).isEqualTo("BOM_MISSING");
    verify(stateService)
        .markBlocked(
            "OA-1",
            11L,
            MONTH,
            "WAIT_BOM",
            "QUOTE_BOM",
            "BOM_MISSING",
            "BOM 准备结果尚未就绪，不能生成结算行",
            1);
    verify(stateService, never())
        .markSystemFailed(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("BOM 基础设施异常仍记系统失败，不能伪装成资料缺口")
  void bomInfrastructureFailureRemainsSystemFailure() {
    when(workbenchService.launchWorkbench("OA-1", 11L))
        .thenThrow(new IllegalStateException("BOM 数据库查询超时"));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.getBlockingStatus()).isEqualTo("SYSTEM_FAILED");
    assertThat(result.getErrorCode()).isEqualTo("BOM_SYSTEM_ERROR");
    verify(stateService)
        .markSystemFailed(
            "OA-1", 11L, MONTH, "QUOTE_BOM", "BOM_SYSTEM_ERROR", "BOM 数据库查询超时");
    verify(stateService, never())
        .markBlocked(
            anyString(),
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt());
  }

  @Test
  @DisplayName("缺价格类型停在价格类型步骤")
  void missingPriceTypeStopsAtType() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("DONE", "PARTIAL", 3));

    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_PRICE_TYPE");
    assertThat(result.getCurrentStep()).isEqualTo("PRICE_TYPE_CONFIRMATION");
    assertThat(result.getGapCount()).isEqualTo(3);
    verify(priceService, never()).generate(anyString(), anyLong(), any());
  }

  @Test
  @DisplayName("缺正式价格停在最终价格步骤")
  void missingPriceStopsAtPrice() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("DONE", "DONE", 0));
    QuotePricePrepareWorkbenchResponse prices = new QuotePricePrepareWorkbenchResponse();
    prices.setReadiness(
        PricePrepareReadinessResult.notReady(
            "NOT_READY", false, true, "缺 2 项正式价格", null, MONTH, "PARTIAL", 2, null));
    when(priceService.generate(anyString(), anyLong(), any())).thenReturn(prices);
    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_PRICE");
    assertThat(result.getGapCount()).isEqualTo(2);
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("沿用历史价只提醒，不阻断成本成功")
  void carriedForwardPriceWarnsButSucceeds() {
    stubReadyStages(7);

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(result.getWarningCount()).isEqualTo(7);
    assertThat(result.getBlockingStatus()).isEqualTo("NONE");
  }

  @Test
  @DisplayName("成本异常保存结构化错误，不伪造成功版本")
  void costFailureKeepsFailureSummary() {
    stubReadyStages(0);
    when(costService.runToSuccess(anyString(), anyLong(), any(), anyString()))
        .thenThrow(new IllegalStateException("公式计算失败"));

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.getBlockingStatus()).isEqualTo("SYSTEM_FAILED");
    assertThat(result.getErrorCode()).isEqualTo("COST_RUN_SYSTEM_ERROR");
    assertThat(result.getCostVersionId()).isNull();
    verify(stateService)
        .markSystemFailed("OA-1", 11L, MONTH, "COST_RUN", "COST_RUN_SYSTEM_ERROR", "公式计算失败");
  }

  @Test
  @DisplayName("审批未齐可检查 BOM 和价格，但不得生成正式成本版本")
  void missingEffectiveTechnicalVersionDuringRevisionBecomesBusinessBlock() {
    stubReadyStages(0);
    when(revisionService.currentRevision(any(OaForm.class), any(OaFormItem.class), eq(MONTH)))
        .thenThrow(new EffectiveTechnicalDataException(
            "TECH_DATA_EFFECTIVE_VERSION_MISSING",
            11L,
            MONTH,
            List.of("PACKAGE", "AUXILIARY", "SALARY"),
            "产品11 / " + MONTH + "：尚无审核生效版本；缺少模块[PACKAGE, AUXILIARY, SALARY]"));
    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(result.getCurrentStep()).isEqualTo("TECHNICAL_DATA");
    assertThat(result.getErrorCode()).isEqualTo("TECH_DATA_EFFECTIVE_VERSION_MISSING");
    assertThat(result.getGapCount()).isEqualTo(3);
    assertThat(result.getMessage()).contains("产品11", "PACKAGE", "SALARY");
    verify(workbenchService).launchWorkbench("OA-1", 11L);
    verify(priceService).generate(anyString(), anyLong(), any());
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
    verify(stateService).markBlocked(
        "OA-1", 11L, MONTH, "WAIT_TECH_DATA", "TECHNICAL_DATA",
        "TECH_DATA_EFFECTIVE_VERSION_MISSING", result.getMessage(), 3);
  }

  @Test
  @DisplayName("财务基准缺失属于价格缺口，不归类为系统异常")
  void financeBaseMissingIsPriceBlock() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("DONE", "DONE", 0));
    when(priceService.generate(anyString(), anyLong(), any()))
        .thenThrow(new IllegalArgumentException("未维护当月财务报价Cu基准"));

    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_PRICE");
    assertThat(result.getErrorCode()).isEqualTo("FINANCE_BASE_PRICE_MISSING");
  }

  @Test
  @DisplayName("相同输入重复请求复用当前 SUCCESS，不重复生成 BOM、价格或成本")
  void duplicateRequestReusesSuccess() {
    QuoteCostingWorkspace workspace = workspace();
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.of(workspace));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    QuoteCostRunVersion version = version();
    version.setInputFingerprint("FULL-FP");
    when(versionMapper.selectById(88L)).thenReturn(version);

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(result.isReusedSuccess()).isTrue();
    verify(workbenchService, never()).launchWorkbench(anyString(), anyLong());
    verify(priceService, never()).generate(anyString(), anyLong(), any());
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("算法版本变化时相同业务输入也必须重新核算")
  void changedAlgorithmDoesNotReuseSuccess() {
    QuoteCostingWorkspace workspace = workspace();
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.of(workspace));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    QuoteCostRunVersion version = version();
    version.setInputFingerprint("FULL-FP");
    version.setAlgorithmVersion("LEGACY");
    when(versionMapper.selectById(88L)).thenReturn(version);
    stubReadyStages(0);

    var result = pipeline.execute(request(false));

    assertThat(result.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(result.isReusedSuccess()).isFalse();
    verify(workbenchService).launchWorkbench("OA-1", 11L);
    verify(costService).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test
  @DisplayName("强制重算不会复用旧成功版本")
  void forceRequestDoesNotReuseSuccess() {
    QuoteCostingWorkspace workspace = workspace();
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.of(workspace));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    QuoteCostRunVersion version = version();
    version.setInputFingerprint("FULL-FP");
    when(versionMapper.selectById(88L)).thenReturn(version);
    stubReadyStages(0);

    var result = pipeline.execute(request(true));

    assertThat(result.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(result.isReusedSuccess()).isFalse();
    verify(workbenchService).launchWorkbench("OA-1", 11L);
  }

  @Test
  void wrongQuotationOwnershipIsRejectedBeforeAnyWork() {
    OaFormItem otherItem = item(null);
    otherItem.setOaFormId(2L);
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(otherItem);
    assertThatThrownBy(() -> pipeline.execute(request(false)))
        .isInstanceOf(QuoteIngestException.class).hasMessageContaining("不属于当前报价单");
    verify(workbenchService, never()).launchWorkbench(anyString(), anyLong());
    verify(revisionService, never()).currentRevision(any(), any(), anyString());
  }

  @Test
  void transientRevisionFailureReturnsRetryableInputFailure() {
    when(revisionService.currentRevision(any(), any(), eq(MONTH)))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("读取输入超时"));
    var result = pipeline.execute(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.getErrorCode()).isEqualTo("INPUT_CHECK_SYSTEM_ERROR");
    assertThat(result.isRetryable()).isTrue();
    verify(workbenchService, never()).launchWorkbench(anyString(), anyLong());
  }

  @Test
  void failedConcurrentLookupDoesNotMaskOriginalStageFailure() {
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.empty())
        .thenThrow(new IllegalStateException("后续查询失败"));
    when(workbenchService.launchWorkbench("OA-1", 11L))
        .thenThrow(new IllegalStateException("原始BOM失败"));
    var result = pipeline.execute(request(false));
    assertThat(result.getErrorCode()).isEqualTo("BOM_SYSTEM_ERROR");
    assertThat(result.getMessage()).isEqualTo("原始BOM失败");
  }

  @Test
  void wrappedNetworkFailureIsNotMisreportedAsMissingPrice() {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("DONE", "DONE", 0));
    when(priceService.generate(anyString(), anyLong(), any()))
        .thenThrow(new IllegalArgumentException("价格服务连接失败", new java.net.ConnectException()));
    var result = pipeline.execute(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.getErrorCode()).isEqualTo("PRICE_PREPARE_SYSTEM_ERROR");
    assertThat(result.isRetryable()).isTrue();
  }

  @Test
  void missingSourceRevisionCannotReuseOldSuccess() {
    when(revisionService.currentRevision(any(), any(), eq(MONTH))).thenReturn(null);
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.of(workspace()));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    stubReadyStages(0);
    assertThat(pipeline.execute(request(false)).isReusedSuccess()).isFalse();
    verify(workbenchService).launchWorkbench("OA-1", 11L);
  }

  @Test
  void concurrentSuccessIsReusedAfterAStageFails() {
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.empty(), Optional.of(workspace()));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    QuoteCostRunVersion version = version();
    version.setInputFingerprint("FULL-FP");
    when(versionMapper.selectById(88L)).thenReturn(version);
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenThrow(new IllegalStateException("并发已发布"));
    assertThat(pipeline.execute(request(false)).isReusedSuccess()).isTrue();
    verify(stateService, never()).markSystemFailed(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
  }

  private void stubReadyStages(int warningCount) {
    when(workbenchService.launchWorkbench("OA-1", 11L)).thenReturn(workbench("DONE", "DONE", 0));
    QuotePricePrepareWorkbenchResponse prices = new QuotePricePrepareWorkbenchResponse();
    PricePrepareReadinessResult readiness =
        warningCount == 0
            ? PricePrepareReadinessResult.ready("PPR-OA-1", MONTH, "SUCCESS")
            : PricePrepareReadinessResult.readyWithWarnings(
                "PPR-OA-1", MONTH, "SUCCESS", warningCount, "沿用历史价 " + warningCount + " 项");
    prices.setReadiness(readiness);
    when(priceService.generate(anyString(), anyLong(), any())).thenReturn(prices);
    QuoteCostRunWorkbenchResponse cost = new QuoteCostRunWorkbenchResponse();
    cost.setCurrentDisplayVersion(summary());
    when(costService.runToSuccess(anyString(), anyLong(), any(), anyString())).thenReturn(cost);
  }

  @Test void confirmedSalaryGapBlocksCostEvenWhenBomAndAllMaterialPricesAreReady() {
    stubReadyStages(0);
    salaryCheck(com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability.MISSING);
    var result = pipeline.execute(request(false));
    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_TECH_DATA");
    assertThat(result.getTechnicalDataCheck().modules().getFirst().moduleType()).isEqualTo("SALARY");
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test void existingSupplementShowsOriginalOwnerAndCannotBypassCostInputReadiness() {
    stubReadyStages(0);
    var missing = new com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleRequirement(
        "SALARY", true, "PUBLIC_MISSING", "公共工资缺失", com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability.MISSING,
        "CMS", java.time.LocalDateTime.now());
    var shared = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSharedModuleInfo(
        "SALARY", "IN_PROGRESS", 20L, 21L, null, null, "王工", "已由王工办理，请等待原资料完成，不能重复补录");
    when(technicalSources.afterCosting(any())).thenReturn(new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse(
        11L, MONTH, "shared", java.time.LocalDateTime.now(), List.of(missing), null, List.of(shared)));
    var result = pipeline.execute(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.getMessage()).contains("王工", "不能重复补录");
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test void sourceQueryErrorDoesNotBecomeASalarySupplementTaskOrSuccessfulCost() {
    stubReadyStages(0);
    salaryCheck(com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability.ERROR);
    var result = pipeline.execute(request(false));
    assertThat(result.getErrorCode()).isEqualTo("TECH_SOURCE_QUERY_FAILED");
    assertThat(result.getTechnicalDataCheck().modules().getFirst().required()).isFalse();
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  @Test void newlyMissingSourceCannotReuseAnEarlierSuccess() {
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.of(workspace()));
    pipeline.setOaWorkflowAccess(mock(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.class));
    when(itemMapper.selectById(11L)).thenReturn(item(88L));
    var old = version(); old.setInputFingerprint("FULL-FP");
    when(versionMapper.selectById(88L)).thenReturn(old);
    salaryCheck(com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability.MISSING);
    var result = pipeline.execute(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("BLOCKED");
    assertThat(result.isReusedSuccess()).isFalse();
    assertThat(old.getStatus()).isEqualTo("SUCCESS");
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  private void salaryCheck(com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability availability) {
    var module = new com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleRequirement(
        "SALARY", availability == com.sanhua.marketingcost.service.technicaldata.TechnicalDataAvailability.MISSING,
        "SALARY_TEST", "工资来源测试", availability, "CMS", java.time.LocalDateTime.now());
    when(technicalSources.afterCosting(any())).thenReturn(new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse(
        11L, MONTH, "current-salary", java.time.LocalDateTime.now(), List.of(module), null, List.of()));
  }

  @Test void failedBomQueryIsASystemFailureInsteadOfMissingBom() {
    var workbench = workbench("BLOCKED", "BLOCKED", 0);
    var bom = new QuoteBomStatusItemResponse();
    bom.setBomStatus("CHECK_FAILED"); bom.setErrorMessage("目标组织 BOM 查询失败");
    workbench.setBomStatus(bom);
    when(workbenchService.launchWorkbench("OA-1",11L)).thenReturn(workbench);
    var result = pipeline.execute(request(false));
    assertThat(result.getPipelineStatus()).isEqualTo("FAILED");
    assertThat(result.getGapCount()).isZero();
    assertThat(result.getErrorCode()).isNotEqualTo("BOM_MISSING");
    verify(costService, never()).runToSuccess(anyString(), anyLong(), any(), anyString());
  }

  private QuoteCostingWorkbenchResponse workbench(
      String bomStatus, String priceTypeStatus, int typeGaps) {
    QuoteCostingWorkflowStatusResponse workflow = new QuoteCostingWorkflowStatusResponse();
    workflow.setQuoteBomStatus(bomStatus);
    workflow.setPriceTypeConfirmationStatus(priceTypeStatus);
    QuotePriceTypeRecognitionSummaryResponse type = new QuotePriceTypeRecognitionSummaryResponse();
    type.setGapCount(typeGaps);
    type.setMessage(typeGaps == 0 ? "价格类型已自动识别" : "缺 " + typeGaps + " 项价格类型");
    QuoteCostingWorkbenchResponse response = new QuoteCostingWorkbenchResponse();
    response.setWorkflowStatus(workflow);
    response.setLatestPriceTypeRecognition(type);
    return response;
  }

  private ProductCostingRequest request(boolean force) {
    return new ProductCostingRequest("OA-1", 11L, MONTH, "tester", force);
  }

  private OaFormItem item(Long confirmedVersionId) {
    OaFormItem item = new OaFormItem();
    item.setId(11L);
    item.setOaFormId(1L);
    item.setMaterialNo("TOP-1");
    item.setConfirmedCostVersionId(confirmedVersionId);
    return item;
  }

  private QuoteCostingWorkspace workspace() {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaNo("OA-1");
    workspace.setOaFormItemId(11L);
    workspace.setProductCode("TOP-1");
    workspace.setPeriodMonth(MONTH);
    workspace.setWorkspaceStatus("SUCCESS");
    workspace.setInputFingerprint("FULL-FP");
    workspace.setLastSuccessInputFingerprint("FULL-FP");
    workspace.setSourceRevision("SOURCE-1");
    workspace.setLastSuccessSourceRevision("SOURCE-1");
    workspace.setCurrentPrepareNo("PPR-OA-1");
    workspace.setCurrentCostVersionId(88L);
    workspace.setCarriedForwardPriceCount(2);
    return workspace;
  }

  private QuoteCostRunVersion version() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(88L);
    version.setOaNo("OA-1");
    version.setOaFormItemId(11L);
    version.setProductCode("TOP-1");
    version.setPricingMonth(MONTH);
    version.setAlgorithmVersion(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
    version.setStatus("SUCCESS");
    version.setSourceRevision("SOURCE-1");
    version.setCostRunNo("RUN-88");
    version.setVersionNo("COST-88");
    version.setOaPricePrepareNo("PPR-OA-1");
    version.setTotalCost(new BigDecimal("12.34"));
    return version;
  }

  private QuoteCostRunSummaryResponse summary() {
    QuoteCostRunSummaryResponse summary = new QuoteCostRunSummaryResponse();
    summary.setId(88L);
    summary.setCostRunNo("RUN-88");
    summary.setVersionNo("COST-88");
    summary.setStatus("SUCCESS");
    summary.setTotalCost(new BigDecimal("12.34"));
    return summary;
  }

  private CostingAlgorithmVersionProvider algorithmVersion() {
    return new CostingAlgorithmVersionProvider(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
  }
}
