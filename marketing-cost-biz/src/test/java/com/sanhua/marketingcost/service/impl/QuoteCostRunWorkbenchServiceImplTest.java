package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.mapper.QuoteCuMaterialDiffItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QWB-07 成本核算 tab service")
class QuoteCostRunWorkbenchServiceImplTest {

  private static final String CURRENT_PERIOD = CostPricingPeriodUtils.currentPricingMonth();

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteCostRunVersionMapper versionMapper;
  private CostRunResultMapper resultMapper;
  private CostRunPartItemMapper partItemMapper;
  private CostRunCostItemMapper costItemMapper;
  private QuoteCuMaterialDiffItemMapper diffItemMapper;
  private QuoteCostingWorkbenchSummaryMapper summaryMapper;
  private QuoteProductBomCostingBuildService costingBuildService;
  private PricePrepareService pricePrepareService;
  private PricePrepareReadinessService readinessService;
  private QuoteCostRunVersionNoGenerator versionNoGenerator;
  private QuoteCuAdjustmentCalcService cuAdjustmentCalcService;
  private QuoteCostRunWorkbenchServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
    TableInfoHelper.initTableInfo(assistant, CostRunResult.class);
    TableInfoHelper.initTableInfo(assistant, CostRunPartItem.class);
    TableInfoHelper.initTableInfo(assistant, CostRunCostItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCuMaterialDiffItem.class);
  }

  @BeforeEach
  void setUp() {
    authenticate("COMMERCIAL");
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    resultMapper = mock(CostRunResultMapper.class);
    partItemMapper = mock(CostRunPartItemMapper.class);
    costItemMapper = mock(CostRunCostItemMapper.class);
    diffItemMapper = mock(QuoteCuMaterialDiffItemMapper.class);
    summaryMapper = mock(QuoteCostingWorkbenchSummaryMapper.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    pricePrepareService = mock(PricePrepareService.class);
    readinessService = mock(PricePrepareReadinessService.class);
    versionNoGenerator = mock(QuoteCostRunVersionNoGenerator.class);
    cuAdjustmentCalcService = mock(QuoteCuAdjustmentCalcService.class);
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form());
    when(oaFormItemMapper.selectById(101L)).thenReturn(item(101L, "TOP-A"));
    when(readinessService.check(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-1", CURRENT_PERIOD, "SUCCESS"));
    when(readinessService.check(anyString(), anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-1", CURRENT_PERIOD, "SUCCESS"));
    when(summaryMapper.selectLatestPriceTypeConfirmation("OA-001", 101L, "TOP-A", CURRENT_PERIOD))
        .thenReturn(confirmedPriceType());
    when(costingBuildService.buildByOaFormItem(101L, CURRENT_PERIOD))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                101L,
                "OA-001",
                "TOP-A",
                "NON_BARE",
                CURRENT_PERIOD,
                "qbp-test",
                1,
                1,
                0,
                java.util.Map.of(),
                List.of(),
                LocalDateTime.now()));
    PricePrepareGenerateResult prepareResult = new PricePrepareGenerateResult();
    prepareResult.setPrepareNo("PPR-1");
    prepareResult.setOaNo("OA-001");
    prepareResult.setOaFormItemId(101L);
    prepareResult.setTopProductCode("TOP-A");
    prepareResult.setPeriodMonth(CURRENT_PERIOD);
    prepareResult.setStatus("SUCCESS");
    when(pricePrepareService.generate(any())).thenReturn(prepareResult);
    service =
        new QuoteCostRunWorkbenchServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            versionMapper,
            resultMapper,
            partItemMapper,
            costItemMapper,
            diffItemMapper,
            summaryMapper,
            readinessService,
            versionNoGenerator,
            cuAdjustmentCalcService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("价格准备阻断时试算失败")
  void trialFailsWhenPricePrepareBlocks() {
    when(readinessService.check("OA-001", 101L, "TOP-A", CURRENT_PERIOD, "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                false,
                true,
                "价格准备存在缺口",
                "PPR-1",
                CURRENT_PERIOD,
                "PARTIAL",
                1,
                List.of("MAT-1 缺价")));

    assertThatThrownBy(() -> service.trial("OA-001", 101L, new QuoteCostRunTrialRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("价格准备存在缺口");
  }

  @Test
  @DisplayName("价格准备有可继续缺口时试算继续")
  void trialContinuesWhenPricePrepareAllowsContinue() {
    when(readinessService.check("OA-001", 101L, "TOP-A", CURRENT_PERIOD, "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                true,
                false,
                "价格准备未完成，实时成本将继续，结果可能缺价",
                "PPR-1",
                CURRENT_PERIOD,
                "PARTIAL",
                1,
                List.of("MAT-1 缺价")));
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "TRIAL", "TOP-A");
    version.setPricePrepareNo("PPR-1");
    stubCalculation(version, "123.450000");

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getCurrentDisplayVersion().getCostRunNo()).isEqualTo("TRIAL-1");
    verify(cuAdjustmentCalcService).calculate(any(QuoteCuAdjustmentCalcRequest.class));
  }

  @Test
  @DisplayName("价格准备允许继续时查询页仍允许发起核算")
  void getCostRunAllowsStartTrialWhenPricePrepareAllowsContinue() {
    when(readinessService.check("OA-001", 101L, "TOP-A", CURRENT_PERIOD, "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                true,
                false,
                "价格准备未完成，实时成本将继续，结果可能缺价",
                "PPR-1",
                CURRENT_PERIOD,
                "PARTIAL",
                1,
                List.of("MAT-1 缺价")));

    var response = service.getCostRun("OA-001", 101L, CURRENT_PERIOD);

    assertThat(response.isCanStartTrial()).isTrue();
    assertThat(response.getBlockingReasons()).isEmpty();
  }

  @Test
  @DisplayName("试算成功返回 total_cost 并写入版本上下文")
  void trialReturnsTotalCost() {
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "TRIAL", "TOP-A");
    LocalDateTime trialStartedAt = LocalDateTime.of(2026, 6, 16, 16, 41, 50);
    version.setTrialStartedAt(trialStartedAt);
    version.setPricePrepareNo("PPR-1");
    stubCalculation(version, "123.450000");

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getCurrentDisplayVersion().getCostRunNo()).isEqualTo("TRIAL-1");
    assertThat(response.getResultHeader().getTotalCost()).isEqualByComparingTo("123.450000");
    assertThat(response.getResultHeader().getFinalQuoteAmount()).isEqualByComparingTo("125.450000");
    assertThat(response.isCanConfirm()).isTrue();
    ArgumentCaptor<QuoteCuAdjustmentCalcRequest> requestCaptor =
        ArgumentCaptor.forClass(QuoteCuAdjustmentCalcRequest.class);
    verify(cuAdjustmentCalcService).calculate(requestCaptor.capture());
    assertThat(requestCaptor.getValue().form()).isNotNull();
    assertThat(requestCaptor.getValue().item().getId()).isEqualTo(101L);
    assertThat(requestCaptor.getValue().oaPricePrepareNo()).isEqualTo("PPR-1");
    verify(costingBuildService, never()).buildByOaFormItem(anyLong(), anyString());
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  @DisplayName("试算直接复用当前最终价格快照，拒绝页面旧批次")
  void trialRejectsStaleRequestedBatchWithoutRebuildingPrices() {
    when(readinessService.check("OA-001", 101L, "TOP-A", CURRENT_PERIOD, "PTC-1"))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-CURRENT", CURRENT_PERIOD, "SUCCESS"));
    QuoteCostRunTrialRequest request = new QuoteCostRunTrialRequest();
    request.setPricePrepareNo("PPR-STALE-FROM-PAGE");

    assertThatThrownBy(() -> service.trial("OA-001", 101L, request))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("最终价格版本已更新");

    verify(cuAdjustmentCalcService, never()).calculate(any());
    verify(costingBuildService, never()).buildByOaFormItem(anyLong(), anyString());
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  @DisplayName("重新试算成功后旧未确认版本转历史，结果和明细不删除")
  void trialVoidsOlderUnconfirmedTrialsWithoutDeletingVersionData() {
    QuoteCostRunVersion version = version(88L, "TRIAL-NEW", "TRIAL", "TOP-A");
    version.setPricePrepareNo("PPR-1");
    QuoteCostRunVersion oldTrial = version(77L, "TRIAL-OLD", "TRIAL", "TOP-A");
    oldTrial.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 9, 0));
    QuoteCostRunVersion oldVoided = version(77L, "TRIAL-OLD", "VOIDED", "TOP-A");
    oldVoided.setTrialFinishedAt(oldTrial.getTrialFinishedAt());
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(oldTrial), List.of(version, oldVoided));
    stubCalculation(version, "123.450000");

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getVersions()).extracting("costRunNo")
        .containsExactly("TRIAL-NEW", "TRIAL-OLD");
    verify(versionMapper).update(any(QuoteCostRunVersion.class), any(Wrapper.class));
    verify(resultMapper).update(any(CostRunResult.class), any(Wrapper.class));
    verify(versionMapper, never()).delete(any(Wrapper.class));
    verify(resultMapper, never()).delete(any(Wrapper.class));
    verify(partItemMapper, never()).delete(any(Wrapper.class));
    verify(costItemMapper, never()).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("确认非当前产品行 costRunNo 失败")
  void confirmRejectsOtherItemCostRunNo() {
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(version(88L, "TRIAL-OTHER", "TRIAL", "TOP-B"));

    assertThatThrownBy(
            () -> service.confirm("OA-001", 101L, "TRIAL-OTHER", new QuoteCostRunConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不属于当前产品行");
  }

  @Test
  @DisplayName("重复确认非 TRIAL 版本时明确失败")
  void confirmRejectsDuplicateConfirm() {
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(version(88L, "TRIAL-1", "CONFIRMED", "TOP-A"));

    assertThatThrownBy(
            () -> service.confirm("OA-001", 101L, "TRIAL-1", new QuoteCostRunConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不能重复确认");
  }

  @Test
  @DisplayName("新版本确认后旧 CONFIRMED 变 VOIDED 并回写产品行和表头核算状态")
  void confirmVoidsOldConfirmedVersion() {
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(version(88L, "TRIAL-1", "TRIAL", "TOP-A"));
    when(versionMapper.update(any(QuoteCostRunVersion.class), any(Wrapper.class)))
        .thenReturn(0, 1);
    when(versionNoGenerator.nextVersionNo(101L, "TOP-A")).thenReturn("COST-20260609-0001-V1");
    when(oaFormItemMapper.countRunnableItems(10L)).thenReturn(1L);
    when(oaFormItemMapper.countCalculatedRunnableItems(10L)).thenReturn(1L);
    QuoteCostRunConfirmRequest request = new QuoteCostRunConfirmRequest();
    request.setConfirmedBy("alice");

    var response = service.confirm("OA-001", 101L, "TRIAL-1", request);

    assertThat(response.getStatus()).isEqualTo("CONFIRMED");
    assertThat(response.getVersionNo()).isEqualTo("COST-20260609-0001-V1");
    ArgumentCaptor<QuoteCostRunVersion> updateCaptor =
        ArgumentCaptor.forClass(QuoteCostRunVersion.class);
    verify(versionMapper, times(2)).update(updateCaptor.capture(), any(Wrapper.class));
    assertThat(updateCaptor.getAllValues())
        .extracting(QuoteCostRunVersion::getStatus)
        .containsExactly("VOIDED", "CONFIRMED");
    verify(oaFormItemMapper).update(eq(null), any(Wrapper.class));
    verify(oaFormMapper).update(eq(null), any(Wrapper.class));
  }

  @Test
  @DisplayName("确认过程中试算已被配置变更标记失效时拒绝确认")
  void confirmRejectsTrialThatBecomesStaleConcurrently() {
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(version(88L, "TRIAL-RACE", "TRIAL", "TOP-A"));
    when(versionMapper.update(any(QuoteCostRunVersion.class), any(Wrapper.class)))
        .thenReturn(0, 0);
    when(versionNoGenerator.nextVersionNo(101L, "TOP-A"))
        .thenReturn("COST-20260609-0001-V1");

    assertThatThrownBy(
            () ->
                service.confirm(
                    "OA-001", 101L, "TRIAL-RACE", new QuoteCostRunConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("已失效或状态已变化");

    verify(versionMapper, times(2)).update(any(QuoteCostRunVersion.class), any(Wrapper.class));
    verify(oaFormItemMapper, never()).update(eq(null), any(Wrapper.class));
    verify(oaFormMapper, never()).update(eq(null), any(Wrapper.class));
  }

  @Test
  @DisplayName("查询默认展示最新 CONFIRMED 并读取版本结果")
  void getCostRunDisplaysLatestConfirmed() {
    QuoteCostRunVersion trial = version(77L, "TRIAL-1", "TRIAL", "TOP-A");
    trial.setTotalCost(new BigDecimal("98.00"));
    QuoteCostRunVersion confirmed = version(88L, "TRIAL-2", "CONFIRMED", "TOP-A");
    confirmed.setVersionNo("COST-20260609-0001-V1");
    confirmed.setTotalCost(new BigDecimal("123.45"));
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(confirmed));
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(null, confirmed);
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(result("123.45"));
    CostRunPartItem part = new CostRunPartItem();
    part.setPartCode("PART-1");
    part.setAmount(BigDecimal.TEN);
    part.setPriceOrgCode("210");
    part.setMaterialOrganizationCode("COMMERCIAL");
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(part));
    CostRunCostItem cost = new CostRunCostItem();
    cost.setCostCode("TOTAL");
    cost.setAmount(new BigDecimal("123.45"));
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cost));

    var response = service.getCostRun("OA-001", 101L, CURRENT_PERIOD);

    assertThat(response.getLatestTrial()).isNull();
    assertThat(response.getLatestConfirmed().getVersionNo()).isEqualTo("COST-20260609-0001-V1");
    assertThat(response.getPartItems()).singleElement().satisfies(row -> {
      assertThat(row.getPriceOrgCode()).isEqualTo("210");
      assertThat(row.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    });
    assertThat(response.getCurrentDisplayVersion().getId()).isEqualTo(88L);
    assertThat(response.getResultHeader().getTotalCost()).isEqualByComparingTo("123.45");
    assertThat(response.getPartItems()).hasSize(1);
    assertThat(response.getCostItems()).hasSize(1);
    assertThat(response.isCanStartTrial()).isTrue();
    assertThat(response.isCanConfirm()).isFalse();
  }

  @Test
  @DisplayName("查询返回最新试算、当前已确认和历史版本，且查询过程不修改版本")
  void getCostRunReturnsSortedVersionRows() {
    OaFormItem currentItem = item(101L, "TOP-A");
    currentItem.setConfirmedCostVersionId(99L);
    when(oaFormItemMapper.selectById(101L)).thenReturn(currentItem);
    QuoteCostRunVersion trial = version(77L, "TRIAL-2", "TRIAL", "TOP-A");
    trial.setTotalCost(new BigDecimal("138.00"));
    trial.setPartItemCount(26);
    trial.setCostItemCount(24);
    trial.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 9, 6, 37));
    QuoteCostRunVersion oldTrial = version(66L, "TRIAL-OLD", "TRIAL", "TOP-A");
    oldTrial.setTotalCost(new BigDecimal("130.00"));
    oldTrial.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 8, 30, 0));
    QuoteCostRunVersion current = version(99L, "TRIAL-3", "CONFIRMED", "TOP-A");
    current.setVersionNo("COST-20260618-0001-V2");
    current.setTotalCost(new BigDecimal("139.00"));
    current.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 10, 0, 0));
    current.setConfirmedAt(LocalDateTime.of(2026, 6, 18, 10, 5, 0));
    current.setConfirmedBy("bob");
    QuoteCostRunVersion history = version(88L, "TRIAL-1", "VOIDED", "TOP-A");
    history.setVersionNo("COST-20260615-0001-V1");
    history.setTotalCost(new BigDecimal("123.45"));
    history.setTrialFinishedAt(LocalDateTime.of(2026, 6, 15, 15, 15, 23));
    history.setConfirmedAt(LocalDateTime.of(2026, 6, 15, 15, 36, 2));
    history.setConfirmedBy("alice");
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(trial, current);
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(oldTrial, history, current, trial));
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(result("138.00"));
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    var response = service.getCostRun("OA-001", 101L, CURRENT_PERIOD);

    assertThat(response.getLatestTrial().getId()).isEqualTo(77L);
    assertThat(response.getCurrentDisplayVersion().getId()).isEqualTo(77L);
    assertThat(response.isCanConfirm()).isTrue();
    assertThat(response.getVersions())
        .extracting("id")
        .containsExactly(77L, 99L, 88L);
    assertThat(response.getVersions().get(0).getDisplayStatus()).isEqualTo("待确认");
    assertThat(response.getVersions().get(0).isCanConfirm()).isTrue();
    assertThat(response.getVersions().get(1).getDisplayStatus()).isEqualTo("当前已确认");
    assertThat(response.getVersions().get(1).isCurrentConfirmed()).isTrue();
    assertThat(response.getVersions().get(2).getDisplayStatus()).isEqualTo("历史版本");
    assertThat(response.getVersions().get(2).isStale()).isTrue();
    assertThat(response.getVersions().get(2).isCanViewSheet()).isTrue();
    assertThat(response.getVersions().get(2).isCanViewTrace()).isTrue();
    verify(versionMapper, never()).update(any(QuoteCostRunVersion.class), any(Wrapper.class));
    verify(resultMapper, never()).update(any(CostRunResult.class), any(Wrapper.class));
    verify(versionMapper, never()).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("查询时保留最新试算，没有 CONFIRMED 时也展示待确认版本")
  void getCostRunDisplaysLatestTrialWhenNoConfirmed() {
    QuoteCostRunVersion trial = version(77L, "TRIAL-1", "TRIAL", "TOP-A");
    trial.setTotalCost(new BigDecimal("137.806"));
    trial.setPartItemCount(26);
    trial.setCostItemCount(24);
    trial.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 9, 6, 37));
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trial));
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(trial, (QuoteCostRunVersion) null);
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    var response = service.getCostRun("OA-001", 101L, CURRENT_PERIOD);

    assertThat(response.getLatestTrial().getId()).isEqualTo(77L);
    assertThat(response.getLatestConfirmed()).isNull();
    assertThat(response.getCurrentDisplayVersion().getId()).isEqualTo(77L);
    assertThat(response.getVersions()).extracting("id").containsExactly(77L);
    assertThat(response.getPartItems()).isEmpty();
    assertThat(response.getCostItems()).isEmpty();
    assertThat(response.isCanConfirm()).isTrue();
  }

  @Test
  @DisplayName("历史版本按 versionId 定向读取并返回完整冻结汇总")
  void getCostRunReadsHistoricalVersionSnapshot() {
    QuoteCostRunVersion selected = version(88L, "TRIAL-HISTORY", "VOIDED", "TOP-A");
    selected.setPricingMonth("2026-05");
    selected.setVersionNo("COST-HISTORY-V1");
    selected.setFinanceCuPrice(new BigDecimal("90.00000000"));
    selected.setOaCuPrice(new BigDecimal("102.03900000"));
    selected.setFinanceMaterialCost(new BigDecimal("261.12800000"));
    selected.setOaMaterialCost(new BigDecimal("285.20600000"));
    selected.setCuMaterialAdjustment(new BigDecimal("24.07800000"));
    selected.setTotalCost(new BigDecimal("1000.00000000"));
    selected.setFinalQuoteAmount(new BigDecimal("1024.07800000"));
    selected.setOaPricePrepareNo("PPR-OA-HISTORY");
    selected.setFinancePricePrepareNo("PPR-FIN-HISTORY");
    when(versionMapper.selectById(88L)).thenReturn(selected);
    when(versionMapper.selectOne(any(Wrapper.class)))
        .thenReturn((QuoteCostRunVersion) null, (QuoteCostRunVersion) null);
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(selected));
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    var response = service.getCostRun("OA-001", 101L, null, 88L);

    assertThat(response.getPeriodMonth()).isEqualTo("2026-05");
    assertThat(response.getCurrentDisplayVersion().getId()).isEqualTo(88L);
    assertThat(response.getCurrentDisplayVersion().getFinanceCuPricePerTon())
        .isEqualByComparingTo("90000.00000000");
    assertThat(response.getCurrentDisplayVersion().getOaCuPricePerTon())
        .isEqualByComparingTo("102039.00000000");
    assertThat(response.getCurrentDisplayVersion().getFinanceBaseTotalCost())
        .isEqualByComparingTo("1000.00000000");
    assertThat(response.getCurrentDisplayVersion().getFinalQuoteAmount())
        .isEqualByComparingTo("1024.07800000");
    assertThat(response.getCurrentDisplayVersion().getOaPricePrepareNo())
        .isEqualTo("PPR-OA-HISTORY");
    assertThat(response.getCurrentDisplayVersion().getFinancePricePrepareNo())
        .isEqualTo("PPR-FIN-HISTORY");
    verify(cuAdjustmentCalcService, never()).calculate(any());
  }

  @Test
  @DisplayName("逐料差异按版本分页并组合父子料号、有差异和正负筛选")
  @SuppressWarnings({"rawtypes", "unchecked"})
  void pageCuMaterialDifferencesUsesPersistedVersionAndFilters() {
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "CONFIRMED", "TOP-A");
    version.setBusinessUnitType("COMMERCIAL");
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(version);
    QuoteCuMaterialDiffItem difference = difference(501L, "24.07800000");
    when(diffItemMapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(
            invocation -> {
              Page<QuoteCuMaterialDiffItem> page = invocation.getArgument(0);
              page.setRecords(List.of(difference));
              page.setTotal(3L);
              return page;
            });

    var result =
        service.pageCuMaterialDifferences(
            "OA-001",
            101L,
            "TRIAL-1",
            2,
            10,
            "MAKE-1",
            "RAW-CU-1",
            true,
            "POSITIVE");

    assertThat(result.getTotal()).isEqualTo(3L);
    assertThat(result.getList()).hasSize(1);
    assertThat(result.getList().get(0).getParentMaterialCode()).isEqualTo("MAKE-1");
    assertThat(result.getList().get(0).getMaterialCode()).isEqualTo("RAW-CU-1");
    assertThat(result.getList().get(0).getDiffAmount()).isEqualByComparingTo("24.07800000");
    assertThat(result.getList().get(0).isContributesToAdjustment()).isTrue();
    ArgumentCaptor<Page<QuoteCuMaterialDiffItem>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<Wrapper<QuoteCuMaterialDiffItem>> wrapperCaptor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(diffItemMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2L);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(10L);
    assertThat(wrapperCaptor.getValue().getSqlSegment())
        .contains("cost_run_version_id", "business_unit_type", "parent_material_code")
        .contains("material_code", "diff_amount");
  }

  @Test
  @DisplayName("逐料差异拒绝未知差异方向和越界分页")
  void pageCuMaterialDifferencesRejectsInvalidFilters() {
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "CONFIRMED", "TOP-A");
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(version);

    assertThatThrownBy(
            () ->
                service.pageCuMaterialDifferences(
                    "OA-001", 101L, "TRIAL-1", 1, 20, null, null, false, "UP"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("POSITIVE");
    assertThatThrownBy(
            () ->
                service.pageCuMaterialDifferences(
                    "OA-001", 101L, "TRIAL-1", 1, 201, null, null, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize");
  }

  @Test
  @DisplayName("单产品查询显式拒绝跨业务单元访问")
  void getCostRunRejectsCrossBusinessUnit() {
    authenticate("HOUSEHOLD");

    assertThatThrownBy(() -> service.getCostRun("OA-001", 101L, CURRENT_PERIOD))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("无权访问");

    verify(versionMapper, never()).selectOne(any(Wrapper.class));
  }

  @Test
  @DisplayName("导出按 versionId 读取冻结快照，不重复导出第五步Cu差异")
  void exportReadsVersionRows() throws Exception {
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "CONFIRMED", "TOP-A");
    version.setVersionNo("COST-1");
    version.setBusinessUnitType("COMMERCIAL");
    version.setFinanceCuPrice(new BigDecimal("90.00000000"));
    version.setOaCuPrice(new BigDecimal("102.03900000"));
    version.setFinanceMaterialCost(new BigDecimal("261.12800000"));
    version.setOaMaterialCost(new BigDecimal("285.20600000"));
    version.setCuMaterialAdjustment(new BigDecimal("24.07800000"));
    version.setTotalCost(new BigDecimal("123.45"));
    version.setFinalQuoteAmount(new BigDecimal("147.52800000"));
    version.setOaPricePrepareNo("PPR-OA-OLD");
    version.setFinancePricePrepareNo("PPR-FIN-OLD");
    when(versionMapper.selectById(88L)).thenReturn(version);
    CostRunPartItem part = new CostRunPartItem();
    part.setPartCode("PART-1");
    part.setPartName("部品1");
    part.setAmount(new BigDecimal("10"));
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(part));
    CostRunCostItem cost = new CostRunCostItem();
    cost.setCostCode("TOTAL");
    cost.setCostName("不含税总成本");
    cost.setAmount(new BigDecimal("123.45"));
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cost));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int rows = service.exportVersion("OA-001", 101L, 88L, out);

    assertThat(rows).isEqualTo(3);
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(3);
      assertThat(workbook.getSheet("汇总").getRow(7).getCell(1).getStringCellValue())
          .isEqualTo("90000.00000000");
      assertThat(workbook.getSheet("汇总").getRow(11).getCell(1).getStringCellValue())
          .isEqualTo("24.07800000");
      assertThat(workbook.getSheet("汇总").getRow(13).getCell(1).getStringCellValue())
          .isEqualTo("147.52800000");
      assertThat(workbook.getSheet("成本部品").getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("PART-1");
      assertThat(workbook.getSheet("成本项目").getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("TOTAL");
      assertThat(workbook.getSheet("Cu材料费差异")).isNull();
    }
    verify(cuAdjustmentCalcService, never()).calculate(any());
  }

  private static OaForm form() {
    OaForm form = new OaForm();
    form.setId(10L);
    form.setOaNo("OA-001");
    form.setCustomer("客户A");
    form.setBusinessUnitType("COMMERCIAL");
    form.setAccountingPeriodMonth(CURRENT_PERIOD);
    return form;
  }

  private static QuotePriceTypeConfirmationSummaryResponse confirmedPriceType() {
    QuotePriceTypeConfirmationSummaryResponse response =
        new QuotePriceTypeConfirmationSummaryResponse();
    response.setConfirmNo("PTC-1");
    response.setOaNo("OA-001");
    response.setOaFormItemId(101L);
    response.setProductCode("TOP-A");
    response.setPeriodMonth(CURRENT_PERIOD);
    response.setStatus("CONFIRMED");
    return response;
  }

  private static OaFormItem item(Long id, String materialNo) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(10L);
    item.setMaterialNo(materialNo);
    item.setPackageMethod("BOX");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private static QuoteCostRunVersion version(Long id, String costRunNo, String status, String productCode) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(id);
    version.setCostRunNo(costRunNo);
    version.setOaNo("OA-001");
    version.setOaFormItemId(101L);
    version.setProductCode(productCode);
    version.setPricingMonth(CURRENT_PERIOD);
    version.setResultPeriod(CURRENT_PERIOD);
    version.setStatus(status);
    return version;
  }

  private static QuoteCuMaterialDiffItem difference(Long id, String diffAmount) {
    QuoteCuMaterialDiffItem item = new QuoteCuMaterialDiffItem();
    item.setId(id);
    item.setCostRunVersionId(88L);
    item.setCostRunNo("TRIAL-1");
    item.setLineNo(1);
    item.setSettlementKey("SETTLEMENT:1:RAW-CU-1");
    item.setDetailLevel("RAW_COMPONENT");
    item.setContributesToAdjustment(1);
    item.setTopProductCode("TOP-A");
    item.setParentMaterialCode("MAKE-1");
    item.setMaterialCode("RAW-CU-1");
    item.setMaterialName("铜材");
    item.setQuantity(new BigDecimal("2.00000000"));
    item.setFinanceUnitPrice(new BigDecimal("90.00000000"));
    item.setOaUnitPrice(new BigDecimal("102.03900000"));
    item.setFinanceAmount(new BigDecimal("261.12800000"));
    item.setOaAmount(new BigDecimal("285.20600000"));
    item.setDiffAmount(new BigDecimal(diffAmount));
    item.setCuAffected(1);
    item.setPriceFormulaRefType("MAKE_PART_COMPONENT");
    item.setPriceFormulaRefId(9001L);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private void authenticate(String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("fcq10.user", null, List.of());
    authentication.setDetails(
        Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static CostRunResult result(String totalCost) {
    CostRunResult result = new CostRunResult();
    result.setOaNo("OA-001");
    result.setProductCode("TOP-A");
    result.setPeriod(CURRENT_PERIOD);
    result.setTotalCost(new BigDecimal(totalCost));
    result.setCalcStatus("SUCCESS");
    return result;
  }

  private static CostRunPartItemDto part(String partCode) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setProductCode("TOP-A");
    item.setPartCode(partCode);
    item.setAmount(BigDecimal.TEN);
    return item;
  }

  private void stubCalculation(QuoteCostRunVersion version, String totalAmount) {
    BigDecimal totalCost = new BigDecimal(totalAmount);
    BigDecimal adjustment = new BigDecimal("2.000000");
    BigDecimal finalQuoteAmount = totalCost.add(adjustment);
    version.setOaPricePrepareNo("PPR-1");
    version.setFinancePricePrepareNo("PPR-FIN-1");
    version.setFinanceMaterialCost(new BigDecimal("100.000000"));
    version.setOaMaterialCost(new BigDecimal("102.000000"));
    version.setCuMaterialAdjustment(adjustment);
    version.setTotalCost(totalCost);
    version.setFinalQuoteAmount(finalQuoteAmount);
    version.setPartItemCount(1);
    version.setCostItemCount(1);
    CostRunContext context = CostRunContext.quote(
        "OA-001", 101L, "TOP-A", "BOX", "ACME", "COMMERCIAL", CURRENT_PERIOD, "QUOTE:101");
    context.setCostRunVersionId(version.getId());
    context.setCostRunNo(version.getCostRunNo());
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(totalCost);
    resultDto.setFinanceMaterialCost(new BigDecimal("100.000000"));
    resultDto.setOaMaterialCost(new BigDecimal("102.000000"));
    resultDto.setCuMaterialAdjustment(adjustment);
    resultDto.setFinalQuoteAmount(finalQuoteAmount);
    CostRunObjectResult costResult = CostRunObjectResult.of(
        context,
        null,
        resultDto,
        List.of(part("PART-1")),
        List.of(total(totalAmount)));
    when(cuAdjustmentCalcService.calculate(any(QuoteCuAdjustmentCalcRequest.class)))
        .thenReturn(new QuoteCuAdjustmentCalcResult(
            version,
            costResult,
            null,
            new BigDecimal("100.000000"),
            new BigDecimal("102.000000"),
            totalCost,
            adjustment,
            finalQuoteAmount));
  }

  private static CostRunCostItemDto total(String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
