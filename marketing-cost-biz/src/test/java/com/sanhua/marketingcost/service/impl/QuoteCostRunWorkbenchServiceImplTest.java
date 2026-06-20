package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QWB-07 成本核算 tab service")
class QuoteCostRunWorkbenchServiceImplTest {

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteCostRunVersionMapper versionMapper;
  private CostRunResultMapper resultMapper;
  private CostRunPartItemMapper partItemMapper;
  private CostRunCostItemMapper costItemMapper;
  private CostRunTraceSnapshotMapper traceSnapshotMapper;
  private CostRunTaskMapper taskMapper;
  private QuoteCostingWorkbenchSummaryMapper summaryMapper;
  private QuoteProductBomCostingBuildService costingBuildService;
  private PricePrepareService pricePrepareService;
  private PricePrepareReadinessService readinessService;
  private QuoteCostRunVersionService versionService;
  private QuoteCostRunVersionNoGenerator versionNoGenerator;
  private CostRunEngine costRunEngine;
  private CostRunResultWriter resultWriter;
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
    TableInfoHelper.initTableInfo(assistant, CostRunTraceSnapshot.class);
    TableInfoHelper.initTableInfo(assistant, CostRunTask.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    resultMapper = mock(CostRunResultMapper.class);
    partItemMapper = mock(CostRunPartItemMapper.class);
    costItemMapper = mock(CostRunCostItemMapper.class);
    traceSnapshotMapper = mock(CostRunTraceSnapshotMapper.class);
    taskMapper = mock(CostRunTaskMapper.class);
    summaryMapper = mock(QuoteCostingWorkbenchSummaryMapper.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    pricePrepareService = mock(PricePrepareService.class);
    readinessService = mock(PricePrepareReadinessService.class);
    versionService = mock(QuoteCostRunVersionService.class);
    versionNoGenerator = mock(QuoteCostRunVersionNoGenerator.class);
    costRunEngine = mock(CostRunEngine.class);
    resultWriter = mock(CostRunResultWriter.class);
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form());
    when(oaFormItemMapper.selectById(101L)).thenReturn(item(101L, "TOP-A"));
    when(readinessService.check(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-1", "2026-06", "SUCCESS"));
    when(readinessService.check(anyString(), anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(PricePrepareReadinessResult.ready("PPR-1", "2026-06", "SUCCESS"));
    when(summaryMapper.selectLatestPriceTypeConfirmation("OA-001", 101L, "TOP-A", "2026-06"))
        .thenReturn(confirmedPriceType());
    when(costingBuildService.buildByOaFormItem(101L, "2026-06"))
        .thenReturn(
            new QuoteBomCostingBuildResponse(
                201L,
                null,
                101L,
                "OA-001",
                "TOP-A",
                "NON_BARE",
                "2026-06",
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
    prepareResult.setPeriodMonth("2026-06");
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
            traceSnapshotMapper,
            taskMapper,
            summaryMapper,
            costingBuildService,
            pricePrepareService,
            readinessService,
            versionService,
            versionNoGenerator,
            costRunEngine,
            resultWriter);
  }

  @Test
  @DisplayName("价格准备阻断时试算失败")
  void trialFailsWhenPricePrepareBlocks() {
    when(readinessService.check("OA-001", 101L, "TOP-A", "2026-06", "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                false,
                true,
                "价格准备存在缺口",
                "PPR-1",
                "2026-06",
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
    when(readinessService.check("OA-001", 101L, "TOP-A", "2026-06", "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                true,
                false,
                "价格准备未完成，实时成本将继续，结果可能缺价",
                "PPR-1",
                "2026-06",
                "PARTIAL",
                1,
                List.of("MAT-1 缺价")));
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "TRIAL", "TOP-A");
    version.setPricePrepareNo("PPR-1");
    when(versionService.createTrial(anyString(), eq(101L), eq("TOP-A"), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(version);
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("123.450000"));
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenAnswer(invocation -> {
          CostRunContext context = invocation.getArgument(0);
          return CostRunObjectResult.of(
              context,
              null,
              resultDto,
              List.of(part("PART-1")),
              List.of(total("123.450000")));
        });

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getCurrentDisplayVersion().getCostRunNo()).isEqualTo("TRIAL-1");
    verify(versionService).finishTrial(88L, new BigDecimal("123.450000"), 1, 1);
  }

  @Test
  @DisplayName("价格准备允许继续时查询页仍允许发起核算")
  void getCostRunAllowsStartTrialWhenPricePrepareAllowsContinue() {
    when(readinessService.check("OA-001", 101L, "TOP-A", "2026-06", "PTC-1"))
        .thenReturn(
            PricePrepareReadinessResult.notReady(
                "PARTIAL",
                true,
                false,
                "价格准备未完成，实时成本将继续，结果可能缺价",
                "PPR-1",
                "2026-06",
                "PARTIAL",
                1,
                List.of("MAT-1 缺价")));

    var response = service.getCostRun("OA-001", 101L, "2026-06");

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
    when(versionService.createTrial(anyString(), eq(101L), eq("TOP-A"), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(version);
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("123.450000"));
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenAnswer(invocation -> {
          CostRunContext context = invocation.getArgument(0);
          return CostRunObjectResult.of(
              context,
              null,
              resultDto,
              List.of(part("PART-1")),
              List.of(total("123.450000")));
        });

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getCurrentDisplayVersion().getCostRunNo()).isEqualTo("TRIAL-1");
    assertThat(response.getResultHeader().getTotalCost()).isEqualByComparingTo("123.450000");
    assertThat(response.isCanConfirm()).isTrue();
    ArgumentCaptor<CostRunObjectResult> resultCaptor = ArgumentCaptor.forClass(CostRunObjectResult.class);
    verify(resultWriter).writeQuoteResult(resultCaptor.capture(), any(), any());
    assertThat(resultCaptor.getValue().getContext().getCostRunVersionId()).isEqualTo(88L);
    assertThat(resultCaptor.getValue().getContext().getPriceAsOfTime()).isEqualTo(trialStartedAt);
    verify(costingBuildService).buildByOaFormItem(101L, "2026-06");
    verify(pricePrepareService).generate(any());
    verify(versionService).finishTrial(88L, new BigDecimal("123.450000"), 1, 1);
  }

  @Test
  @DisplayName("重新试算成功后清理同范围旧未确认试算及其明细")
  void trialDeletesOlderUnconfirmedTrialsAfterNewTrialSucceeds() {
    QuoteCostRunVersion version = version(88L, "TRIAL-NEW", "TRIAL", "TOP-A");
    version.setPricePrepareNo("PPR-1");
    QuoteCostRunVersion oldTrial = version(77L, "TRIAL-OLD", "TRIAL", "TOP-A");
    oldTrial.setTrialFinishedAt(LocalDateTime.of(2026, 6, 18, 9, 0));
    when(versionService.createTrial(anyString(), eq(101L), eq("TOP-A"), anyString(), anyString(), any(), any(), any(), any()))
        .thenReturn(version);
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(oldTrial), List.of(version));
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("123.450000"));
    when(costRunEngine.run(any(CostRunContext.class)))
        .thenAnswer(invocation -> {
          CostRunContext context = invocation.getArgument(0);
          return CostRunObjectResult.of(
              context,
              null,
              resultDto,
              List.of(part("PART-1")),
              List.of(total("123.450000")));
        });

    var response = service.trial("OA-001", 101L, new QuoteCostRunTrialRequest());

    assertThat(response.getVersions()).extracting("costRunNo").containsExactly("TRIAL-NEW");
    verify(resultMapper).delete(any(Wrapper.class));
    verify(partItemMapper).delete(any(Wrapper.class));
    verify(costItemMapper).delete(any(Wrapper.class));
    verify(traceSnapshotMapper).delete(any(Wrapper.class));
    verify(taskMapper, times(2)).delete(any(Wrapper.class));
    verify(versionMapper).delete(any(Wrapper.class));
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
    verify(versionMapper).update(updateCaptor.capture(), any(Wrapper.class));
    assertThat(updateCaptor.getValue().getStatus()).isEqualTo("VOIDED");
    verify(oaFormItemMapper).update(eq(null), any(Wrapper.class));
    verify(oaFormMapper).update(eq(null), any(Wrapper.class));
  }

  @Test
  @DisplayName("查询默认展示最新 CONFIRMED 并读取版本结果")
  void getCostRunDisplaysLatestConfirmed() {
    QuoteCostRunVersion trial = version(77L, "TRIAL-1", "TRIAL", "TOP-A");
    trial.setTotalCost(new BigDecimal("98.00"));
    QuoteCostRunVersion confirmed = version(88L, "TRIAL-2", "CONFIRMED", "TOP-A");
    confirmed.setVersionNo("COST-20260609-0001-V1");
    confirmed.setTotalCost(new BigDecimal("123.45"));
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trial), List.of(confirmed));
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(confirmed);
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(result("123.45"));
    CostRunPartItem part = new CostRunPartItem();
    part.setPartCode("PART-1");
    part.setAmount(BigDecimal.TEN);
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(part));
    CostRunCostItem cost = new CostRunCostItem();
    cost.setCostCode("TOTAL");
    cost.setAmount(new BigDecimal("123.45"));
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cost));

