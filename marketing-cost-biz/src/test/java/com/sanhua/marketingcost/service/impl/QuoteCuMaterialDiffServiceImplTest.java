package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class QuoteCuMaterialDiffServiceImplTest {
  private static final Long VERSION_ID = 100L;
  private static final LocalDateTime PRICE_AS_OF =
      LocalDateTime.of(2026, 5, 18, 10, 20, 30);

  private QuoteCostRunVersionMapper versionMapper;
  private PricePrepareBatchMapper batchMapper;
  private PricePrepareItemMapper itemMapper;
  private MakePartPriceCalcRowMapper makePartRowMapper;
  private PriceLinkedCalcItemMapper linkedCalcItemMapper;
  private FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper;
  private ObjectMapper objectMapper;
  private QuoteCuMaterialDiffServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, FactorQuoteBaseMapping.class);
    TableInfoHelper.initTableInfo(assistant, MakePartPriceCalcRow.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCuMaterialDiffItem.class);
  }

  @BeforeEach
  void setUp() {
    versionMapper = Mockito.mock(QuoteCostRunVersionMapper.class);
    batchMapper = Mockito.mock(PricePrepareBatchMapper.class);
    itemMapper = Mockito.mock(PricePrepareItemMapper.class);
    makePartRowMapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    linkedCalcItemMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    factorQuoteBaseMappingMapper = Mockito.mock(FactorQuoteBaseMappingMapper.class);
    objectMapper = new ObjectMapper();
    service = new QuoteCuMaterialDiffServiceImpl(
        versionMapper,
        batchMapper,
        itemMapper,
        makePartRowMapper,
        linkedCalcItemMapper,
        factorQuoteBaseMappingMapper,
        objectMapper);
  }

  @Test
  void calculatesPositiveCuDifferenceAndReturnsTransientTrace() {
    PricePrepareItem oa = linkedItem(
        11L, "PPR-OA", "SET-CU", "MAT-CU", "2", "142.603", "285.206", 501L);
    PricePrepareItem finance = linkedItem(
        21L, "PPR-FIN", "SET-CU", "MAT-CU", "2", "130.564", "261.128", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(
            501L,
            "MAT-CU",
            "142.603",
            "[Cu]+[Zn]+[Al]",
            vars("Cu", "102.039", "Zn", "21.684", "Al", "18.88"),
            "OA_LOCKED")))
        .thenReturn(List.of(linked(
            601L,
            "MAT-CU",
            "130.564",
            "[Cu]+[Zn]+[Al]",
            vars("Cu", "90", "Zn", "21.684", "Al", "18.88"),
            "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("24.07800000");
    assertThat(result.settlementCount()).isEqualTo(1);
    assertThat(result.rawComponentCount()).isZero();
    assertThat(result.cuAffectedSettlementCount()).isEqualTo(1);
    QuoteCuMaterialDiffItem row = result.items().get(0);
    assertThat(row.getDetailLevel()).isEqualTo("SETTLEMENT");
    assertThat(row.getContributesToAdjustment()).isEqualTo(1);
    assertThat(row.getOaAmount()).isEqualByComparingTo("285.20600000");
    assertThat(row.getFinanceAmount()).isEqualByComparingTo("261.12800000");
    assertThat(row.getDiffAmount()).isEqualByComparingTo("24.07800000");
    assertThat(row.getTraceJson())
        .contains("oaLinkedTrace", "financeLinkedTrace", "102.039", "90");
  }

  @Test
  void recognizesMappedFactorIdentityAsCuVariable() {
    PricePrepareItem oa = linkedItem(
        11L, "PPR-OA", "301070047|301990315/原材料", "301990315", "1",
        "88.674600", "88.674600", 501L);
    PricePrepareItem finance = linkedItem(
        21L, "PPR-FIN", "301070047|301990315/原材料", "301990315", "1",
        "78.212389", "78.212389", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(
            501L,
            "301990315",
            "88.674600",
            "([factor_identity_191]*0.982)",
            vars("factor_identity_191", "102.039"),
            "OA_LOCKED")))
        .thenReturn(List.of(linked(
            601L,
            "301990315",
            "78.212389",
            "([factor_identity_191]*0.982)",
            vars("factor_identity_191", "90"),
            "FINANCE_QUOTE_BASE")));
    FactorQuoteBaseMapping mapping = new FactorQuoteBaseMapping();
    mapping.setFactorIdentityId(191L);
    mapping.setVariableCode("Cu");
    mapping.setQuoteFieldCode("copper_price");
    mapping.setEnabled(1);
    when(factorQuoteBaseMappingMapper.selectList(any())).thenReturn(List.of(mapping));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("10.46221100");
    assertThat(result.cuAffectedSettlementCount()).isEqualTo(1);
    assertThat(result.items()).singleElement().satisfies(row -> {
      assertThat(row.getCuAffected()).isEqualTo(1);
      assertThat(row.getDiffAmount()).isEqualByComparingTo("10.46221100");
    });
  }

  @Test
  void calculatesZeroDifferenceForNonCuSettlement() {
    PricePrepareItem oa = fixedItem(
        11L, "PPR-OA", "SET-FIXED", "MAT-FIXED", "2", "10", "20");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-FIXED", "MAT-FIXED", "2", "10", "20");
    stubBase(List.of(oa), List.of(finance));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(0).getDiffAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(0).getCuAffected()).isZero();
    verify(linkedCalcItemMapper, never()).selectList(any());
  }

  @Test
  void calculatesNegativeCuDifference() {
    PricePrepareItem oa = linkedItem(
        11L, "PPR-OA", "SET-CU", "MAT-CU", "1", "80", "80", 501L);
    PricePrepareItem finance = linkedItem(
        21L, "PPR-FIN", "SET-CU", "MAT-CU", "1", "90", "90", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(
            501L, "MAT-CU", "80", "[Cu]", vars("Cu", "80"), "OA_LOCKED")))
        .thenReturn(List.of(linked(
            601L,
            "MAT-CU",
            "90",
            "[Cu]",
            vars("Cu", "90"),
            "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("-10.00000000");
    assertThat(result.items().get(0).getDiffAmount()).isEqualByComparingTo("-10");
  }

  @Test
  void roundsAmountsAndDifferenceToEightDecimals() {
    PricePrepareItem oa = linkedItem(
        11L,
        "PPR-OA",
        "SET-ROUND",
        "MAT-CU",
        "3",
        "0.333333335",
        "1.00000001",
        501L);
    PricePrepareItem finance = linkedItem(
        21L,
        "PPR-FIN",
        "SET-ROUND",
        "MAT-CU",
        "3",
        "0.333333331",
        "0.99999999",
        601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(
            501L,
            "MAT-CU",
            "0.333333335",
            "[Cu]",
            vars("Cu", "0.333333335"),
            "OA_LOCKED")))
        .thenReturn(List.of(linked(
            601L,
            "MAT-CU",
            "0.333333331",
            "[Cu]",
            vars("Cu", "0.333333331"),
            "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("0.00000002");
    assertThat(result.items().get(0).getDiffAmount().scale()).isEqualTo(8);
  }

  @Test
  void blocksWhenOneScenarioMissesSettlementKey() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "10", "10");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-B", "MAT-B", "1", "10", "10");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结算键范围不一致");
  }

  @Test
  void blocksDuplicateSettlementKey() {
    PricePrepareItem first = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "10", "10");
    PricePrepareItem duplicate = fixedItem(
        12L, "PPR-OA", "SET-A", "MAT-A", "1", "10", "10");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-A", "MAT-A", "1", "10", "10");
    stubBase(List.of(first, duplicate), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结算键重复");
  }

  @Test
  void blocksQuantityMismatch() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-A", "MAT-A", "3", "10", "30");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("数量");
  }

  @Test
  void blocksItemFromAnotherBatchScopeEvenWhenSettlementKeyMatches() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-A", "MAT-A", "2", "10", "20");
    finance.setPeriodMonth("2026-06");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("计价月份");
  }

  @Test
  void blocksNonCuSettlementWithNonZeroDifference() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(
        21L, "PPR-FIN", "SET-A", "MAT-A", "2", "9", "18");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("非Cu结算行");
  }

  @Test
  void blocksLinkedNonCuVariableMismatch() {
    PricePrepareItem oa = linkedItem(
        11L, "PPR-OA", "SET-CU", "MAT-CU", "1", "120", "120", 501L);
    PricePrepareItem finance = linkedItem(
        21L, "PPR-FIN", "SET-CU", "MAT-CU", "1", "109", "109", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(
            501L,
            "MAT-CU",
            "120",
            "[Cu]+[Zn]",
            vars("Cu", "100", "Zn", "20"),
            "OA_LOCKED")))
        .thenReturn(List.of(linked(
            601L,
            "MAT-CU",
            "109",
            "[Cu]+[Zn]",
            vars("Cu", "90", "Zn", "19"),
            "FINANCE_QUOTE_BASE")));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("非Cu变量zn");
  }

  @Test
  void blocksDifferentCuValuesInsideSameScenario() {
    PricePrepareItem oaA = linkedItem(
        11L, "PPR-OA", "SET-A", "MAT-A", "1", "100", "100", 501L);
    PricePrepareItem oaB = linkedItem(
        12L, "PPR-OA", "SET-B", "MAT-B", "1", "101", "101", 502L);
    PricePrepareItem financeA = linkedItem(
        21L, "PPR-FIN", "SET-A", "MAT-A", "1", "90", "90", 601L);
    PricePrepareItem financeB = linkedItem(
        22L, "PPR-FIN", "SET-B", "MAT-B", "1", "90", "90", 602L);
    stubBase(List.of(oaA, oaB), List.of(financeA, financeB));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(
            linked(501L, "MAT-A", "100", "[Cu]", vars("Cu", "100"), "OA_LOCKED"),
            linked(502L, "MAT-B", "101", "[Cu]", vars("Cu", "101"), "OA_LOCKED")))
        .thenReturn(List.of(
            linked(601L, "MAT-A", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE"),
            linked(602L, "MAT-B", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE")));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不同Cu值");
  }

  @Test
  void makePartParentContributesOnceAndComponentsOnlyExplain() {
    PricePrepareItem oaItem = makeItem(11L, "PPR-OA", "10", "20", 701L);
    PricePrepareItem financeItem = makeItem(21L, "PPR-FIN", "8", "16", 801L);
    stubBase(List.of(oaItem), List.of(financeItem));
    MakePartPriceCalcRow oaA = makeRow(
        701L, "BATCH-OA", "OA_LOCKED", "RAW-A", "SCRAP-A", "联动价", "固定价",
        "102.039", "5", "6", "10", "55");
    MakePartPriceCalcRow oaB = makeRow(
        702L, "BATCH-OA", "OA_LOCKED", "RAW-B", "SCRAP-B", "固定价", "联动价",
        "20", "102.039", "4", "10", "45");
    MakePartPriceCalcRow financeA = makeRow(
        801L, "BATCH-FIN", "FINANCE_QUOTE_BASE", "RAW-A", "SCRAP-A", "联动价", "固定价",
        "90", "5", "5", "8", "55");
    MakePartPriceCalcRow financeB = makeRow(
        802L, "BATCH-FIN", "FINANCE_QUOTE_BASE", "RAW-B", "SCRAP-B", "固定价", "联动价",
        "20", "90", "3", "8", "45");
    when(makePartRowMapper.selectById(701L)).thenReturn(oaA);
    when(makePartRowMapper.selectById(801L)).thenReturn(financeA);
    when(makePartRowMapper.selectList(any()))
        .thenReturn(List.of(oaA, oaB))
        .thenReturn(List.of(financeA, financeB));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(
            linked(901L, "RAW-A", "102.039", "[Cu]", vars("Cu", "102.039"), "OA_LOCKED"),
            linked(
                902L,
                "SCRAP-B",
                "102.039",
                "[Cu]",
                vars("Cu", "102.039"),
                "OA_LOCKED")))
        .thenReturn(List.of(
            linked(
                911L,
                "RAW-A",
                "90",
                "[Cu]",
                vars("Cu", "90"),
                "FINANCE_QUOTE_BASE"),
            linked(
                912L,
                "SCRAP-B",
                "90",
                "[Cu]",
                vars("Cu", "90"),
                "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("4.00000000");
    assertThat(result.settlementCount()).isEqualTo(1);
    assertThat(result.rawComponentCount()).isEqualTo(2);
    assertThat(result.items()).hasSize(3);
    QuoteCuMaterialDiffItem parent = result.items().get(0);
    assertThat(parent.getDetailLevel()).isEqualTo("SETTLEMENT");
    assertThat(parent.getContributesToAdjustment()).isEqualTo(1);
    assertThat(parent.getDiffAmount()).isEqualByComparingTo("4");
    assertThat(result.items().subList(1, 3))
        .allSatisfy(component -> {
          assertThat(component.getDetailLevel()).isEqualTo("RAW_COMPONENT");
          assertThat(component.getContributesToAdjustment()).isZero();
          assertThat(component.getParentSettlementKey()).isEqualTo("SET-MAKE");
          assertThat(component.getTraceJson())
              .contains("qtyPerParent", "grossWeightG", "netWeightG");
        });
    assertThat(result.items().subList(1, 3).stream()
        .map(QuoteCuMaterialDiffItem::getDiffAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(parent.getDiffAmount());
  }

  @Test
  void blocksMakePartNetWeightMismatch() {
    PricePrepareItem oaItem = makeItem(11L, "PPR-OA", "6", "12", 701L);
    PricePrepareItem financeItem = makeItem(21L, "PPR-FIN", "5", "10", 801L);
    stubBase(List.of(oaItem), List.of(financeItem));
    MakePartPriceCalcRow oa = makeRow(
        701L, "BATCH-OA", "OA_LOCKED", "RAW-A", "SCRAP-A", "联动价", "固定价",
        "102.039", "5", "6", "6", "55");
    MakePartPriceCalcRow finance = makeRow(
        801L, "BATCH-FIN", "FINANCE_QUOTE_BASE", "RAW-A", "SCRAP-A", "联动价", "固定价",
        "90", "5", "5", "5", "54");
    when(makePartRowMapper.selectById(701L)).thenReturn(oa);
    when(makePartRowMapper.selectById(801L)).thenReturn(finance);
    when(makePartRowMapper.selectList(any()))
        .thenReturn(List.of(oa))
        .thenReturn(List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("净重");
  }

  private void stubBase(
      List<PricePrepareItem> oaItems, List<PricePrepareItem> financeItems) {
    when(versionMapper.selectById(VERSION_ID)).thenReturn(version());
    when(batchMapper.selectOne(any()))
        .thenReturn(batch("PPR-OA", "OA_LOCKED", null))
        .thenReturn(batch("PPR-FIN", "FINANCE_QUOTE_BASE", "PPR-OA"));
    when(itemMapper.selectList(any())).thenReturn(oaItems).thenReturn(financeItems);
  }

  private QuoteCostRunVersion version() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(VERSION_ID);
    version.setCostRunNo("RUN-1");
    version.setOaNo("OA-1");
    version.setOaFormItemId(101L);
    version.setProductCode("TOP-1");
    version.setPricingMonth("2026-05");
    version.setOaPricePrepareNo("PPR-OA");
    version.setFinancePricePrepareNo("PPR-FIN");
    version.setBusinessUnitType("COMMERCIAL");
    return version;
  }

  private PricePrepareBatch batch(String prepareNo, String scenarioType, String sourcePrepareNo) {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setPrepareNo(prepareNo);
    batch.setOaNo("OA-1");
    batch.setOaFormItemId(101L);
    batch.setTopProductCode("TOP-1");
    batch.setPeriodMonth("2026-05");
    batch.setBomPurpose("主制造");
    batch.setSourceType("U9");
    batch.setScenarioType(scenarioType);
    batch.setScenarioGroupNo("GROUP-1");
    batch.setSourcePrepareNo(sourcePrepareNo);
    batch.setStatus("SUCCESS");
    batch.setGapCount(0);
    batch.setPriceAsOfTime(PRICE_AS_OF);
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private PricePrepareItem linkedItem(
      Long id,
      String prepareNo,
      String key,
      String code,
      String quantity,
      String unitPrice,
      String amount,
      Long resultRefId) {
    PricePrepareItem item = item(id, prepareNo, key, code, quantity, unitPrice, amount);
    item.setPriceSource("联动价");
    item.setResultRefType("LINKED_PRICE");
    item.setResultRefId(resultRefId);
    return item;
  }

  private PricePrepareItem fixedItem(
      Long id,
      String prepareNo,
      String key,
      String code,
      String quantity,
      String unitPrice,
      String amount) {
    PricePrepareItem item = item(id, prepareNo, key, code, quantity, unitPrice, amount);
    item.setPriceSource("固定价");
    item.setResultRefType("FIXED_PRICE");
    item.setResultRefId(300L);
    return item;
  }

  private PricePrepareItem makeItem(
      Long id, String prepareNo, String unitPrice, String amount, Long resultRefId) {
    PricePrepareItem item = item(
        id, prepareNo, "SET-MAKE", "MAKE-1", "2", unitPrice, amount);
    item.setPriceSource("自制件计算价");
    item.setResultRefType("MAKE_PART_PRICE");
    item.setResultRefId(resultRefId);
    return item;
  }

  private PricePrepareItem item(
      Long id,
      String prepareNo,
      String key,
      String code,
      String quantity,
      String unitPrice,
      String amount) {
    PricePrepareItem item = new PricePrepareItem();
    item.setId(id);
    item.setPrepareNo(prepareNo);
    item.setPeriodMonth("2026-05");
    item.setOaNo("OA-1");
    item.setOaFormItemId(101L);
    item.setTopProductCode("TOP-1");
    item.setBomRowId(1001L);
    item.setMaterialCode(code);
    item.setMaterialName(code + "名称");
    item.setItemType("NORMAL");
    item.setQuantity(new BigDecimal(quantity));
    item.setUnitPrice(new BigDecimal(unitPrice));
    item.setAmount(new BigDecimal(amount));
    item.setStatus("READY");
    item.setSettlementKey(key);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private PriceLinkedCalcItem linked(
      Long id,
      String code,
      String unitPrice,
      String formula,
      Map<String, BigDecimal> variables,
      String factorSource) {
    PriceLinkedCalcItem row = new PriceLinkedCalcItem();
    row.setId(id);
    row.setItemCode(code);
    row.setPartUnitPrice(new BigDecimal(unitPrice));
    row.setFactorSource(factorSource);
    row.setCalcStatus("OK");
    row.setTraceJson(trace(formula, variables, factorSource));
    return row;
  }

  private String trace(
      String formula, Map<String, BigDecimal> variables, String factorSource) {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("normalizedExpr", formula);
    trace.put("variables", variables);
    List<Map<String, Object>> details = new ArrayList<>();
    variables.forEach((code, value) -> details.add(Map.of(
        "code", code,
        "value", value,
        "source",
        "Cu".equalsIgnoreCase(code) || code.startsWith("factor_identity_")
            ? factorSource
            : "OA_LOCKED")));
    trace.put("variableDetails", details);
    try {
      return objectMapper.writeValueAsString(trace);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Map<String, BigDecimal> vars(String... pairs) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      result.put(pairs[i], new BigDecimal(pairs[i + 1]));
    }
    return result;
  }

  private MakePartPriceCalcRow makeRow(
      Long id,
      String batchId,
      String scenario,
      String child,
      String scrap,
      String rawPriceType,
      String scrapPriceType,
      String rawPrice,
      String scrapPrice,
      String costPrice,
      String parentTotal,
      String netWeight) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setId(id);
    row.setCalcBatchId(batchId);
    row.setOaNo("OA-1");
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-05");
    row.setPriceAsOfTime(PRICE_AS_OF);
    row.setPriceScenarioType(scenario);
    row.setParentMaterialNo("MAKE-1");
    row.setParentMaterialName("制造件1");
    row.setItemProcessType("原材料加工");
    row.setChildMaterialNo(child);
    row.setChildMaterialName(child + "名称");
    row.setStockUnit("KG");
    row.setQtyPerParent(new BigDecimal("0.08"));
    row.setGrossWeightG(new BigDecimal("80"));
    row.setNetWeightG(new BigDecimal(netWeight));
    row.setRawPriceType(rawPriceType);
    row.setRawUnitPrice(new BigDecimal(rawPrice));
    row.setScrapCode(scrap);
    row.setScrapName(scrap + "名称");
    row.setScrapPriceType(scrapPriceType);
    row.setScrapUnitPrice(new BigDecimal(scrapPrice));
    row.setNoScrapConfirmed(false);
    row.setOutsourceFee(BigDecimal.ZERO);
    row.setCostPrice(new BigDecimal(costPrice));
    row.setParentTotalCostPrice(new BigDecimal(parentTotal));
    row.setStatus("OK");
    return row;
  }
}
