package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffItemResult;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionService;
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
  private FinancePricePrepareService financePrepareService;
  private QuoteCostRunVersionService versionService;
  private QuoteCostRunVersionMapper versionMapper;
  private CostRunEngine costRunEngine;
  private QuoteCostPriceScenarioMapper scenarioMapper;
  private QuoteCuAdjustmentCalcServiceImpl service;
  private OaForm form;
  private OaFormItem item;
  private PricePrepareBatch oaBatch;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(PricePrepareBatchMapper.class);
    financePrepareService = mock(FinancePricePrepareService.class);
    versionService = mock(QuoteCostRunVersionService.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    costRunEngine = mock(CostRunEngine.class);
    scenarioMapper = mock(QuoteCostPriceScenarioMapper.class);
    service = new QuoteCuAdjustmentCalcServiceImpl(
        batchMapper,
        financePrepareService,
        versionService,
        versionMapper,
        costRunEngine,
        scenarioMapper, null);

    form = form(new BigDecimal("102039"));
    item = item();
    oaBatch = oaBatch();
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(oaBatch);
    when(financePrepareService.loadPreparedFromOa("PPR-OA-1")).thenReturn(financePrepare());
    when(versionService.createTrial(
            anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(version(101L));
    when(versionMapper.updateById(any(QuoteCostRunVersion.class))).thenReturn(1);
    when(costRunEngine.run(any())).thenAnswer(invocation -> costResult(invocation.getArgument(0), "100", "140"));
    when(scenarioMapper.insert(any(QuoteCostPriceScenario.class))).thenReturn(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void incompatibleQuotationOrPreparationIsRejectedBeforeCosting(int mismatch) {
    switch (mismatch) {
      case 0 -> item.setOaFormId(-1L);
      case 1 -> oaBatch.setOaNo("another-quotation");
      case 2 -> oaBatch.setOaFormItemId(-1L);
      case 3 -> oaBatch.setTopProductCode("another-product");
      case 4 -> oaBatch.setBusinessUnitType("HOUSEHOLD");
      case 5 -> oaBatch.setPeriodMonth("2025-01");
      default -> throw new AssertionError();
    }
    assertThatThrownBy(() -> service.calculate(request())).isInstanceOf(RuntimeException.class);
    org.mockito.Mockito.verifyNoInteractions(costRunEngine);
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
  }

  @Test
  @DisplayName("任一场景写入失败即抛错，编排方法声明整事务回滚")
  void writeFailureIsTransactional() throws Exception {
    Transactional transactional = QuoteCuAdjustmentCalcServiceImpl.class
        .getMethod("calculate", QuoteCuAdjustmentCalcRequest.class)
        .getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.rollbackFor()).contains(Exception.class);
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

  private FinancePricePrepareGenerateResult financePrepare() {
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-1");
    generated.setScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    generated.setScenarioGroupNo("FQG-1");
    generated.setSourcePrepareNo("PPR-OA-1");
    generated.setPriceAsOfTime(AS_OF);
    generated.setStatus("SUCCESS");
    return new FinancePricePrepareGenerateResult(
        "PPR-OA-1", "PPR-FIN-1", "FQG-1", 900L, new BigDecimal("90"), generated, null);
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

  private EffectiveTechnicalDataInput effectiveTechnicalV2() {
    return new EffectiveTechnicalDataInput(
        9001L,
        9002L,
        2,
        MONTH,
        EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        "f".repeat(64),
        LocalDateTime.of(2026, 7, 15, 9, 31),
        true,
        true,
        true,
        new BigDecimal("3.50"),
        new BigDecimal("0.704"),
        new BigDecimal("18.90"),
        List.of(),
        List.of(),
        List.of(), null, null);
  }
}
