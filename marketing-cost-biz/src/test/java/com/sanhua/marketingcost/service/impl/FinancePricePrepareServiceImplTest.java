package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import com.sanhua.marketingcost.service.PricePrepareService;
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
  private QuotePriceTypeConfirmBatchMapper confirmBatchMapper;
  private QuoteBomConfirmationMapper bomConfirmationMapper;
  private MakePartPriceCalcRowMapper makePartRowMapper;
  private FinanceQuoteBasePriceService financeBasePriceService;
  private PricePrepareService pricePrepareService;
  private FinancePricePrepareServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmBatch.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomConfirmation.class);
    TableInfoHelper.initTableInfo(assistant, MakePartPriceCalcRow.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = Mockito.mock(PricePrepareBatchMapper.class);
    itemMapper = Mockito.mock(PricePrepareItemMapper.class);
    confirmBatchMapper = Mockito.mock(QuotePriceTypeConfirmBatchMapper.class);
    bomConfirmationMapper = Mockito.mock(QuoteBomConfirmationMapper.class);
    makePartRowMapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    financeBasePriceService = Mockito.mock(FinanceQuoteBasePriceService.class);
    pricePrepareService = Mockito.mock(PricePrepareService.class);
    service = new FinancePricePrepareServiceImpl(
        batchMapper,
        itemMapper,
        confirmBatchMapper,
        bomConfirmationMapper,
        makePartRowMapper,
        financeBasePriceService,
        pricePrepareService);
  }

  @Test
  void generatesSingleProductFinanceBatchWithOnlyCuOverrideAndSameInputs() {
    PricePrepareBatch source = sourceBatch();
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(batchMapper.updateById(source)).thenReturn(1);
    when(confirmBatchMapper.selectOne(any())).thenReturn(confirmedBatch());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(confirmedBom());
    PricePrepareItem oaFixed = item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem oaRange = item("SET:RANGE", "MAT-RANGE", "RANGE_PRICE", "8.80", "17.60");
    PricePrepareItem oaLinked = item("SET:CU", "MAT-CU", "LINKED_PRICE", "102.039", "204.078");
    PricePrepareItem oaPackage = item(
        "SET:PACKAGE", "PKG-1", "PACKAGE_COMPONENT_PRICE", "5.00", "10.00");
    oaPackage.setItemType("PACKAGE_COMPONENT");
    PricePrepareItem financeFixed = item(
        "SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem financeRange = item(
        "SET:RANGE", "MAT-RANGE", "RANGE_PRICE", "8.80", "17.60");
    PricePrepareItem financeLinked = item(
        "SET:CU", "MAT-CU", "LINKED_PRICE", "90.000", "180.000");
    PricePrepareItem financePackage = item(
        "SET:PACKAGE", "PKG-1", "PACKAGE_COMPONENT_PRICE", "5.00", "10.00");
    financePackage.setItemType("PACKAGE_COMPONENT");
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oaFixed, oaRange, oaLinked, oaPackage))
        .thenReturn(List.of(financeFixed, financeRange, financeLinked, financePackage));
    FinanceBasePrice basePrice = financeBasePrice();
    when(financeBasePriceService.getRequired("2026-05")).thenReturn(basePrice);
    PricePrepareGenerateResult generated = new PricePrepareGenerateResult();
    generated.setPrepareNo("PPR-FIN-1");
    generated.setStatus("SUCCESS");
    generated.setPriceAsOfTime(source.getPriceAsOfTime());
    when(pricePrepareService.generate(any())).thenReturn(generated);

    var result = service.generateFromOa(" PPR-OA-1 ");

    assertThat(result.sourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(result.financePrepareNo()).isEqualTo("PPR-FIN-1");
    assertThat(result.financeBasePriceId()).isEqualTo(701L);
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
    assertThat(request.getValue().getPriceTypeConfirmNo()).isEqualTo("QPTC-1");
  }

  @Test
  void loadsExistingFinanceSnapshotWithoutRegeneratingPrices() {
    PricePrepareBatch source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    PricePrepareBatch finance = financeBatch(source);
    when(batchMapper.selectOne(any())).thenReturn(source).thenReturn(finance);
    when(confirmBatchMapper.selectOne(any())).thenReturn(confirmedBatch());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(confirmedBom());
    PricePrepareItem oaFixed =
        item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    PricePrepareItem financeFixed =
        item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75");
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oaFixed))
        .thenReturn(List.of(financeFixed));
    when(financeBasePriceService.getRequired("2026-05")).thenReturn(financeBasePrice());

    var result = service.loadPreparedFromOa("PPR-OA-1");

    assertThat(result.sourcePrepareNo()).isEqualTo("PPR-OA-1");
    assertThat(result.financePrepareNo()).isEqualTo("PPR-FIN-EXISTING");
    assertThat(result.scenarioGroupNo()).isEqualTo("GROUP-1");
    assertThat(result.prepareResult().getStatus()).isEqualTo("SUCCESS");
    assertThat(result.prepareResult().getPriceAsOfTime()).isEqualTo(source.getPriceAsOfTime());
    verify(pricePrepareService, never()).generate(any());
    verify(batchMapper, never()).updateById(any(PricePrepareBatch.class));
  }

  @Test
  void missingFinanceCuBaseBlocksBeforeGeneratingFinanceBatch() {
    PricePrepareBatch source = sourceBatch();
    source.setScenarioGroupNo("GROUP-1");
    when(batchMapper.selectOne(any())).thenReturn(source);
    when(confirmBatchMapper.selectOne(any())).thenReturn(confirmedBatch());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(confirmedBom());
    when(itemMapper.selectList(any())).thenReturn(List.of(
        item("SET:FIXED", "MAT-FIXED", "FIXED_PRICE", "12.30", "30.75")));
    when(financeBasePriceService.getRequired("2026-05"))
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
    when(confirmBatchMapper.selectOne(any())).thenReturn(confirmedBatch());
    when(bomConfirmationMapper.selectOne(any())).thenReturn(confirmedBom());
    PricePrepareItem oaMake = item(
        "SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "20", "40");
    oaMake.setResultRefId(10L);
    PricePrepareItem financeMake = item(
        "SET:MAKE", "MAKE-1", "MAKE_PART_PRICE", "18", "36");
    financeMake.setResultRefId(20L);
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oaMake))
        .thenReturn(List.of(financeMake));
    when(financeBasePriceService.getRequired("2026-05")).thenReturn(financeBasePrice());
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
        .thenReturn(List.of(
            oaRef,
            makeRow(11L, "BATCH-OA", "RAW-B", "SCRAP-B")))
        .thenReturn(List.of(
            financeRef,
            makeRow(21L, "BATCH-FIN", "RAW-B", "SCRAP-B")));

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
    verify(financeBasePriceService, never()).getRequired(any());
    verify(pricePrepareService, never()).generate(any());
  }

  private PricePrepareBatch sourceBatch() {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setId(1L);
    batch.setPrepareNo("PPR-OA-1");
    batch.setOaNo("OA-1");
    batch.setOaFormItemId(101L);
    batch.setTopProductCode("TOP-1");
    batch.setPriceTypeConfirmNo("QPTC-1");
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
    batch.setPriceTypeConfirmNo(source.getPriceTypeConfirmNo());
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

  private QuotePriceTypeConfirmBatch confirmedBatch() {
    QuotePriceTypeConfirmBatch batch = new QuotePriceTypeConfirmBatch();
    batch.setConfirmNo("QPTC-1");
    batch.setBomConfirmNo("QBOM-1");
    batch.setStatus(QuotePriceTypeConfirmBatch.STATUS_CONFIRMED);
    return batch;
  }

  private FinanceBasePrice financeBasePrice() {
    FinanceBasePrice base = new FinanceBasePrice();
    base.setId(701L);
    base.setPriceMonth("2026-05");
    base.setPrice(new BigDecimal("90.000000"));
    base.setBusinessUnitType("COMMERCIAL");
    return base;
  }

  private QuoteBomConfirmation confirmedBom() {
    QuoteBomConfirmation confirmation = new QuoteBomConfirmation();
    confirmation.setConfirmNo("QBOM-1");
    confirmation.setConfirmStatus(QuoteBomConfirmation.STATUS_CONFIRMED);
    return confirmation;
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
    item.setPriceTypeConfirmItemId(9001L);
    item.setResultRefType(resultRefType);
    item.setStatus("READY");
    return item;
  }

  private MakePartPriceCalcRow makeRow(
      Long id, String batchId, String child, String scrap) {
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
