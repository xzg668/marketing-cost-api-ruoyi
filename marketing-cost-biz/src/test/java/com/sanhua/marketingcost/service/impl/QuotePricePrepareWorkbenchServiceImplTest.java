package com.sanhua.marketingcost.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummary;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.financefactor.QuoteFinanceFactorReader;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class QuotePricePrepareWorkbenchServiceImplTest {

  private static final String CURRENT_MONTH = CostPricingPeriodUtils.currentPricingMonth();
  private static final YearMonth CURRENT_YEAR_MONTH = YearMonth.parse(CURRENT_MONTH);

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private QuotePriceTypeRecognitionService priceTypeRecognitionService;
  private PricePrepareService pricePrepareService;
  private FinancePricePrepareService financePricePrepareService;
  private QuoteFinanceFactorReader financeQuoteBasePriceService;
  private PricePrepareQueryService pricePrepareQueryService;
  private PricePrepareReadinessService pricePrepareReadinessService;
  private QuotePricePrepareWorkbenchServiceImpl service;
  private PricePrepareInputRevisionService inputRevisions;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    priceTypeRecognitionService = mock(QuotePriceTypeRecognitionService.class);
    pricePrepareService = mock(PricePrepareService.class);
    financePricePrepareService = mock(FinancePricePrepareService.class);
    financeQuoteBasePriceService = mock(QuoteFinanceFactorReader.class);
    when(financeQuoteBasePriceService.read(any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("未发布财务基价"));
    pricePrepareQueryService = mock(PricePrepareQueryService.class);
    pricePrepareReadinessService = mock(PricePrepareReadinessService.class);
    inputRevisions = mock(PricePrepareInputRevisionService.class);
    service =
        new QuotePricePrepareWorkbenchServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            priceTypeRecognitionService,
            pricePrepareService,
            financePricePrepareService,
            financeQuoteBasePriceService,
            pricePrepareQueryService,
            pricePrepareReadinessService,
            mock(
                com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionService
                    .class),
            inputRevisions);
  }

  @Test
  @DisplayName("FCQ-05：产品工作台财务场景只传OA来源批次，由后端读取财务Cu基准")
  void financeScenarioUsesServerSideOrchestration() {
    OaForm form = new OaForm();
    form.setId(10L);
    form.setOaNo("OA-WORKBENCH");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setOaFormId(10L);
    item.setMaterialNo("TOP-WORKBENCH");
    item.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectById(101L)).thenReturn(item);
    when(priceTypeRecognitionService.getRecognition(any(), any(), any()))
        .thenReturn(automaticTypeState(0));
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setOaNo("OA-WORKBENCH");
    generated.setOaFormItemId(101L);
    generated.setTopProductCode("TOP-WORKBENCH");
    generated.setPeriodMonth(CURRENT_MONTH);
    generated.setScenarioType("FINANCE_QUOTE_BASE");
    when(financePricePrepareService.generateFromOa("PPR-OA-WORKBENCH"))
        .thenReturn(
            new FinancePricePrepareGenerateResult(
                "PPR-OA-WORKBENCH",
                "PPR-FIN-WORKBENCH",
                "SCG-WORKBENCH",
                1L,
                new java.math.BigDecimal("90"),
                generated,
                null));

    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);
    request.setPriceAsOfTime(CURRENT_YEAR_MONTH.atDay(15).atTime(12, 0));
    request.setScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setSourcePrepareNo("PPR-OA-WORKBENCH");

    service.generate("OA-WORKBENCH", 101L, request);

    verify(financePricePrepareService).generateFromOa("PPR-OA-WORKBENCH");
    verify(pricePrepareService, org.mockito.Mockito.never()).generate(any());
  }

  @Test
  @DisplayName("最终价格生成一次完成OA锁价和财务基准两份快照")
  void oaGenerateAlsoCreatesFinanceComparison() {
    prepareConfirmedScope();
    PricePrepareGenerateResult oaGenerated =
        generated("PPR-OA-WORKBENCH", QuotePriceScenarioType.OA_LOCKED.name());
    PricePrepareGenerateResult financeGenerated =
        generated("PPR-FIN-WORKBENCH", QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    when(pricePrepareService.generate(any())).thenReturn(oaGenerated);
    when(financePricePrepareService.generateFromOa("PPR-OA-WORKBENCH"))
        .thenReturn(
            new FinancePricePrepareGenerateResult(
                "PPR-OA-WORKBENCH",
                "PPR-FIN-WORKBENCH",
                "SCG-WORKBENCH",
                1L,
                new java.math.BigDecimal("90"),
                financeGenerated,
                null));

    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);

    var response = service.generate("OA-WORKBENCH", 101L, request);

    verify(pricePrepareService).generate(any());
    verify(financePricePrepareService).generateFromOa("PPR-OA-WORKBENCH");
    org.assertj.core.api.Assertions.assertThat(response.getGeneratedResult()).isSameAs(oaGenerated);
    org.assertj.core.api.Assertions.assertThat(response.getFinanceGeneratedResult())
        .isSameAs(financeGenerated);
    org.assertj.core.api.Assertions.assertThat(response.getFinanceCuPricePerKg())
        .isEqualByComparingTo("90");
  }

  @Test
  @DisplayName("价格源预检查走纯计算接口且不生成任何正式场景")
  void sourceCheckUsesTemporaryOaPreviewOnly() {
    prepareConfirmedScope();
    PricePrepareCalculationResult calculation = new PricePrepareCalculationResult();
    calculation.setSummary(generated("PPR-OA-WORKBENCH", QuotePriceScenarioType.OA_LOCKED.name()));
    calculation.setItems(List.of());
    calculation.setGaps(List.of());
    when(pricePrepareService.calculate(any())).thenReturn(calculation);
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);

    var response = service.checkPriceSources("OA-WORKBENCH", 101L, request);

    ArgumentCaptor<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest> captor =
        ArgumentCaptor.forClass(
            com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest.class);
    verify(pricePrepareService).calculate(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getScenarioType())
        .isEqualTo(QuotePriceScenarioType.OA_LOCKED);
    org.assertj.core.api.Assertions.assertThat(response.getReadiness().getStatus())
        .isEqualTo("READY");
    org.assertj.core.api.Assertions.assertThat(response.getReadiness().getPrepareNo()).isNull();
    verify(pricePrepareService, org.mockito.Mockito.never()).generate(any());
    verify(financePricePrepareService, org.mockito.Mockito.never()).generateFromOa(any());
  }

  @Test
  void electronicDrawingPricePreparationUsesPublishedDrawingStructure() {
    prepareConfirmedScope();
    QuoteBomStatus drawing = new QuoteBomStatus();
    drawing.setBomSource("ELECTRONIC_DRAWING_BOM");
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(drawing);
    PricePrepareCalculationResult calculation = new PricePrepareCalculationResult();
    calculation.setSummary(generated("PPR-DRAWING", QuotePriceScenarioType.OA_LOCKED.name()));
    calculation.setItems(List.of());
    calculation.setGaps(List.of());
    when(pricePrepareService.calculate(any())).thenReturn(calculation);
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);

    service.checkPriceSources("OA-WORKBENCH", 101L, request);

    ArgumentCaptor<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest> captor =
        ArgumentCaptor.forClass(
            com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest.class);
    verify(pricePrepareService).calculate(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getSourceType())
        .isEqualTo("E_DRAWING");
  }

  @Test
  @DisplayName("价格源预检查为内存缺口补齐当前路由价格类型")
  void sourceCheckEnrichesPreviewGapPriceType() {
    prepareConfirmedScope();
    when(priceTypeRecognitionService.getRecognition(any(), any(), any()))
        .thenReturn(automaticTypeState(1));
    PricePrepareGap gap = new PricePrepareGap();
    gap.setMaterialCode("MAT-FIXED");
    gap.setGapMaterialCode("MAT-FIXED");
    gap.setGapType("MISSING_PRICE");
    PricePrepareGenerateResult summary =
        generated("PPR-OA-WORKBENCH", QuotePriceScenarioType.OA_LOCKED.name());
    summary.setStatus("PARTIAL");
    summary.setGapCount(1);
    PricePrepareCalculationResult calculation = new PricePrepareCalculationResult();
    calculation.setSummary(summary);
    calculation.setItems(List.of());
    calculation.setGaps(List.of(gap));
    when(pricePrepareService.calculate(any())).thenReturn(calculation);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              List<PricePrepareGap> gaps = invocation.getArgument(0);
              gaps.getFirst().setPriceType("固定价");
              return null;
            })
        .when(pricePrepareQueryService)
        .enrichGaps(any());
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);

    var response = service.checkPriceSources("OA-WORKBENCH", 101L, request);

    verify(pricePrepareQueryService).enrichGaps(calculation.getGaps());
    org.assertj.core.api.Assertions.assertThat(response.getGaps().getRecords())
        .singleElement()
        .extracting(PricePrepareGap::getPriceType)
        .isEqualTo("固定价");
  }

  @Test
  @DisplayName("工作台缺口超过单页时仍返回完整清单供分类统计")
  void workbenchLoadsAllCurrentGapPages() {
    prepareConfirmedScope();
    List<PricePrepareGap> firstPage = java.util.stream.IntStream.range(0, 500)
        .mapToObj(index -> {
          PricePrepareGap gap = new PricePrepareGap();
          gap.setId((long) index + 1);
          return gap;
        }).toList();
    PricePrepareGap lastGap = new PricePrepareGap();
    lastGap.setId(501L);
    when(pricePrepareQueryService.pageGaps(any())).thenAnswer(invocation -> {
      PricePrepareGapQueryRequest query = invocation.getArgument(0);
      return new PricePrepareGapPageResponse(501,
          query.getPage() == 1 ? firstPage : List.of(lastGap));
    });

    var response = service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH);

    org.assertj.core.api.Assertions.assertThat(response.getGaps().getTotal()).isEqualTo(501);
    org.assertj.core.api.Assertions.assertThat(response.getGaps().getRecords())
        .hasSize(501).last().extracting(PricePrepareGap::getId).isEqualTo(501L);
  }

  @Test
  void blockedPricesKeepLatestSupplierReviewWithoutDisplayingOldSuccessfulPrices() {
    prepareConfirmedScope();
    var oldBatch = batch("PPR-OLD", QuotePriceScenarioType.OA_LOCKED.name(), null);
    var current = batch("PPR-REVIEW", QuotePriceScenarioType.OA_LOCKED.name(), null);
    current.setStatus("PARTIAL");
    current.setGapCount(1);
    var review = new com.sanhua.marketingcost.service.pricing.SupplierPriceReview(
        "scope", "current-candidates", null, List.of(), "PENDING", null, null, "待确认");
    current.setSupplierPriceReviews(List.of(review));
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(current, oldBatch)));
    when(pricePrepareReadinessService.check(any(), any(), any(), any()))
        .thenReturn(PricePrepareReadinessResult.notReady(
            "PARTIAL", false, true, "供货比率待确认", null, CURRENT_MONTH, "PARTIAL", 1, List.of()));

    var response = service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH);

    org.assertj.core.api.Assertions.assertThat(response.getSupplierPriceReviews()).containsExactly(review);
    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getFinanceScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getItems().getRecords()).isEmpty();
    verify(pricePrepareService, org.mockito.Mockito.never()).calculate(any());
    verify(pricePrepareService, org.mockito.Mockito.never()).generate(any());
  }

  @Test
  @DisplayName("已有当前OA与财务完整结果时检查直接复用数据库结果，不重新计算")
  void sourceCheckReusesCompletedPersistedPair() {
    prepareConfirmedScope();
    PricePrepareBatch oaBatch =
        batch("PPR-OA-WORKBENCH", QuotePriceScenarioType.OA_LOCKED.name(), null);
    PricePrepareBatch financeBatch =
        batch(
            "PPR-FIN-WORKBENCH",
            QuotePriceScenarioType.FINANCE_QUOTE_BASE.name(),
            "PPR-OA-WORKBENCH");
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(financeBatch, oaBatch)));
    when(pricePrepareReadinessService.check(any(), any(), any(), any()))
        .thenReturn(
            PricePrepareReadinessResult.ready("PPR-OA-WORKBENCH", CURRENT_MONTH, "SUCCESS"));
    preparedFinanceCopper("90", 9L, null);
    QuotePricePrepareGenerateRequest request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);

    when(inputRevisions.isCurrent(any(), any(), any(), any())).thenReturn(true);
    var response = service.checkPriceSources("OA-WORKBENCH", 101L, request);

    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch().getPrepareNo())
        .isEqualTo("PPR-OA-WORKBENCH");
    org.assertj.core.api.Assertions.assertThat(
            response.getFinanceScenario().getBatch().getPrepareNo())
        .isEqualTo("PPR-FIN-WORKBENCH");
    verify(pricePrepareService, org.mockito.Mockito.never()).generate(any());
    verify(pricePrepareService, org.mockito.Mockito.never()).calculate(any());
    verify(financePricePrepareService, org.mockito.Mockito.never()).generateFromOa(any());
  }

  @Test
  void explicitSourceRecheckDoesNotPresentOldFinalPricesAsTheNewCalculation() {
    prepareConfirmedScope();
    var oa = batch("PPR-OA-WORKBENCH", "OA_LOCKED", null);
    var finance = batch("PPR-FIN-WORKBENCH", "FINANCE_QUOTE_BASE", oa.getPrepareNo());
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(finance, oa)));
    when(pricePrepareReadinessService.check(any(), any(), any(), any()))
        .thenReturn(PricePrepareReadinessResult.ready(oa.getPrepareNo(), CURRENT_MONTH, "SUCCESS"));
    preparedFinanceCopper("90", 9L, null);
    var calculated = new PricePrepareCalculationResult();
    calculated.setSummary(generated("PREVIEW", "OA_LOCKED"));
    calculated.setItems(List.of(item("PREVIEW", "2", "2")));
    calculated.setGaps(List.of());
    when(pricePrepareService.calculate(any())).thenReturn(calculated);
    var request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);
    request.setRefreshCandidates(true);
    var response = service.checkPriceSources("OA-WORKBENCH", 101L, request);
    org.assertj.core.api.Assertions.assertThat(
            response.getItems().getRecords().getFirst().getUnitPrice())
        .isEqualByComparingTo("2");
    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getFinanceScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getDifferences()).isEmpty();
  }

  @Test
  void changedInputsRecheckWithoutAForceFlagInsteadOfReturningOldPrices() {
    prepareConfirmedScope();
    var oa = batch("PPR-OA-WORKBENCH", "OA_LOCKED", null);
    var finance = batch("PPR-FIN-WORKBENCH", "FINANCE_QUOTE_BASE", oa.getPrepareNo());
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(finance, oa)));
    when(pricePrepareReadinessService.check(any(), any(), any(), any()))
        .thenReturn(PricePrepareReadinessResult.ready(oa.getPrepareNo(), CURRENT_MONTH, "SUCCESS"));
    preparedFinanceCopper("90", 9L, null);
    var calculated = new PricePrepareCalculationResult();
    calculated.setSummary(generated("PREVIEW", "OA_LOCKED"));
    calculated.setItems(List.of(item("PREVIEW", "2", "2")));
    calculated.setGaps(List.of());
    when(pricePrepareService.calculate(any())).thenReturn(calculated);
    var request = new QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(CURRENT_MONTH);
    when(inputRevisions.isCurrent(any(), any(), any(), any())).thenReturn(false);
    var response = service.checkPriceSources("OA-WORKBENCH", 101L, request);
    org.assertj.core.api.Assertions.assertThat(
            response.getItems().getRecords().getFirst().getUnitPrice())
        .isEqualByComparingTo("2");
    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getFinanceScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(response.getDifferences()).isEmpty();
  }

  @Test
  void sourceCheckIsReadOnlyTransactionalBoundary() throws Exception {
    Transactional transactional =
        QuotePricePrepareWorkbenchServiceImpl.class
            .getMethod(
                "checkPriceSources",
                String.class,
                Long.class,
                QuotePricePrepareGenerateRequest.class)
            .getAnnotation(Transactional.class);

    org.assertj.core.api.Assertions.assertThat(transactional).isNotNull();
    org.assertj.core.api.Assertions.assertThat(transactional.readOnly()).isTrue();
    org.assertj.core.api.Assertions.assertThat(transactional.rollbackFor()).isEmpty();
  }

  @Test
  @DisplayName("工作台按最新OA批次配对财务批次并返回逐料差异")
  void queryReturnsPairedScenarioDifference() {
    prepareConfirmedScope();
    PricePrepareBatch checkOnlyBatch =
        batch("PPR-OA-CHECK-ONLY", QuotePriceScenarioType.OA_LOCKED.name(), null);
    PricePrepareBatch oaBatch =
        batch("PPR-OA-WORKBENCH", QuotePriceScenarioType.OA_LOCKED.name(), null);
    PricePrepareBatch financeBatch =
        batch(
            "PPR-FIN-WORKBENCH",
            QuotePriceScenarioType.FINANCE_QUOTE_BASE.name(),
            "PPR-OA-WORKBENCH");
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(
            new PricePrepareBatchPageResponse(3, List.of(checkOnlyBatch, financeBatch, oaBatch)));
    PricePrepareItem oaItem = item("PPR-OA-WORKBENCH", "102.039", "102.039");
    PricePrepareItem financeItem = item("PPR-FIN-WORKBENCH", "90", "90");
    when(pricePrepareQueryService.pageItems(any()))
        .thenAnswer(
            invocation -> {
              PricePrepareItemQueryRequest query = invocation.getArgument(0);
              if ("PPR-OA-WORKBENCH".equals(query.getPrepareNo())) {
                return new PricePrepareItemPageResponse(1, List.of(oaItem));
              }
              if ("PPR-FIN-WORKBENCH".equals(query.getPrepareNo())) {
                return new PricePrepareItemPageResponse(1, List.of(financeItem));
              }
              return new PricePrepareItemPageResponse(1, List.of(oaItem));
            });
    preparedFinanceCopper("90", 9L, null);

    var response = service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH);

    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch().getPrepareNo())
        .isEqualTo("PPR-OA-WORKBENCH");
    org.assertj.core.api.Assertions.assertThat(
            response.getFinanceScenario().getBatch().getPrepareNo())
        .isEqualTo("PPR-FIN-WORKBENCH");
    org.assertj.core.api.Assertions.assertThat(response.getDifferences()).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(
            response.getDifferences().getFirst().getUnitPriceDifference())
        .isEqualByComparingTo("12.039");
    org.assertj.core.api.Assertions.assertThat(
            response.getDifferenceSummary().getAmountDifference())
        .isEqualByComparingTo("12.039");
    org.assertj.core.api.Assertions.assertThat(response.getFinanceCuPricePerTon())
        .isEqualByComparingTo("90000");
  }

  @Test
  void completedPricesDisplayTheirFinancialSnapshotInsteadOfLegacyCurrentCopper() {
    prepareConfirmedScope();
    PricePrepareBatch oa = batch("PPR-OA-WORKBENCH", "OA_LOCKED", null);
    PricePrepareBatch finance = batch("PPR-FIN-WORKBENCH", "FINANCE_QUOTE_BASE", oa.getPrepareNo());
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(finance, oa)));
    preparedFinanceCopper("95.123456789", null, 501L);

    var response = service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH);

    org.assertj.core.api.Assertions.assertThat(response.getFinanceCuPricePerKg())
        .isEqualByComparingTo("95.123456789");
    org.assertj.core.api.Assertions.assertThat(response.getFinanceCuPricePerTon())
        .isEqualByComparingTo("95123.456789");
    org.assertj.core.api.Assertions.assertThat(response.getFinanceBasePriceId()).isNull();
    verify(financeQuoteBasePriceService, org.mockito.Mockito.never())
        .read(any(), any(), any(), any());
    when(financePricePrepareService.loadPreparedFromOa(oa.getPrepareNo()))
        .thenThrow(new IllegalStateException("原稿财务计算快照损坏"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH))
        .hasMessage("原稿财务计算快照损坏");
    verify(financeQuoteBasePriceService, org.mockito.Mockito.never())
        .read(any(), any(), any(), any());
  }

  @Test
  void currentMissingBandCannotDisplayAnOlderSuccessfulScenarioPair() {
    prepareConfirmedScope();
    var oa = batch("PPR-OA-WORKBENCH", "OA_LOCKED", null);
    var finance = batch("PPR-FIN-WORKBENCH", "FINANCE_QUOTE_BASE", oa.getPrepareNo());
    when(pricePrepareQueryService.pageBatches(any()))
        .thenReturn(new PricePrepareBatchPageResponse(2, List.of(finance, oa)));
    when(pricePrepareReadinessService.check(any(), any(), any(), any()))
        .thenReturn(
            com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult.notReady(
                "PARTIAL",
                false,
                true,
                "区间缺档",
                null,
                CURRENT_MONTH,
                "PARTIAL",
                1,
                List.of("MAT-CU缺档")));
    var response = service.getPricePrepare("OA-WORKBENCH", 101L, CURRENT_MONTH);
    org.assertj.core.api.Assertions.assertThat(response.getReadiness().isBlocking()).isTrue();
    org.assertj.core.api.Assertions.assertThat(response.getItems().getRecords()).isEmpty();
    org.assertj.core.api.Assertions.assertThat(response.getOaScenario().getBatch()).isNull();
    org.assertj.core.api.Assertions.assertThat(
            response.getFinanceScenario().getItems().getRecords())
        .isEmpty();
    org.assertj.core.api.Assertions.assertThat(response.getDifferences()).isEmpty();
    org.assertj.core.api.Assertions.assertThat(response.getBatches().getRecords())
        .containsExactly(finance, oa);
    verify(financePricePrepareService, org.mockito.Mockito.never()).loadPreparedFromOa(any());
    verify(pricePrepareQueryService, org.mockito.Mockito.never()).pageItems(any());
  }

  private void preparedFinanceCopper(String price, Long legacyId, Long factorId) {
    when(financePricePrepareService.loadPreparedFromOa("PPR-OA-WORKBENCH"))
        .thenReturn(
            new FinancePricePrepareGenerateResult(
                "PPR-OA-WORKBENCH",
                "PPR-FIN-WORKBENCH",
                "SCG-WORKBENCH",
                legacyId,
                new BigDecimal(price),
                generated("PPR-FIN-WORKBENCH", "FINANCE_QUOTE_BASE"),
                factorId));
  }

  private void prepareConfirmedScope() {
    OaForm form = new OaForm();
    form.setId(10L);
    form.setOaNo("OA-WORKBENCH");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setOaFormId(10L);
    item.setMaterialNo("TOP-WORKBENCH");
    item.setBusinessUnitType("COMMERCIAL");
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectById(101L)).thenReturn(item);
    when(priceTypeRecognitionService.getRecognition(any(), any(), any()))
        .thenReturn(automaticTypeState(0));
  }

  private PricePrepareGenerateResult generated(String prepareNo, String scenarioType) {
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo(prepareNo);
    generated.setOaNo("OA-WORKBENCH");
    generated.setOaFormItemId(101L);
    generated.setTopProductCode("TOP-WORKBENCH");
    generated.setPeriodMonth(CURRENT_MONTH);
    generated.setScenarioType(scenarioType);
    generated.setStatus("SUCCESS");
    generated.setGapCount(0);
    return generated;
  }

  private PricePrepareBatch batch(String prepareNo, String scenarioType, String sourcePrepareNo) {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setPrepareNo(prepareNo);
    batch.setScenarioType(scenarioType);
    batch.setSourcePrepareNo(sourcePrepareNo);
    batch.setStatus("SUCCESS");
    batch.setGapCount(0);
    return batch;
  }

  private PricePrepareItem item(String prepareNo, String unitPrice, String amount) {
    PricePrepareItem item = new PricePrepareItem();
    item.setPrepareNo(prepareNo);
    item.setSettlementKey("101|TOP-WORKBENCH|MAKE_PART|MAT-CU");
    item.setMaterialCode("MAT-CU");
    item.setMaterialName("铜件");
    item.setItemType("MAKE_PART");
    item.setQuantity(BigDecimal.ONE);
    item.setUnitPrice(new BigDecimal(unitPrice));
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private QuotePriceTypeRecognitionResponse automaticTypeState(int missingCount) {
    QuotePriceTypeRecognitionSummary summary = new QuotePriceTypeRecognitionSummary();
    summary.setMissingSourceCount(missingCount);
    QuotePriceTypeRecognitionResponse response = new QuotePriceTypeRecognitionResponse();
    response.setSummary(summary);
    return response;
  }
}
