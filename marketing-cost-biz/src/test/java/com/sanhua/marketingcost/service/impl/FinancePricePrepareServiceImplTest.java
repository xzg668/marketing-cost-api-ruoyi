package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostPriceScenario;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostPriceScenarioMapper;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.financefactor.QuoteFinanceFactorReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FinancePricePrepareServiceImplTest {
  private PricePrepareBatchMapper batchMapper;
  private PricePrepareItemMapper itemMapper;
  private MakePartPriceCalcRowMapper makePartRowMapper;
  private QuoteFinanceFactorReader financeBasePriceService;
  private PricePrepareService pricePrepareService;
  private FinancePricePrepareServiceImpl service;
  private QuoteCostPriceScenarioMapper scenarioMapper;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, MakePartPriceCalcRow.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCostPriceScenario.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = Mockito.mock(PricePrepareBatchMapper.class);
    itemMapper = Mockito.mock(PricePrepareItemMapper.class);
    makePartRowMapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    financeBasePriceService = Mockito.mock(QuoteFinanceFactorReader.class);
    pricePrepareService = Mockito.mock(PricePrepareService.class);
    scenarioMapper = Mockito.mock(QuoteCostPriceScenarioMapper.class);
    when(batchMapper.saveFinanceFactorSnapshot(any(), any())).thenReturn(1);
    service =
        new FinancePricePrepareServiceImpl(
            batchMapper,
            itemMapper,
            makePartRowMapper,
            financeBasePriceService,
            pricePrepareService,
            scenarioMapper,
            new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void packageFinancialPreparationValidatesItsRangeChildrenInsteadOfForcingSameTotal()
      throws Exception {
    var source = sourceBatch();
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(batchMapper.updateById(source)).thenReturn(1);
    when(itemMapper.selectList(any()))
        .thenReturn(
            List.of(PackageCalculationEvidenceTest.item("RANGE", true)),
            List.of(PackageCalculationEvidenceTest.item("RANGE", false)));
    var generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-1");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    var result = service.generateFromOa("PPR-OA-1");
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("90");
    assertThat(result.financeFactorPriceId()).isEqualTo(2L);
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
  }

  @Test
  void settlementAmountsCannotChangeWithOrdinaryFinancialCopper() {
    var source = sourceBatch();
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(batchMapper.updateById(source)).thenReturn(1);
    var oa = item("SET:SETTLEMENT", "SETTLEMENT", "SETTLEMENT_PRICE", "10", "20");
    var finance = item("SET:SETTLEMENT", "SETTLEMENT", "SETTLEMENT_PRICE", "11", "22");
    when(itemMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    when(financeBasePriceService.read("OA-1", 101L, "2026-05", "COMMERCIAL")).thenReturn(financeBasePrice());
    var generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-SETTLEMENT");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    assertThatThrownBy(() -> service.generateFromOa("PPR-OA-1"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("非Cu单价");
  }

  @Test
  void generatesSingleProductFinanceBatchWithOnlyCuOverrideAndSameInputs() {
    PricePrepareBatch source = sourceBatch();
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(batchMapper.updateById(source)).thenReturn(1);
    PricePrepareItem oaFixed = item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem oaRange = item("SET:RANGE", "MAT-RANGE", "RANGE_PRICE", "8.80", "17.60");
    PricePrepareItem oaLinked = item("SET:CU", "MAT-CU", "LINKED_PRICE", "102.039", "204.078");
    PricePrepareItem oaPackage =
        item("SET:PACKAGE", "PKG-1", "PACKAGE_COMPONENT_PRICE", "5.00", "10.00");
    oaPackage.setItemType("PACKAGE_COMPONENT");
    PricePrepareItem financeFixed = item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem financeRange = item("SET:RANGE", "MAT-RANGE", "RANGE_PRICE", "8.80", "17.60");
    PricePrepareItem financeLinked = item("SET:CU", "MAT-CU", "LINKED_PRICE", "90.000", "180.000");
    PricePrepareItem financePackage =
        item("SET:PACKAGE", "PKG-1", "PACKAGE_COMPONENT_PRICE", "5.00", "10.00");
    financePackage.setItemType("PACKAGE_COMPONENT");
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oaFixed, oaRange, oaLinked, oaPackage))
        .thenReturn(List.of(financeFixed, financeRange, financeLinked, financePackage));
    PriceFactorContext basePrice = financeBasePrice();
    when(financeBasePriceService.read("OA-1", 101L, "2026-05", "COMMERCIAL")).thenReturn(basePrice);
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-1");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);

    var result = service.generateFromOa(" PPR-OA-1 ");

    assertThat(result.sourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(result.financePrepareNo()).isEqualTo("PPR-FIN-1");
    assertThat(result.financeBasePriceId()).isNull();
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("90");
    assertThat(result.scenarioGroupNo()).startsWith("FQG-");
    ArgumentCaptor<PricePrepareGenerateRequest> request =
        ArgumentCaptor.forClass(PricePrepareGenerateRequest.class);
    verify(pricePrepareService).generate(request.capture());
    assertThat(request.getValue().getScenarioType().name()).isEqualTo("FINANCE_QUOTE_BASE");
    assertThat(request.getValue().getSourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(request.getValue().getVariableOverrides())
        .containsOnlyKeys("Cu")
        .containsEntry("Cu", new BigDecimal("90.000000"));
    assertThat(request.getValue().getPriceAsOfTime()).isEqualTo(source.getPriceAsOfTime());
    assertThat(request.getValue().getOaFormItemId()).isEqualTo(101L);
    assertThat(request.getValue().getTopProductCode()).isEqualTo("TOP-1");
    var evidence = ArgumentCaptor.forClass(String.class);
    verify(batchMapper).saveFinanceFactorSnapshot(Mockito.eq("PPR-FIN-1"), evidence.capture());
    assertThat(evidence.getValue()).contains("9001", "CU_90000", "90.000000", "OA-1");
    when(batchMapper.saveFinanceFactorSnapshot(any(), any())).thenReturn(0);
    assertThatThrownBy(() -> service.generateFromOa("PPR-OA-1")).hasMessageContaining("财务基价依据保存失败");
  }

  @Test
  void loadsExistingFinanceSnapshotWithoutRegeneratingPrices() {
    PricePrepareBatch source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    PricePrepareBatch finance = financeBatch(source);
    when(batchMapper.selectOne(any())).thenReturn(source).thenReturn(finance);
    PricePrepareItem oaFixed = item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem financeFixed = item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oaFixed))
        .thenReturn(List.of(financeFixed));
    var history = new QuoteCostPriceScenario();
    history.setCuPrice(new BigDecimal("88.765432"));
    history.setCuPriceSource("FINANCE_QUOTE_BASE");
    history.setCuSourceRefId(701L);
    when(scenarioMapper.selectList(any())).thenReturn(List.of(history));

    var result = service.loadPreparedFromOa("PPR-OA-1");

    assertThat(result.sourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(result.financePrepareNo()).isEqualTo("PPR-FIN-EXISTING");
    assertThat(result.scenarioGroupNo()).isEqualTo("GROUP-1");
    assertThat(result.prepareResult().getStatus()).isEqualTo("SUCCESS");
    assertThat(result.prepareResult().getPriceAsOfTime()).isEqualTo(source.getPriceAsOfTime());
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("88.765432");
    assertThat(result.financeBasePriceId()).isEqualTo(701L);
    assertThat(result.financeFactorPriceId()).isNull();
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
    verify(pricePrepareService, never()).generate(any());
    verify(batchMapper, never()).updateById(any(PricePrepareBatch.class));
  }

  @Test
  void missingFinanceCuBaseBlocksBeforeGeneratingFinanceBatch() {
    PricePrepareBatch source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75")));
    when(financeBasePriceService.read("OA-1", 101L, "2026-05", "COMMERCIAL"))
        .thenThrow(new IllegalArgumentException("未维护2026-05财务报价Cu基准"));

    assertThatThrownBy(() -> service.generateFromOa("PPR-OA-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未维护2026-05财务报价Cu基准");
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  void validatesMultiChildMakePartQuantityGrossNetAndScrapIdentity() {
    PricePrepareBatch source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    PricePrepareItem oaMake = item("SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "20", "40");
    oaMake.setResultRefId(10L);
    PricePrepareItem financeMake = item("SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "18", "36");
    financeMake.setResultRefId(20L);
    when(itemMapper.selectList(any())).thenReturn(List.of(oaMake)).thenReturn(List.of(financeMake));
    when(financeBasePriceService.read("OA-1", 101L, "2026-05", "COMMERCIAL"))
        .thenReturn(financeBasePrice());
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-MAKE");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    MakePartPriceCalcRow oaRef = makeRow(10L, "BATCH-OA", "RAW-A", "SCRAP-A");
    MakePartPriceCalcRow financeRef = makeRow(20L, "BATCH-FIN", "RAW-A", "SCRAP-A");
    when(makePartRowMapper.selectById(10L)).thenReturn(oaRef);
    when(makePartRowMapper.selectById(20L)).thenReturn(financeRef);
    when(makePartRowMapper.selectList(any()))
        .thenReturn(List.of(oaRef, makeRow(11L, "BATCH-OA", "RAW-B", "SCRAP-B")))
        .thenReturn(List.of(financeRef, makeRow(21L, "BATCH-FIN", "RAW-B", "SCRAP-B")));

    var result = service.generateFromOa("PPR-OA-1");

    assertThat(result.financePrepareNo()).isEqualTo("PPR-FIN-MAKE");
    verify(makePartRowMapper).selectById(10L);
    verify(makePartRowMapper).selectById(20L);
  }

  @Test
  void rejectsWholeQuoteBatchAtFcq05Stage() {
    PricePrepareBatch source = sourceBatch();
    source.setOaFormItemId(null);
    source.setTopProductCode(null);
    when(batchMapper.selectOne(any())).thenReturn(source);

    assertThatThrownBy(() -> service.generateFromOa("PPR-OA-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只支持一个产品");
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  void originalQuoteUsesCapturedFinancialCopperAndKeepsNewIdentitySeparate() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    var oa = item("SET:CU", "MAT-CU", "LINKED_PRICE", "100", "200");
    var finance = item("SET:CU", "MAT-CU", "LINKED_PRICE", "95", "190");
    when(itemMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    var snapshot = originalCalculation("95.1234567890123456");
    oa.setInputSnapshotJson(snapshot.getInputSnapshotJson());
    var generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-NATIVE");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    var result = service.generateFromOa("PPR-OA-1");
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("95.1234567890123456");
    assertThat(result.financeBasePriceId()).isNull();
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
    var request = ArgumentCaptor.forClass(PricePrepareGenerateRequest.class);
    verify(pricePrepareService).generate(request.capture());
    assertThat(request.getValue().getVariableOverrides())
        .containsOnlyKeys("Cu")
        .containsEntry("Cu", new BigDecimal("95.1234567890123456"));
  }

  @Test
  void ordinaryMaterialsKeepTheirFinancialBasisWhenScrapUsesDifferentCommercialCopper() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var source = sourceBatch(); source.setScenarioGroupNo("GROUP-SCRAP");
    when(batchMapper.selectOne(any())).thenReturn(source);
    var ordinary = item("SET:CU", "MAT-CU", "LINKED_PRICE", "110", "220");
    ordinary.setInputSnapshotJson(originalCalculation("90").getInputSnapshotJson());
    var ordinaryBase = item("SET:CU", "MAT-CU", "LINKED_PRICE", "90", "180");
    var captured = com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.captured("2026-05", "95", "110000");
    var base = com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.captured("2026-05", "95", null);
    var scrap = item("SET:SCRAP", "SCRAP", "SCRAP_PRICE", captured.unitPrice().toPlainString(), "-1");
    scrap.setInputSnapshotJson(json.writeValueAsString(captured));
    var scrapBase = item("SET:SCRAP", "SCRAP", "SCRAP_PRICE", base.unitPrice().toPlainString(), "-1");
    scrapBase.setInputSnapshotJson(json.writeValueAsString(base));
    when(itemMapper.selectList(any())).thenReturn(List.of(scrap, ordinary), List.of(scrapBase, ordinaryBase));
    var generated = new PricePrepareGenerateResult(); generated.setPrepareNo("PPR-FIN-SCRAP");
    generated.setStatus("SUCCESS"); generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    assertThat(service.generateFromOa("PPR-OA-1").financeCuPricePerKg()).isEqualByComparingTo("90");
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
  }

  @Test
  void nonCopperFormulaEvidenceStillProvidesTheCapturedQuoteCopperBasis() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-NON-COPPER");
    when(batchMapper.selectOne(any())).thenReturn(source);
    var oa = item("SET:CU", "MAT-CU", "LINKED_PRICE", "100", "200");
    var financeItem = item("SET:CU", "MAT-CU", "LINKED_PRICE", "95", "190");
    when(itemMapper.selectList(any())).thenReturn(List.of(oa), List.of(financeItem));
    var snapshot = originalCalculation("95.1234567890123456");
    var json = new ObjectMapper().findAndRegisterModules();
    var evidence =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(snapshot.getInputSnapshotJson());
    var context = json.treeToValue(evidence.path("context"), PriceFactorContext.class);
    evidence.set("context", json.valueToTree(context.evidenceForFactors(java.util.Set.of())));
    snapshot.setInputSnapshotJson(json.writeValueAsString(evidence));
    oa.setInputSnapshotJson(snapshot.getInputSnapshotJson());
    var generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-NON-COPPER");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    var result = service.generateFromOa("PPR-OA-1");
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("95.1234567890123456");
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
  }

  @Test
  void loadingOriginalPreparedPricesRetainsTheirCapturedFinancialVersion() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source, financeBatch(source));
    var oa = item("SET:CU", "MAT-CU", "LINKED_PRICE", "100", "200");
    var finance = item("SET:CU", "MAT-CU", "LINKED_PRICE", "95", "190");
    when(itemMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    finance.setInputSnapshotJson(originalCalculation("95").getInputSnapshotJson());
    var result = service.loadPreparedFromOa("PPR-OA-1");
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("95");
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  void corruptOriginalFinancialSnapshotCannotFallBackToLegacyPrice() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(item("SET:CU", "MAT-CU", "LINKED_PRICE", "100", "200")));
    var broken = originalCalculation("95");
    broken.setInputSnapshotJson("{}");
    var brokenItem = item("SET:CU", "MAT-CU", "LINKED_PRICE", "100", "200");
    brokenItem.setInputSnapshotJson(broken.getInputSnapshotJson());
    when(itemMapper.selectList(any())).thenReturn(List.of(brokenItem));
    assertThatThrownBy(() -> service.generateFromOa("PPR-OA-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("快照");
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
    verify(pricePrepareService, never()).generate(any());
  }

  @Test
  void manufacturingLinkedSnapshotIsNotParsedAsRangeEvidence() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    var oa = item("SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "20", "40");
    oa.setResultRefId(10L);
    var finance = item("SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "18", "36");
    finance.setResultRefId(20L);
    when(itemMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    var linked = originalCalculation("95");
    var rawOa = makeRow(10L, "BATCH-OA", "MAT-CU", "SCRAP-A");
    var rawFinance = makeRow(20L, "BATCH-FIN", "MAT-CU", "SCRAP-A");
    rawOa.setRawPriceType("联动价");
    rawFinance.setRawPriceType("联动价");
    rawOa.setRawInputSnapshotJson(linked.getInputSnapshotJson());
    rawFinance.setRawInputSnapshotJson(linked.getInputSnapshotJson());
    when(makePartRowMapper.selectById(10L)).thenReturn(rawOa);
    when(makePartRowMapper.selectById(20L)).thenReturn(rawFinance);
    when(makePartRowMapper.selectList(any())).thenReturn(List.of(rawOa), List.of(rawFinance));
    var generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-MAKE");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);
    var result = service.generateFromOa("PPR-OA-1");
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("95");
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    verify(financeBasePriceService, never()).read(any(), any(), any(), any());
  }

  @Test
  void fixedOnlyPreparedBasisRemainsExactAfterPublicationChanges() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    var finance = financeBatch(source);
    when(batchMapper.selectOne(any())).thenReturn(source, finance);
    var fixed = item("FIXED", "MAT-FIXED", "FIXED_PRICE", "10", "20");
    when(itemMapper.selectList(any())).thenReturn(List.of(fixed));
    var json = new ObjectMapper().findAndRegisterModules();
    var captured =
        json.treeToValue(
                json.readTree(originalCalculation("91.1234567890123456").getInputSnapshotJson())
                    .path("context"),
                PriceFactorContext.class)
            .financialOnly();
    when(batchMapper.selectFinanceFactorSnapshot(finance.getPrepareNo()))
        .thenReturn(
            json.writeValueAsString(
                java.util.Map.of("oaNo", "OA-1", "oaFormItemId", 101L, "context", captured)));
    when(financeBasePriceService.read(any(), any(), any(), any()))
        .thenThrow(new AssertionError("历史批次禁止读取当前发布"));
    var result = service.loadPreparedFromOa(source.getPrepareNo());
    assertThat(result.financeCuPricePerKg()).isEqualByComparingTo("91.1234567890123456");
    assertThat(result.financeFactorPriceId()).isEqualTo(9001L);
    Mockito.verifyNoInteractions(scenarioMapper, financeBasePriceService, pricePrepareService);
    verify(batchMapper, never()).saveFinanceFactorSnapshot(any(), any());
  }

  @Test
  void missingHistoricalBasisRequiresRegenerationInsteadOfCurrentPrice() {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source, financeBatch(source));
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(item("FIXED", "M", "FIXED_PRICE", "10", "20")));
    assertThatThrownBy(() -> service.loadPreparedFromOa(source.getPrepareNo()))
        .hasMessageContaining("未保存财务基价依据")
        .hasMessageContaining("重新生成");
    Mockito.verifyNoInteractions(financeBasePriceService, pricePrepareService);
  }

  @Test
  void corruptedSavedBasisCannotReadAnotherSource() {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source, financeBatch(source));
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(item("FIXED", "M", "FIXED_PRICE", "10", "20")));
    when(batchMapper.selectFinanceFactorSnapshot(any())).thenReturn("{broken");
    assertThatThrownBy(() -> service.loadPreparedFromOa(source.getPrepareNo()))
        .hasMessageContaining("财务基价依据损坏");
    Mockito.verifyNoInteractions(scenarioMapper, financeBasePriceService, pricePrepareService);
  }

  @Test
  void savedBasisMustBelongToTheSameQuotationItem() throws Exception {
    var source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source, financeBatch(source));
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(item("FIXED", "M", "FIXED_PRICE", "10", "20")));
    when(batchMapper.selectFinanceFactorSnapshot(any()))
        .thenReturn(
            new ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(
                    java.util.Map.of(
                        "oaNo", "OA-1", "oaFormItemId", 999L, "context", financeBasePrice())));
    assertThatThrownBy(() -> service.loadPreparedFromOa(source.getPrepareNo()))
        .hasMessageContaining("与报价产品不一致");
    Mockito.verifyNoInteractions(scenarioMapper, financeBasePriceService, pricePrepareService);
  }

  private PriceLinkedCalcItem originalCalculation(String price) throws Exception {
    var factor =
        new FactorPriceSnapshot.FactorValue(
            901L, 9001L, "电解铜", "1#Cu", new BigDecimal(price), "CNY/KG", true, List.of());
    var scope =
        new FactorPriceScope(
            "COMMERCIAL", "210", "2026-05", "CU_90000", FactorPriceScope.Condition.GENERAL);
    var financial =
        new FactorPriceSnapshot(
            scope,
            80L,
            2,
            "财务.xlsx",
            "hash",
            "content",
            java.util.Map.of("AVG|1#CU", factor),
            List.of("CU_90000"),
            "tester",
            LocalDateTime.of(2026, 5, 1, 1, 0),
            "change");
    var context =
        new PriceFactorContext(
            "MAT-CU",
            new PriceMonthSelection("2026-05", "2026-05"),
            financial,
            new BigDecimal("100000"));
    var row = new PriceLinkedCalcItem();
    row.setId(801L);
    row.setItemCode("MAT-CU");
    row.setInputSnapshotJson(
        new ObjectMapper()
            .findAndRegisterModules()
            .writeValueAsString(
                java.util.Map.of("calculatorVersion", "ORIGINAL_V1", "context", context)));
    return row;
  }

  private PricePrepareBatch sourceBatch() {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setId(1L);
    batch.setPrepareNo("PPR-OA-1");
    batch.setOaNo("OA-1");
    batch.setOaFormItemId(101L);
    batch.setTopProductCode("TOP-1");
    batch.setPeriodMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setBomPurpose("主制造");
    batch.setSourceType("U9");
    batch.setScenarioType("OA_LOCKED");
    batch.setStatus("SUCCESS");
    batch.setGapCount(0);
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 5, 18, 10, 20, 30));
    return batch;
  }

  private PricePrepareBatch financeBatch(PricePrepareBatch source) {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setId(2L);
    batch.setPrepareNo("PPR-FIN-EXISTING");
    batch.setOaNo(source.getOaNo());
    batch.setOaFormItemId(source.getOaFormItemId());
    batch.setTopProductCode(source.getTopProductCode());
    batch.setPeriodMonth(source.getPeriodMonth());
    batch.setBusinessUnitType(source.getBusinessUnitType());
    batch.setBomPurpose(source.getBomPurpose());
    batch.setSourceType(source.getSourceType());
    batch.setScenarioType("FINANCE_QUOTE_BASE");
    batch.setScenarioGroupNo(source.getScenarioGroupNo());
    batch.setSourcePrepareNo(source.getPrepareNo());
    batch.setStatus("SUCCESS");
    batch.setTotalCount(1);
    batch.setSuccessCount(1);
    batch.setWarningCount(0);
    batch.setGapCount(0);
    batch.setPriceAsOfTime(source.getPriceAsOfTime());
    batch.setPriceAsOfSource("REQUEST");
    return batch;
  }

  private PriceFactorContext financeBasePrice() {
    try {
      var json = new ObjectMapper().findAndRegisterModules();
      return json.treeToValue(
              json.readTree(originalCalculation("90.000000").getInputSnapshotJson())
                  .path("context"),
              PriceFactorContext.class)
          .financialOnly();
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private PricePrepareItem item(
      String settlementKey,
      String materialCode,
      String resultRefType,
      String unitPrice,
      String amount) {
    PricePrepareItem item = new PricePrepareItem();
    item.setSettlementKey(settlementKey);
    item.setMaterialCode(materialCode);
    item.setItemType("NORMAL");
    item.setQuantity(new BigDecimal("2"));
    item.setUnitPrice(new BigDecimal(unitPrice));
    item.setAmount(new BigDecimal(amount));
    item.setResultRefType(resultRefType);
    item.setStatus("READY");
    return item;
  }

  private MakePartPriceCalcRow makeRow(Long id, String batchId, String child, String scrap) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setId(id);
    row.setCalcBatchId(batchId);
    row.setParentMaterialNo("MAKE-1");
    row.setChildMaterialNo(child);
    row.setScrapCode(scrap);
    row.setQtyPerParent(new BigDecimal("0.080"));
    row.setGrossWeightG(new BigDecimal("80"));
    row.setNetWeightG(new BigDecimal("55"));
    return row;
  }
}
