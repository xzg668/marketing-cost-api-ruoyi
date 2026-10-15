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
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffItemResult;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
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
import org.mockito.Mockito;

class QuoteCuMaterialDiffServiceImplTest {
  private static final Long VERSION_ID = 100L;
  private static final LocalDateTime PRICE_AS_OF = LocalDateTime.of(2026, 5, 18, 10, 20, 30);

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
    service =
        new QuoteCuMaterialDiffServiceImpl(
            versionMapper,
            batchMapper,
            itemMapper,
            makePartRowMapper,
            linkedCalcItemMapper,
            factorQuoteBaseMappingMapper,
            objectMapper);
  }

  @Test
  void packageCopperDifferenceIsIncludedOnlyOnceAtTheSettlementParent() throws Exception {
    objectMapper.findAndRegisterModules();
    var oa = fixedItem(11L, "PPR-OA", "SET:PACK", "PACK", "3", "13", "39");
    var finance = fixedItem(21L, "PPR-FIN", "SET:PACK", "PACK", "3", "12", "36");
    oa.setResultRefType("PACKAGE_PRICE");
    finance.setResultRefType("PACKAGE_PRICE");
    oa.setInputSnapshotJson(
        PackageCalculationEvidenceTest.item("RANGE", true).getInputSnapshotJson());
    finance.setInputSnapshotJson(
        PackageCalculationEvidenceTest.item("RANGE", false).getInputSnapshotJson());
    stubBase(List.of(oa), List.of(finance));
    var version = version();
    version.setOaCuPrice(new BigDecimal("100"));
    version.setFinanceCuPrice(new BigDecimal("90"));
    when(versionMapper.selectById(VERSION_ID)).thenReturn(version);
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo("3");
    assertThat(result.items()).hasSize(1);
    assertThat(result.cuAffectedSettlementCount()).isOne();
    verify(linkedCalcItemMapper, never()).selectList(any());
  }

  @Test
  void calculatesPositiveCuDifferenceAndReturnsTransientTrace() {
    PricePrepareItem oa =
        linkedItem(11L, "PPR-OA", "SET-CU", "MAT-CU", "2", "142.603", "285.206", 501L);
    PricePrepareItem finance =
        linkedItem(21L, "PPR-FIN", "SET-CU", "MAT-CU", "2", "130.564", "261.128", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(
                    501L,
                    "MAT-CU",
                    "142.603",
                    "[Cu]+[Zn]+[Al]",
                    vars("Cu", "102.039", "Zn", "21.684", "Al", "18.88"),
                    "OA_LOCKED")))
        .thenReturn(
            List.of(
                linked(
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
    QuoteCuMaterialDiffItemResult row = result.items().get(0);
    assertThat(row.getDetailLevel()).isEqualTo("SETTLEMENT");
    assertThat(row.getContributesToAdjustment()).isEqualTo(1);
    assertThat(row.getOaAmount()).isEqualByComparingTo("285.20600000");
    assertThat(row.getFinanceAmount()).isEqualByComparingTo("261.12800000");
    assertThat(row.getDiffAmount()).isEqualByComparingTo("24.07800000");
    assertThat(row.getTraceJson()).contains("oaLinkedTrace", "financeLinkedTrace", "102.039", "90");
  }

  @Test
  void technicalFormulaKeepsCapturedResultAfterARecalculationAtTheSameTime() throws Exception {
    var oa = linkedItem(11L, "PPR-OA", "SET-TECH", "TECH", "2", "130", "260", 501L);
    var finance = linkedItem(21L, "PPR-FIN", "SET-TECH", "TECH", "2", "100", "200", 601L);
    stubBase(List.of(oa), List.of(finance));
    var oldOa = technicalLinked(501L, "130", "120", "10", "OA_LOCKED", false);
    var oldFinance = technicalLinked(601L, "100", "90", "10", "FINANCE_QUOTE_BASE", false);
    var newerOa = technicalLinked(502L, "131", "120", "11", "OA_LOCKED", false);
    var newerFinance = technicalLinked(602L, "111", "100", "11", "FINANCE_QUOTE_BASE", false);
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(newerOa, oldOa), List.of(newerFinance, oldFinance));
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo("60");
    assertThat(result.cuAffectedSettlementCount()).isEqualTo(1);
  }

  @Test
  void technicalDerivedCopperUsesCapturedPrimitiveInputs() throws Exception {
    objectMapper.findAndRegisterModules();
    var oa = linkedItem(11L, "PPR-OA", "SET-TECH", "TECH", "1", "118", "118", 501L);
    var finance = linkedItem(21L, "PPR-FIN", "SET-TECH", "TECH", "1", "91", "91", 601L);
    stubBase(List.of(oa), List.of(finance));
    var customer = technicalLinked(501L, "118", "120", "10", "OA_LOCKED", true);
    var base = technicalLinked(601L, "91", "90", "10", "FINANCE_QUOTE_BASE", true);
    when(linkedCalcItemMapper.selectList(any())).thenReturn(List.of(customer), List.of(base));
    assertThat(service.calculate(VERSION_ID).adjustmentAmount()).isEqualByComparingTo("27");
    var changed = objectMapper.readTree(base.getInputSnapshotJson());
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("primitiveInputs"))
        .put("Zn", new BigDecimal("11"));
    base.setInputSnapshotJson(objectMapper.writeValueAsString(changed));
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any())).thenReturn(List.of(customer), List.of(base));
    assertThatThrownBy(() -> service.calculate(VERSION_ID)).hasMessageContaining("非Cu变量");
  }

  private PriceLinkedCalcItem technicalLinked(
      Long id, String price, String copper, String zinc, String side, boolean derived)
      throws Exception {
    var formula = derived ? "[Cu_scrap]+[Zn]" : "[Cu]+[Zn]";
    var row = linked(id, "TECH", price, formula, vars("Cu", copper, "Zn", zinc), side);
    row.setCalculatorVersion("TECH_FORMULA_V1");
    row.setSourcePriceRecordId(900L);
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("calculatorVersion", "TECH_FORMULA_V1");
    snapshot.put("ruleId", 900L);
    snapshot.put("technicalVersionId", 81L);
    snapshot.put("expression", formula);
    snapshot.put("definitions", Map.of("Cu_scrap", Map.of("ratio", "0.9")));
    snapshot.put(
        "factorKeys",
        derived ? Map.of("Zn", "AVG|1#ZN") : Map.of("Cu", "AVG|1#CU", "Zn", "AVG|1#ZN"));
    snapshot.put(
        "primitiveInputs",
        derived ? vars("Cu_scrap:ratio", "0.9", "Zn", zinc) : vars("Cu", copper, "Zn", zinc));
    snapshot.put("usedFinanceKeys", derived ? List.of("AVG|1#CU") : List.of());
    if (derived) {
      var context =
          objectMapper
              .readTree(
                  rangeSnapshot(
                      "AVG|1#CU", "90000", side.equals("OA_LOCKED") ? "120000" : null, price))
              .path("context");
      snapshot.put("context", context);
    } else snapshot.put("context", Map.of("financial", Map.of("batchId", 1L)));
    row.setInputSnapshotJson(objectMapper.writeValueAsString(snapshot));
    return row;
  }

  @Test
  void recognizesMappedFactorIdentityAsCuVariable() {
    PricePrepareItem oa =
        linkedItem(
            11L,
            "PPR-OA",
            "301070047|301990315/原材料",
            "301990315",
            "1",
            "88.674600",
            "88.674600",
            501L);
    PricePrepareItem finance =
        linkedItem(
            21L,
            "PPR-FIN",
            "301070047|301990315/原材料",
            "301990315",
            "1",
            "78.212389",
            "78.212389",
            601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(
                    501L,
                    "301990315",
                    "88.674600",
                    "([factor_identity_191]*0.982)",
                    vars("factor_identity_191", "102.039"),
                    "OA_LOCKED")))
        .thenReturn(
            List.of(
                linked(
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
    assertThat(result.items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getCuAffected()).isEqualTo(1);
              assertThat(row.getDiffAmount()).isEqualByComparingTo("10.46221100");
            });
  }

  @Test
  void calculatesZeroDifferenceForNonCuSettlement() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-FIXED", "MAT-FIXED", "2", "10", "20");
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-FIXED", "MAT-FIXED", "2", "10", "20");
    stubBase(List.of(oa), List.of(finance));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(0).getDiffAmount()).isEqualByComparingTo("0");
    assertThat(result.items().get(0).getCuAffected()).isZero();
    verify(linkedCalcItemMapper, never()).selectList(any());
  }

  @Test
  void calculatesNegativeCuDifference() {
    PricePrepareItem oa = linkedItem(11L, "PPR-OA", "SET-CU", "MAT-CU", "1", "80", "80", 501L);
    PricePrepareItem finance =
        linkedItem(21L, "PPR-FIN", "SET-CU", "MAT-CU", "1", "90", "90", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(linked(501L, "MAT-CU", "80", "[Cu]", vars("Cu", "80"), "OA_LOCKED")))
        .thenReturn(
            List.of(linked(601L, "MAT-CU", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("-10.00000000");
    assertThat(result.items().get(0).getDiffAmount()).isEqualByComparingTo("-10");
  }

  @Test
  void roundsAmountsAndDifferenceToEightDecimals() {
    PricePrepareItem oa =
        linkedItem(11L, "PPR-OA", "SET-ROUND", "MAT-CU", "3", "0.333333335", "1.00000001", 501L);
    PricePrepareItem finance =
        linkedItem(21L, "PPR-FIN", "SET-ROUND", "MAT-CU", "3", "0.333333331", "0.99999999", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(
                    501L, "MAT-CU", "0.333333335", "[Cu]", vars("Cu", "0.333333335"), "OA_LOCKED")))
        .thenReturn(
            List.of(
                linked(
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
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-B", "MAT-B", "1", "10", "10");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结算键范围不一致");
  }

  @Test
  void blocksDuplicateSettlementKey() {
    PricePrepareItem first = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "10", "10");
    PricePrepareItem duplicate = fixedItem(12L, "PPR-OA", "SET-A", "MAT-A", "1", "10", "10");
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "1", "10", "10");
    stubBase(List.of(first, duplicate), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结算键重复");
  }

  @Test
  void blocksQuantityMismatch() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "3", "10", "30");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("数量");
  }

  @Test
  void blocksItemFromAnotherBatchScopeEvenWhenSettlementKeyMatches() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "2", "10", "20");
    finance.setPeriodMonth("2026-06");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("计价月份");
  }

  @Test
  void blocksNonCuSettlementWithNonZeroDifference() {
    PricePrepareItem oa = fixedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "10", "20");
    PricePrepareItem finance = fixedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "2", "9", "18");
    stubBase(List.of(oa), List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("非Cu结算行");
  }

  @Test
  void blocksLinkedNonCuVariableMismatch() {
    PricePrepareItem oa = linkedItem(11L, "PPR-OA", "SET-CU", "MAT-CU", "1", "120", "120", 501L);
    PricePrepareItem finance =
        linkedItem(21L, "PPR-FIN", "SET-CU", "MAT-CU", "1", "109", "109", 601L);
    stubBase(List.of(oa), List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(
                    501L,
                    "MAT-CU",
                    "120",
                    "[Cu]+[Zn]",
                    vars("Cu", "100", "Zn", "20"),
                    "OA_LOCKED")))
        .thenReturn(
            List.of(
                linked(
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
    PricePrepareItem oaA = linkedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "100", "100", 501L);
    PricePrepareItem oaB = linkedItem(12L, "PPR-OA", "SET-B", "MAT-B", "1", "101", "101", 502L);
    PricePrepareItem financeA = linkedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "1", "90", "90", 601L);
    PricePrepareItem financeB = linkedItem(22L, "PPR-FIN", "SET-B", "MAT-B", "1", "90", "90", 602L);
    stubBase(List.of(oaA, oaB), List.of(financeA, financeB));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(501L, "MAT-A", "100", "[Cu]", vars("Cu", "100"), "OA_LOCKED"),
                linked(502L, "MAT-B", "101", "[Cu]", vars("Cu", "101"), "OA_LOCKED")))
        .thenReturn(
            List.of(
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
    MakePartPriceCalcRow oaA =
        makeRow(
            701L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "102.039",
            "5",
            "6",
            "10",
            "55");
    MakePartPriceCalcRow oaB =
        makeRow(
            702L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-B",
            "SCRAP-B",
            "固定价",
            "联动价",
            "20",
            "102.039",
            "4",
            "10",
            "45");
    MakePartPriceCalcRow financeA =
        makeRow(
            801L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "90",
            "5",
            "5",
            "8",
            "55");
    MakePartPriceCalcRow financeB =
        makeRow(
            802L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-B",
            "SCRAP-B",
            "固定价",
            "联动价",
            "20",
            "90",
            "3",
            "8",
            "45");
    when(makePartRowMapper.selectById(701L)).thenReturn(oaA);
    when(makePartRowMapper.selectById(801L)).thenReturn(financeA);
    when(makePartRowMapper.selectList(any()))
        .thenReturn(List.of(oaA, oaB))
        .thenReturn(List.of(financeA, financeB));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                linked(901L, "RAW-A", "102.039", "[Cu]", vars("Cu", "102.039"), "OA_LOCKED"),
                linked(902L, "SCRAP-B", "102.039", "[Cu]", vars("Cu", "102.039"), "OA_LOCKED")))
        .thenReturn(
            List.of(
                linked(911L, "RAW-A", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE"),
                linked(912L, "SCRAP-B", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE")));

    var result = service.calculate(VERSION_ID);

    assertThat(result.adjustmentAmount()).isEqualByComparingTo("4.00000000");
    assertThat(result.settlementCount()).isEqualTo(1);
    assertThat(result.rawComponentCount()).isEqualTo(2);
    assertThat(result.items()).hasSize(3);
    QuoteCuMaterialDiffItemResult parent = result.items().get(0);
    assertThat(parent.getDetailLevel()).isEqualTo("SETTLEMENT");
    assertThat(parent.getContributesToAdjustment()).isEqualTo(1);
    assertThat(parent.getDiffAmount()).isEqualByComparingTo("4");
    assertThat(result.items().subList(1, 3))
        .allSatisfy(
            component -> {
              assertThat(component.getDetailLevel()).isEqualTo("RAW_COMPONENT");
              assertThat(component.getContributesToAdjustment()).isZero();
              assertThat(component.getParentSettlementKey()).isEqualTo("SET-MAKE");
              assertThat(component.getTraceJson())
                  .contains("qtyPerParent", "grossWeightG", "netWeightG");
            });
    assertThat(
            result.items().subList(1, 3).stream()
                .map(QuoteCuMaterialDiffItemResult::getDiffAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(parent.getDiffAmount());
  }

  @Test
  void blocksMakePartNetWeightMismatch() {
    PricePrepareItem oaItem = makeItem(11L, "PPR-OA", "6", "12", 701L);
    PricePrepareItem financeItem = makeItem(21L, "PPR-FIN", "5", "10", 801L);
    stubBase(List.of(oaItem), List.of(financeItem));
    MakePartPriceCalcRow oa =
        makeRow(
            701L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "102.039",
            "5",
            "6",
            "6",
            "55");
    MakePartPriceCalcRow finance =
        makeRow(
            801L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "90",
            "5",
            "5",
            "5",
            "54");
    when(makePartRowMapper.selectById(701L)).thenReturn(oa);
    when(makePartRowMapper.selectById(801L)).thenReturn(finance);
    when(makePartRowMapper.selectList(any())).thenReturn(List.of(oa)).thenReturn(List.of(finance));

    assertThatThrownBy(() -> service.calculate(VERSION_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("净重");
  }

  private void stubBase(List<PricePrepareItem> oaItems, List<PricePrepareItem> financeItems) {
    when(versionMapper.selectById(VERSION_ID)).thenReturn(version());
    when(batchMapper.selectOne(any()))
        .thenReturn(batch("PPR-OA", "OA_LOCKED", null))
        .thenReturn(batch("PPR-FIN", "FINANCE_QUOTE_BASE", "PPR-OA"));
    when(itemMapper.selectList(any())).thenReturn(oaItems).thenReturn(financeItems);
  }

  @Test
  void originalVariableNamesAreLocalAndCopperUnitsAreNormalized() throws Exception {
    var oaCu = linkedItem(11L, "PPR-OA", "SET-CU", "MAT-CU", "1", "100", "100", 501L);
    var financeCu = linkedItem(21L, "PPR-FIN", "SET-CU", "MAT-CU", "1", "90", "90", 601L);
    var oaZn = linkedItem(12L, "PPR-OA", "SET-ZN", "MAT-ZN", "1", "21.636", "21.636", 502L);
    var financeZn = linkedItem(22L, "PPR-FIN", "SET-ZN", "MAT-ZN", "1", "21.636", "21.636", 602L);
    stubBase(List.of(oaCu, oaZn), List.of(financeCu, financeZn));
    var cuBindings =
        Map.of(
            "factor_0", binding("AVG|1#CU", "CNY_TON"), "factor_1", binding("AVG|1#CU", "CNY_KG"));
    var znBindings = Map.of("factor_0", binding("AVG|1#ZN", "CNY_KG"));
    var a =
        originalLinked(
            501L,
            "MAT-CU",
            "100",
            "OA_LOCKED",
            cuBindings,
            vars("factor_0", "100000", "factor_1", "100"));
    var b =
        originalLinked(
            601L,
            "MAT-CU",
            "90",
            "FINANCE_QUOTE_BASE",
            cuBindings,
            vars("factor_0", "90000", "factor_1", "90"));
    var c =
        originalLinked(
            502L, "MAT-ZN", "21.636", "OA_LOCKED", znBindings, vars("factor_0", "21.636"));
    var d =
        originalLinked(
            602L, "MAT-ZN", "21.636", "FINANCE_QUOTE_BASE", znBindings, vars("factor_0", "21.636"));
    when(linkedCalcItemMapper.selectList(any())).thenReturn(List.of(a, c), List.of(b, d));
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo("10");
    assertThat(result.cuAffectedSettlementCount()).isOne();
    verify(factorQuoteBaseMappingMapper, never()).selectList(any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"AVG|1#ZN", "AVG|PCU"})
  void originalNonCopperChangesCannotBecomeCopperAdjustments(String identity) throws Exception {
    var oa = linkedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "101", "101", 501L);
    var finance = linkedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "1", "100", "100", 601L);
    stubBase(List.of(oa), List.of(finance));
    var bindings = Map.of("factor_0", binding(identity, "CNY_KG"));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(
            List.of(
                originalLinked(
                    501L, "MAT-A", "101", "OA_LOCKED", bindings, vars("factor_0", "101"))),
            List.of(
                originalLinked(
                    601L,
                    "MAT-A",
                    "100",
                    "FINANCE_QUOTE_BASE",
                    bindings,
                    vars("factor_0", "100"))));
    assertThatThrownBy(() -> service.calculate(VERSION_ID)).hasMessageContaining("非Cu变量");
  }

  @Test
  void originalPairCannotUseDifferentFinancialRevisions() throws Exception {
    var oa = linkedItem(11L, "PPR-OA", "SET-A", "MAT-A", "1", "100", "100", 501L);
    var finance = linkedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "1", "90", "90", 601L);
    stubBase(List.of(oa), List.of(finance));
    var bindings = Map.of("factor_0", binding("AVG|1#CU", "CNY_KG"));
    var a = originalLinked(501L, "MAT-A", "100", "OA_LOCKED", bindings, vars("factor_0", "100"));
    var b =
        originalLinked(601L, "MAT-A", "90", "FINANCE_QUOTE_BASE", bindings, vars("factor_0", "90"));
    var changed =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.readTree(b.getInputSnapshotJson());
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("context").path("financial"))
        .put("batchId", 2L);
    b.setInputSnapshotJson(objectMapper.writeValueAsString(changed));
    when(linkedCalcItemMapper.selectList(any())).thenReturn(List.of(a), List.of(b));
    assertThatThrownBy(() -> service.calculate(VERSION_ID)).hasMessageContaining("原稿财务输入版本");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "AVG|1#CU,90000,100000,1,1.4,0.8,1",
    "AVG|1#CU,90000,94999,1,1,0,1",
    "AVG|1#CU,100000,100000,1.4,1.4,0,1",
    "AVG|1#CU,130000,100000,2.6,1.4,-2.4,1",
    "AVG|黄金AU9999,90000,100000,2,2,0,0"
  })
  void nativeRangeSettlesOnlyTheActualMaterialDifference(
      String factor,
      String financeCu,
      String customerCu,
      String financePrice,
      String customerPrice,
      String delta,
      int affected)
      throws Exception {
    objectMapper.findAndRegisterModules();
    var oa =
        fixedItem(
            11L,
            "PPR-OA",
            "SET-RANGE",
            "MAT-RANGE",
            "2",
            customerPrice,
            new BigDecimal(customerPrice).multiply(new BigDecimal("2")).toPlainString());
    var finance =
        fixedItem(
            21L,
            "PPR-FIN",
            "SET-RANGE",
            "MAT-RANGE",
            "2",
            financePrice,
            new BigDecimal(financePrice).multiply(new BigDecimal("2")).toPlainString());
    oa.setResultRefType("RANGE_PRICE");
    finance.setResultRefType("RANGE_PRICE");
    oa.setResultRefId(701L);
    finance.setResultRefId(702L);
    oa.setInputSnapshotJson(rangeSnapshot(factor, financeCu, customerCu, customerPrice));
    finance.setInputSnapshotJson(rangeSnapshot(factor, financeCu, null, financePrice));
    stubBase(List.of(oa), List.of(finance));
    var version = version();
    version.setOaCuPrice(new BigDecimal(customerCu).movePointLeft(3));
    version.setFinanceCuPrice(new BigDecimal(financeCu).movePointLeft(3));
    when(versionMapper.selectById(VERSION_ID)).thenReturn(version);
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo(delta);
    assertThat(result.cuAffectedSettlementCount()).isEqualTo(affected);
    assertThat(result.settlementCount()).isOne();
    assertThat(result.items().getFirst().getTraceJson())
        .contains("oaRangeTrace", "financeRangeTrace", "RANGE_ORIGINAL_V1");
    verify(linkedCalcItemMapper, never()).selectList(any());
    verify(factorQuoteBaseMappingMapper, never()).selectList(any());
  }

  @Test
  void nativeRangeCannotSettleMixedFinancialVersionsOrMissingSnapshots() throws Exception {
    objectMapper.findAndRegisterModules();
    var validator = new OriginalRangeCalculationEvidence(objectMapper);
    String customer = rangeSnapshot("AVG|1#CU", "90000", "100000", "1.4");
    String finance = rangeSnapshot("AVG|1#CU", "90000", null, "1");
    var changed = objectMapper.readTree(finance);
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("context").path("financial"))
        .put("batchId", 999);
    assertThatThrownBy(
            () ->
                validator.validatePair(
                    "MAT-RANGE",
                    customer,
                    changed.toString(),
                    new BigDecimal("1.4"),
                    BigDecimal.ONE,
                    null,
                    null))
        .hasMessageContaining("财务输入版本不一致");
    assertThatThrownBy(
            () ->
                validator.validatePair(
                    "MAT-RANGE", customer, null, new BigDecimal("1.4"), BigDecimal.ONE, null, null))
        .hasMessageContaining("快照不可用");
    assertThatThrownBy(
            () ->
                validator.validatePair(
                    "MAT-RANGE",
                    customer,
                    finance,
                    new BigDecimal("1.5"),
                    BigDecimal.ONE,
                    null,
                    null))
        .hasMessageContaining("客户区间单价");
  }

  @Test
  void nativeLinkedManufacturingKeepsItsOwnSnapshotAndSettlesParentOnce() throws Exception {
    var bindings = Map.of("factor_0", binding("AVG|1#CU", "CNY_KG"));
    var oaEvidence =
        originalLinked(901L, "RAW-A", "100", "OA_LOCKED", bindings, vars("factor_0", "100"));
    var financeEvidence =
        originalLinked(911L, "RAW-A", "90", "FINANCE_QUOTE_BASE", bindings, vars("factor_0", "90"));
    var oa =
        makeRow(
            701L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "100",
            "5",
            "7.9",
            "7.9",
            "60");
    var finance =
        makeRow(
            801L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-A",
            "SCRAP-A",
            "联动价",
            "固定价",
            "90",
            "5",
            "7.1",
            "7.1",
            "60");
    oa.setRawInputSnapshotJson(withSelectionMetadata(oaEvidence.getInputSnapshotJson()));
    finance.setRawInputSnapshotJson(withSelectionMetadata(financeEvidence.getInputSnapshotJson()));
    stubMakePair(
        makeItem(11L, "PPR-OA", "7.9", "15.8", 701L),
        makeItem(21L, "PPR-FIN", "7.1", "14.2", 801L),
        List.of(oa),
        List.of(finance));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(oaEvidence), List.of(financeEvidence));
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo("1.6");
    assertThat(result.settlementCount()).isOne();
    assertThat(result.rawComponentCount()).isOne();
    assertThat(result.items().get(1).getContributesToAdjustment()).isZero();
    assertThat(result.items().get(1).getTraceJson()).contains("ORIGINAL_V1", "oaRawLinkedTrace");
    finance.setRawInputSnapshotJson(oaEvidence.getInputSnapshotJson());
    stubBase(
        List.of(makeItem(11L, "PPR-OA", "7.9", "15.8", 701L)),
        List.of(makeItem(21L, "PPR-FIN", "7.1", "14.2", 801L)));
    when(linkedCalcItemMapper.selectList(any()))
        .thenReturn(List.of(oaEvidence), List.of(financeEvidence));
    when(makePartRowMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    assertThatThrownBy(() -> service.calculate(VERSION_ID)).hasMessageContaining("制造件联动输入快照");
  }

  private String withSelectionMetadata(String snapshot) throws Exception {
    var decorated = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(snapshot);
    decorated.putObject("pricePriority").put("revision", "current-route");
    decorated.put("selectedPriceType", "LINKED");
    decorated.put("selectedPriceSourceKind", "PUBLIC");
    return objectMapper.writeValueAsString(decorated);
  }

  @Test
  void nativeRangeManufacturingUsesRawAndScrapSnapshots() throws Exception {
    objectMapper.findAndRegisterModules();
    var oa =
        makeRow(
            701L,
            "BATCH-OA",
            "OA_LOCKED",
            "MAT-RANGE",
            "MAT-RANGE",
            "区间价",
            "区间价",
            "1.4",
            "1.4",
            ".084",
            ".084",
            "60");
    var finance =
        makeRow(
            801L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "MAT-RANGE",
            "MAT-RANGE",
            "区间价",
            "区间价",
            "1",
            "1",
            ".06",
            ".06",
            "60");
    oa.setRawInputSnapshotJson(rangeSnapshot("AVG|1#CU", "90000", "100000", "1.4"));
    oa.setScrapInputSnapshotJson(oa.getRawInputSnapshotJson());
    finance.setRawInputSnapshotJson(rangeSnapshot("AVG|1#CU", "90000", null, "1"));
    finance.setScrapInputSnapshotJson(finance.getRawInputSnapshotJson());
    stubMakePair(
        makeItem(11L, "PPR-OA", ".084", ".168", 701L),
        makeItem(21L, "PPR-FIN", ".06", ".12", 801L),
        List.of(oa),
        List.of(finance));
    var version = version();
    version.setOaCuPrice(new BigDecimal("100"));
    version.setFinanceCuPrice(new BigDecimal("90"));
    when(versionMapper.selectById(VERSION_ID)).thenReturn(version);
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isEqualByComparingTo(".048");
    assertThat(result.cuAffectedSettlementCount()).isOne();
    assertThat(result.items().get(1).getTraceJson())
        .contains("oaRawRangeTrace", "oaScrapRangeTrace");
    verify(linkedCalcItemMapper, never()).selectList(any());
  }

  @Test
  void fractionalManufacturingQuantityUsesParentSettlementRounding() throws Exception {
    var oaItem = makeItem(11L, "PPR-OA", ".00000002", ".00000001", 701L);
    var financeItem = makeItem(21L, "PPR-FIN", ".00000002", ".00000001", 801L);
    oaItem.setQuantity(new BigDecimal(".5"));
    financeItem.setQuantity(new BigDecimal(".5"));
    var oaA =
        makeRow(
            701L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-A",
            "SCRAP",
            "固定价",
            "固定价",
            "1",
            "1",
            ".00000001",
            ".00000002",
            "60");
    var oaB =
        makeRow(
            702L,
            "BATCH-OA",
            "OA_LOCKED",
            "RAW-B",
            "SCRAP",
            "固定价",
            "固定价",
            "1",
            "1",
            ".00000001",
            ".00000002",
            "60");
    var financeA =
        makeRow(
            801L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-A",
            "SCRAP",
            "固定价",
            "固定价",
            "1",
            "1",
            ".00000001",
            ".00000002",
            "60");
    var financeB =
        makeRow(
            802L,
            "BATCH-FIN",
            "FINANCE_QUOTE_BASE",
            "RAW-B",
            "SCRAP",
            "固定价",
            "固定价",
            "1",
            "1",
            ".00000001",
            ".00000002",
            "60");
    stubMakePair(oaItem, financeItem, List.of(oaA, oaB), List.of(financeA, financeB));
    var result = service.calculate(VERSION_ID);
    assertThat(result.adjustmentAmount()).isZero();
    assertThat(result.items().getFirst().getFinanceAmount()).isEqualByComparingTo(".00000001");
    var trace = objectMapper.readTree(result.items().getFirst().getTraceJson());
    assertThat(trace.path("oaRoundingAdjustment").decimalValue())
        .isEqualByComparingTo("-.00000001");
    assertThat(trace.path("financeRoundingAdjustment").decimalValue())
        .isEqualByComparingTo("-.00000001");
  }

  @Test
  void linkedPairCannotSwitchSupplierEvenWhenFormulaAndCuMatch() {
    stubBase(
        List.of(linkedItem(11L, "PPR-OA", "SET-A", "MAT-A", "2", "100", "200", 501L)),
        List.of(linkedItem(21L, "PPR-FIN", "SET-A", "MAT-A", "2", "90", "180", 601L)));
    var oa = linked(501L, "MAT-A", "100", "[Cu]", vars("Cu", "100"), "OA_LOCKED");
    var finance = linked(601L, "MAT-A", "90", "[Cu]", vars("Cu", "90"), "FINANCE_QUOTE_BASE");
    oa.setSupplierCode("S1");
    finance.setSupplierCode("S2");
    when(linkedCalcItemMapper.selectList(any())).thenReturn(List.of(oa), List.of(finance));
    assertThatThrownBy(() -> service.calculate(VERSION_ID)).hasMessageContaining("供应商");
  }

  private void stubMakePair(
      PricePrepareItem oaItem,
      PricePrepareItem financeItem,
      List<MakePartPriceCalcRow> oa,
      List<MakePartPriceCalcRow> finance) {
    stubBase(List.of(oaItem), List.of(financeItem));
    when(makePartRowMapper.selectById(oaItem.getResultRefId())).thenReturn(oa.getFirst());
    when(makePartRowMapper.selectById(financeItem.getResultRefId())).thenReturn(finance.getFirst());
    when(makePartRowMapper.selectList(any())).thenReturn(oa, finance);
  }

  private String rangeSnapshot(String factor, String financeCu, String customerCu, String price)
      throws Exception {
    var scope =
        new com.sanhua.marketingcost.service.pricing.FactorPriceScope(
            "COMMERCIAL",
            "210",
            "2026-05",
            "BASE",
            com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.GENERAL);
    Map<String, com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue> factors =
        new LinkedHashMap<>();
    factors.put(
        "AVG|1#CU",
        new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue(
            1L, 2L, "AVG|1#CU", "Cu", new BigDecimal(financeCu), "CNY/TON", true, List.of()));
    factors.put(
        "AVG|黄金AU9999",
        new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue(
            3L, 4L, "AVG|黄金AU9999", "黄金", new BigDecimal("938.91"), "CNY/G", true, List.of()));
    var snapshot =
        new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot(
            scope,
            42L,
            1,
            "financial.xlsx",
            "hash",
            "content",
            factors,
            List.of("BASE"),
            "test",
            PRICE_AS_OF,
            null);
    var context =
        new com.sanhua.marketingcost.service.pricing.PriceFactorContext(
            "MAT-RANGE",
            new com.sanhua.marketingcost.service.pricing.PriceMonthSelection("2026-05", "2026-05"),
            snapshot,
            customerCu == null ? null : new BigDecimal(customerCu));
    var unit =
        factor.equals("AVG|1#CU")
            ? com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_TON
            : com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_G;
    var input = context.requiredPrice(factor, unit, true);
    var result =
        new OriginalRangeRuleCalculator.Result(
            input,
            new BigDecimal(price),
            new BigDecimal(price),
            new OriginalRangeWorkbookParser.Bounds(
                input, input.add(new BigDecimal("4999")), true, true),
            null,
            null);
    return objectMapper.writeValueAsString(
        new OriginalRangePriceRuntime.Calculation(
            "RANGE_ORIGINAL_V1",
            500L,
            1,
            "2026-05",
            40L,
            new com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey(
                "COMMERCIAL", "210", "MAT-RANGE", "S01", "Supplier", "SPEC", "只"),
            factor,
            unit,
            context,
            result,
            null));
  }

  private Map<String, String> binding(String key, String unit) {
    return Map.of("canonicalKey", key, "unit", unit);
  }

  private PriceLinkedCalcItem originalLinked(
      Long id,
      String code,
      String price,
      String source,
      Map<String, Map<String, String>> bindings,
      Map<String, BigDecimal> inputs)
      throws Exception {
    var row = linked(id, code, price, "[factor_0]", inputs, source);
    row.setCalculatorVersion("ORIGINAL_V1");
    row.setSourcePriceRecordId(900L);
    row.setInputSnapshotJson(
        objectMapper.writeValueAsString(
            Map.of(
                "calculatorVersion",
                "ORIGINAL_V1",
                "factorBindings",
                bindings,
                "inputs",
                inputs,
                "ruleId",
                900L,
                "ruleRevision",
                1,
                "ruleIdentity",
                code,
                "ruleMonth",
                "2026-05",
                "expression",
                "[factor_0]",
                "context",
                Map.of("financial", Map.of("batchId", 1L)))));
    return row;
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
    PricePrepareItem item = item(id, prepareNo, "SET-MAKE", "MAKE-1", "2", unitPrice, amount);
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

  private String trace(String formula, Map<String, BigDecimal> variables, String factorSource) {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("normalizedExpr", formula);
    trace.put("variables", variables);
    List<Map<String, Object>> details = new ArrayList<>();
    variables.forEach(
        (code, value) ->
            details.add(
                Map.of(
                    "code",
                    code,
                    "value",
                    value,
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
