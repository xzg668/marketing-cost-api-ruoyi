package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
import com.sanhua.marketingcost.service.QuoteCuMaterialDiffService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("FCQ-07 单产品双场景成本编排")
class QuoteCuAdjustmentCalcServiceImplTest {
  private static final String MONTH = "2026-07";
  private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 15, 9, 30);

  private PricePrepareBatchMapper batchMapper;
  private QuotePriceTypeConfirmBatchMapper confirmMapper;
  private FinancePricePrepareService financePrepareService;
  private QuoteCostRunVersionService versionService;
  private QuoteCostRunVersionMapper versionMapper;
  private CostRunEngine costRunEngine;
  private CostRunResultWriter resultWriter;
  private QuoteCuMaterialDiffService diffService;
  private QuoteCostPriceScenarioMapper scenarioMapper;
  private CostRunResultMapper resultMapper;
  private QuoteCuAdjustmentCalcServiceImpl service;
  private OaForm form;
  private OaFormItem item;
  private PricePrepareBatch oaBatch;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmBatch.class);
    TableInfoHelper.initTableInfo(assistant, CostRunResult.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(PricePrepareBatchMapper.class);
    confirmMapper = mock(QuotePriceTypeConfirmBatchMapper.class);
    financePrepareService = mock(FinancePricePrepareService.class);
    versionService = mock(QuoteCostRunVersionService.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    costRunEngine = mock(CostRunEngine.class);
    resultWriter = mock(CostRunResultWriter.class);
    diffService = mock(QuoteCuMaterialDiffService.class);
    scenarioMapper = mock(QuoteCostPriceScenarioMapper.class);
    resultMapper = mock(CostRunResultMapper.class);
    service = new QuoteCuAdjustmentCalcServiceImpl(
        batchMapper,
        confirmMapper,
        financePrepareService,
        versionService,
        versionMapper,
        costRunEngine,
        resultWriter,
        diffService,
        scenarioMapper,
        resultMapper);

    form = form(new BigDecimal("102039"));
    item = item();
    oaBatch = oaBatch();
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(oaBatch);
    when(confirmMapper.selectOne(any(Wrapper.class))).thenReturn(confirmation());
    when(financePrepareService.loadPreparedFromOa("PPR-OA-1")).thenReturn(financePrepare());
    when(versionService.createTrial(
            anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString()))
        .thenReturn(version(101L));
    when(versionMapper.updateById(any(QuoteCostRunVersion.class))).thenReturn(1);
    when(costRunEngine.run(any())).thenAnswer(invocation -> costResult(invocation.getArgument(0), "100", "140"));
    when(diffService.calculate(101L)).thenReturn(diff(101L, "12", 1));
    when(scenarioMapper.insert(any(QuoteCostPriceScenario.class))).thenReturn(1);
    CostRunResult stored = new CostRunResult();
    stored.setId(501L);
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(stored);
    when(resultMapper.updateById(any(CostRunResult.class))).thenReturn(1);
  }

  @Test
  @DisplayName("完整成功：只跑财务成本一次并保存正差额、双场景和最终报价")
  void calculatesPositiveAdjustmentAndPersistsAllSummaries() {
    var result = service.calculate(request());

    assertThat(result.financeMaterialCost()).isEqualByComparingTo("100.00000000");
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("12.00000000");
    assertThat(result.oaMaterialCost()).isEqualByComparingTo("112.00000000");
    assertThat(result.totalCost()).isEqualByComparingTo("140.00000000");
    assertThat(result.finalQuoteAmount()).isEqualByComparingTo("152.00000000");
    assertThat(result.costResult().getResult().getTotalCost()).isEqualByComparingTo("140");
    assertThat(result.costResult().getResult().getFinalQuoteAmount()).isEqualByComparingTo("152");
    assertThat(result.costResult().getCostItems())
        .extracting(CostRunCostItemDto::getCostCode)
        .containsExactly("MATERIAL", "TOTAL");
    verify(costRunEngine, times(1)).run(any());
    verify(financePrepareService).loadPreparedFromOa("PPR-OA-1");
    verify(financePrepareService, never()).generateFromOa(anyString());
    verify(resultWriter).writeQuoteResult(any(), any(), any());

    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(costRunEngine).run(contextCaptor.capture());
    CostRunContext context = contextCaptor.getValue();
    assertThat(context.getPricePrepareNo()).isEqualTo("PPR-FIN-1");
    assertThat(context.getPriceScenarioType()).isEqualTo("FINANCE_QUOTE_BASE");
    assertThat(context.getPriceAsOfTime()).isEqualTo(AS_OF);
    assertThat(context.getPriceOrgCode()).isEqualTo("210");
    assertThat(context.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");

    ArgumentCaptor<QuoteCostPriceScenario> scenarioCaptor =
        ArgumentCaptor.forClass(QuoteCostPriceScenario.class);
    verify(scenarioMapper, times(2)).insert(scenarioCaptor.capture());
    List<QuoteCostPriceScenario> scenarios = scenarioCaptor.getAllValues();
    assertThat(scenarios).extracting(QuoteCostPriceScenario::getScenarioType)
        .containsExactly("OA_LOCKED", "FINANCE_QUOTE_BASE");
    assertThat(scenarios.get(0).getMaterialCost()).isEqualByComparingTo("112");
    assertThat(scenarios.get(0).getTotalCost()).isNull();
    assertThat(scenarios.get(1).getMaterialCost()).isEqualByComparingTo("100");
    assertThat(scenarios.get(1).getTotalCost()).isEqualByComparingTo("140");
    assertThat(scenarios.get(0).getInputSnapshotHash())
        .isEqualTo(scenarios.get(1).getInputSnapshotHash())
        .hasSize(64);

    ArgumentCaptor<CostRunResult> resultPatch = ArgumentCaptor.forClass(CostRunResult.class);
    verify(resultMapper).updateById(resultPatch.capture());
    assertThat(resultPatch.getValue().getTotalCost()).isEqualByComparingTo("140");
    assertThat(resultPatch.getValue().getFinalQuoteAmount()).isEqualByComparingTo("152");
  }

  @Test
  @DisplayName("无Cu材料：OA未填Cu也允许，差额为0且最终报价等于财务总成本")
  void noCuMaterialAllowsMissingOaCuAndKeepsZeroAdjustment() {
    form.setCopperPrice(null);
    when(diffService.calculate(101L)).thenReturn(diff(101L, "0", 0));

    var result = service.calculate(request());

    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("0");
    assertThat(result.oaMaterialCost()).isEqualByComparingTo("100");
    assertThat(result.finalQuoteAmount()).isEqualByComparingTo("140");
    assertThat(result.version().getOaCuPrice()).isNull();
  }

  @Test
  @DisplayName("财务Cu高于OA时保留负差额并从财务总成本扣回")
  void calculatesNegativeAdjustment() {
    when(diffService.calculate(101L)).thenReturn(diff(101L, "-8.5", 1));

    var result = service.calculate(request());

    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("-8.5");
    assertThat(result.oaMaterialCost()).isEqualByComparingTo("91.5");
    assertThat(result.finalQuoteAmount()).isEqualByComparingTo("131.5");
  }

  @Test
  @DisplayName("存在Cu材料但OA未锁Cu时阻断，结果和场景均不落库")
  void blocksCuMaterialWhenOaCuIsMissing() {
    form.setCopperPrice(null);
    when(diffService.calculate(101L)).thenReturn(diff(101L, "0", 1));

    assertThatThrownBy(() -> service.calculate(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未锁定Cu价格");
    verify(resultWriter, never()).writeQuoteResult(any(), any(), any());
    verify(resultMapper, never()).updateById(any(CostRunResult.class));
    verify(scenarioMapper, never()).insert(any(QuoteCostPriceScenario.class));
  }

  @Test
  @DisplayName("成本头totalCost与TOTAL不一致时保存前阻断")
  void blocksWhenTotalIdentityIsBroken() {
    when(costRunEngine.run(any())).thenAnswer(invocation -> {
      CostRunObjectResult result = costResult(invocation.getArgument(0), "100", "140");
      result.getResult().setTotalCost(new BigDecimal("141"));
      return result;
    });

    assertThatThrownBy(() -> service.calculate(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("totalCost与TOTAL");
    verify(diffService, never()).calculate(any());
    verify(resultWriter, never()).writeQuoteResult(any(), any(), any());
  }

  @Test
  @DisplayName("Cu差额或最终报价混入普通成本项时保存前阻断")
  void blocksAdjustmentCostItemFromParticipatingInTotal() {
    when(costRunEngine.run(any())).thenAnswer(invocation -> {
      CostRunObjectResult result = costResult(invocation.getArgument(0), "100", "140");
      result.setCostItems(List.of(
          cost("MATERIAL", "100"),
          cost("CU_MATERIAL_ADJUSTMENT", "12"),
          cost("TOTAL", "152")));
      result.getResult().setTotalCost(new BigDecimal("152"));
      return result;
    });

    assertThatThrownBy(() -> service.calculate(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不能写入普通成本项");
    verify(diffService, never()).calculate(any());
    verify(resultWriter, never()).writeQuoteResult(any(), any(), any());
  }

  @Test
  @DisplayName("任一场景写入失败即抛错，编排方法声明整事务回滚")
  void writeFailureIsTransactional() throws Exception {
    when(scenarioMapper.insert(any(QuoteCostPriceScenario.class))).thenReturn(1, 0);

    assertThatThrownBy(() -> service.calculate(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("场景汇总写入失败");
    Transactional transactional = QuoteCuAdjustmentCalcServiceImpl.class
        .getMethod("calculate", QuoteCuAdjustmentCalcRequest.class)
        .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.rollbackFor()).contains(Exception.class);
  }

  @Test
  @DisplayName("重复试算每次创建新版本，更新只落在各自新版本上")
  void repeatedTrialCreatesNewVersionWithoutOverwritingOldOne() {
    AtomicLong id = new AtomicLong(100);
    when(versionService.createTrial(
            anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString()))
        .thenAnswer(invocation -> version(id.incrementAndGet()));
    when(diffService.calculate(any())).thenAnswer(invocation ->
        diff(invocation.getArgument(0), "12", 1));
    CostRunResult stored1 = new CostRunResult();
    stored1.setId(501L);
    CostRunResult stored2 = new CostRunResult();
    stored2.setId(502L);
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(stored1, stored2);

    var first = service.calculate(request());
    var second = service.calculate(request());

    assertThat(first.version().getId()).isEqualTo(101L);
    assertThat(second.version().getId()).isEqualTo(102L);
    assertThat(second.version().getId()).isNotEqualTo(first.version().getId());
    verify(versionService, times(2)).createTrial(
        anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(),
        anyString(), anyString());
  }

  private QuoteCuAdjustmentCalcRequest request() {
    return new QuoteCuAdjustmentCalcRequest(
        form, item, MONTH, "PPR-OA-1", "QUOTE:11", ignored -> {});
  }

  private OaForm form(BigDecimal copperPrice) {
    OaForm value = new OaForm();
    value.setId(10L);
    value.setOaNo("OA-1");
    value.setCustomer("ACME");
    value.setBusinessUnitType("COMMERCIAL");
    value.setCopperPrice(copperPrice);
    return value;
  }

  private OaFormItem item() {
    OaFormItem value = new OaFormItem();
    value.setId(11L);
    value.setOaFormId(10L);
    value.setMaterialNo("PROD-1");
    value.setProductName("阀件");
    value.setPackageMethod("BOX");
    value.setBusinessUnitType("COMMERCIAL");
    return value;
  }

  private PricePrepareBatch oaBatch() {
    PricePrepareBatch value = new PricePrepareBatch();
    value.setId(1L);
    value.setPrepareNo("PPR-OA-1");
    value.setOaNo("OA-1");
    value.setOaFormItemId(11L);
    value.setTopProductCode("PROD-1");
    value.setPriceTypeConfirmNo("PTC-1");
    value.setPeriodMonth(MONTH);
    value.setScenarioType(QuotePriceScenarioType.OA_LOCKED.name());
    value.setScenarioGroupNo("FQG-1");
    value.setStatus("SUCCESS");
    value.setGapCount(0);
    value.setPriceAsOfTime(AS_OF);
    value.setBomPurpose("NON_BARE");
    value.setSourceType("SYSTEM");
    value.setBusinessUnitType("COMMERCIAL");
    return value;
  }

  private QuotePriceTypeConfirmBatch confirmation() {
    QuotePriceTypeConfirmBatch value = new QuotePriceTypeConfirmBatch();
    value.setConfirmNo("PTC-1");
    value.setBomConfirmNo("BOMC-1");
    value.setStatus(QuotePriceTypeConfirmBatch.STATUS_CONFIRMED);
    return value;
  }

  private FinancePricePrepareGenerateResult financePrepare() {
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-1");
    generated.setScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    generated.setScenarioGroupNo("FQG-1");
    generated.setSourcePrepareNo("PPR-OA-1");
    generated.setPriceAsOfTime(AS_OF);
    generated.setStatus("SUCCESS");
    return new FinancePricePrepareGenerateResult(
        "PPR-OA-1", "PPR-FIN-1", "FQG-1", 900L, new BigDecimal("90"), generated);
  }

  private QuoteCostRunVersion version(Long id) {
    QuoteCostRunVersion value = new QuoteCostRunVersion();
    value.setId(id);
    value.setCostRunNo("CR-" + id);
    value.setOaNo("OA-1");
    value.setOaFormItemId(11L);
    value.setProductCode("PROD-1");
    value.setPricingMonth(MONTH);
    value.setResultPeriod(MONTH);
    value.setPriceTypeConfirmNo("PTC-1");
    value.setBomConfirmNo("BOMC-1");
    value.setStatus("TRIAL");
    value.setBusinessUnitType("COMMERCIAL");
    value.setTrialStartedAt(AS_OF);
    return value;
  }

  private CostRunObjectResult costResult(
      CostRunContext context, String materialAmount, String totalAmount) {
    CostRunResultDto header = new CostRunResultDto();
    header.setTotalCost(new BigDecimal(totalAmount));
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setPartCode("CU-PART");
    part.setAmount(new BigDecimal(materialAmount));
    return CostRunObjectResult.of(
        context,
        null,
        header,
        List.of(part),
        List.of(cost("MATERIAL", materialAmount), cost("TOTAL", totalAmount)));
  }

  private CostRunCostItemDto cost(String code, String amount) {
    CostRunCostItemDto value = new CostRunCostItemDto();
    value.setCostCode(code);
    value.setAmount(new BigDecimal(amount));
    return value;
  }

  private QuoteCuMaterialDiffResult diff(Long versionId, String adjustment, int cuCount) {
    QuoteCuMaterialDiffItem row = new QuoteCuMaterialDiffItem();
    row.setSettlementKey("11|PROD-1|1|CU-PART|NORMAL");
    row.setBomRowId(1L);
    row.setMaterialCode("CU-PART");
    row.setItemType("NORMAL");
    row.setQuantity(new BigDecimal("2"));
    row.setContributesToAdjustment(1);
    row.setCuAffected(cuCount > 0 ? 1 : 0);
    row.setDiffAmount(new BigDecimal(adjustment));
    return new QuoteCuMaterialDiffResult(
        versionId, "CR-" + versionId, new BigDecimal(adjustment), 1, 0, cuCount, List.of(row));
  }
}