    var response = service.getCostRun("OA-001", 101L, "2026-06");

    assertThat(response.getLatestTrial()).isNull();
    assertThat(response.getLatestConfirmed().getVersionNo()).isEqualTo("COST-20260609-0001-V1");
    assertThat(response.getCurrentDisplayVersion().getId()).isEqualTo(88L);
    assertThat(response.getResultHeader().getTotalCost()).isEqualByComparingTo("123.45");
    assertThat(response.getPartItems()).hasSize(1);
    assertThat(response.getCostItems()).hasSize(1);
    assertThat(response.isCanStartTrial()).isTrue();
    assertThat(response.isCanConfirm()).isFalse();
    verify(versionMapper).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("查询只返回当前已确认、历史版本列表且历史版本保留入口")
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
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(current);
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(oldTrial, trial), List.of(history, current));
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(result("139.00"));
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    var response = service.getCostRun("OA-001", 101L, "2026-06");

    assertThat(response.getVersions())
        .extracting("id")
        .containsExactly(99L, 88L);
    assertThat(response.getVersions().get(0).getDisplayStatus()).isEqualTo("当前已确认");
    assertThat(response.getVersions().get(0).isCurrentConfirmed()).isTrue();
    assertThat(response.getVersions().get(1).getDisplayStatus()).isEqualTo("历史版本");
    assertThat(response.getVersions().get(1).isStale()).isTrue();
    assertThat(response.getVersions().get(1).isCanViewSheet()).isTrue();
    assertThat(response.getVersions().get(1).isCanViewTrace()).isTrue();
    verify(versionMapper).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("查询时清理未确认试算，没有 CONFIRMED 时不展示版本明细")
  void getCostRunDeletesLatestTrialWhenNoConfirmed() {
    QuoteCostRunVersion trial = version(77L, "TRIAL-1", "TRIAL", "TOP-A");
    trial.setTotalCost(new BigDecimal("137.806"));
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trial), List.of());
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    var response = service.getCostRun("OA-001", 101L, "2026-06");

    assertThat(response.getLatestTrial()).isNull();
    assertThat(response.getLatestConfirmed()).isNull();
    assertThat(response.getCurrentDisplayVersion()).isNull();
    assertThat(response.getVersions()).isEmpty();
    assertThat(response.getPartItems()).isEmpty();
    assertThat(response.getCostItems()).isEmpty();
    assertThat(response.isCanConfirm()).isFalse();
    verify(versionMapper).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("导出按 versionId 读取稳定数据")
  void exportReadsVersionRows() throws Exception {
    QuoteCostRunVersion version = version(88L, "TRIAL-1", "CONFIRMED", "TOP-A");
    version.setVersionNo("COST-1");
    version.setTotalCost(new BigDecimal("123.45"));
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
    String csv = out.toString(StandardCharsets.UTF_8);
    assertThat(csv).contains("TRIAL-1", "PART-1", "TOTAL", "123.45");
  }

  private static OaForm form() {
    OaForm form = new OaForm();
    form.setId(10L);
    form.setOaNo("OA-001");
    form.setCustomer("客户A");
    form.setBusinessUnitType("COMMERCIAL");
    form.setAccountingPeriodMonth("2026-06");
    return form;
  }

  private static QuotePriceTypeConfirmationSummaryResponse confirmedPriceType() {
    QuotePriceTypeConfirmationSummaryResponse response =
        new QuotePriceTypeConfirmationSummaryResponse();
    response.setConfirmNo("PTC-1");
    response.setOaNo("OA-001");
    response.setOaFormItemId(101L);
    response.setProductCode("TOP-A");
    response.setPeriodMonth("2026-06");
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
    version.setPricingMonth("2026-06");
    version.setResultPeriod("2026-06");
    version.setStatus(status);
    return version;
  }

  private static CostRunResult result(String totalCost) {
    CostRunResult result = new CostRunResult();
    result.setOaNo("OA-001");
    result.setProductCode("TOP-A");
    result.setPeriod("2026-06");
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

  private static CostRunCostItemDto total(String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
