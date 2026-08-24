package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.ProductCostingStateService;
import com.sanhua.marketingcost.service.ProductCostingCollaborationService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
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
  private ProductCostingCollaborationService collaborationService;
  private ProductCostingPipelineImpl pipeline;

  @BeforeEach
  void setUp() {
    workbenchService = mock(QuoteCostingWorkbenchService.class);
    priceService = mock(QuotePricePrepareWorkbenchService.class);
    costService = mock(QuoteCostRunWorkbenchService.class);
    stateService = mock(ProductCostingStateService.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    itemMapper = mock(OaFormItemMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    collaborationService = mock(ProductCostingCollaborationService.class);
    pipeline =
        new ProductCostingPipelineImpl(
            workbenchService,
            priceService,
            costService,
            stateService,
            workspaceService,
            itemMapper,
            versionMapper,
            collaborationService,
            algorithmVersion());
    when(itemMapper.selectById(11L)).thenReturn(item(null));
    when(workspaceService.find(11L, MONTH)).thenReturn(Optional.empty());
    when(stateService.bindCurrentPriceFingerprint("OA-1", 11L, MONTH, "PPR-OA-1"))
        .thenReturn("FULL-FP");
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
    when(collaborationService.coordinate(any())).thenReturn(
        new ProductCostingCollaborationService.CoordinationResult(
            101L, "WAIT_TECH", 601L, "王工", true, false, "已创建协作"));

    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_BOM");
    assertThat(result.getCurrentStep()).isEqualTo("QUOTE_BOM");
    assertThat(result.getErrorCode()).isEqualTo("BOM_MISSING");
    assertThat(result.getCollaborationTaskId()).isEqualTo(101L);
    assertThat(result.getCollaborationStatus()).isEqualTo("WAIT_TECH");
    assertThat(result.getCollaborationAssigneeName()).isEqualTo("王工");
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
    when(collaborationService.coordinate(any())).thenReturn(
        new ProductCostingCollaborationService.CoordinationResult(
            102L, "WAIT_TECH", 601L, "王工", true, true, "缺价已转协作"));

    var result = pipeline.execute(request(false));

    assertThat(result.getBlockingStatus()).isEqualTo("WAIT_PRICE");
    assertThat(result.getGapCount()).isEqualTo(2);
    assertThat(result.getCollaborationTaskId()).isEqualTo(102L);
    assertThat(result.getCollaborationMessage()).isEqualTo("缺价已转协作");
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
