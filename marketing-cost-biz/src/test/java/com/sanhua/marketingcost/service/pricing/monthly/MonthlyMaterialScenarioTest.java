package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyMaterialScenarioTest {
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper().findAndRegisterModules());
  final MonthlyPriceScenarios scenarios = MonthlyScenarioTestSupport.create(null);

  @Test void silverAndTinRepriceTogetherWithoutChangingTheSavedFinancialUnitPrice() {
    var request = request();
    var selected = inquiry("ag*0.002+sn*0.1+2", "69", Map.of("ag", "AVG|1#AG", "sn", "AVG|1#SN"),
        Map.of("ag", "14000", "sn", "390"));
    String evidence = selected.fact().evidence().json();
    var sample = new CustomerMaterialPrices(d("100000"), d("25000"), d("15000000"), d("400000"), null, null);
    var pair = scenarios.calculateMaterials(Map.of(request, selected), sample, Map.of()).get(1L);
    assertThat(pair.financialPrice()).isEqualByComparingTo("69");
    assertThat(pair.customerPrice()).isEqualByComparingTo("72");
    assertThat(pair.customerPrice().subtract(pair.financialPrice()).multiply(d("2.5"))).isEqualByComparingTo("7.5");
    assertThat(selected.fact().evidence().json()).isEqualTo(evidence);
    assertThat(selected.fact().price()).isEqualByComparingTo("69");
    var preview = scenarios.previewMaterials(Map.of(request, selected), sample, Map.of()).get(request);
    assertThat(preview.financialPrice()).isEqualByComparingTo(pair.financialPrice());
    assertThat(preview.customerPrice()).isEqualByComparingTo(pair.customerPrice());
  }

  @Test void missingCustomerMetalsKeepTheBaselineAndNegativeDifferencesArePreserved() {
    var selected = inquiry("ag*0.002+sn*0.1+2", "69", Map.of("ag", "AVG|1#AG", "sn", "AVG|1#SN"),
        Map.of("ag", "14000", "sn", "390"));
    for (var sample : List.of(new String[]{null, null, "69"}, new String[]{"13000000", null, "67"},
        new String[]{null, "380000", "68"}, new String[]{"13000000", "380000", "66"})) {
      var inputs = new CustomerMaterialPrices(d("100000"), null, d(sample[0]), d(sample[1]), null, null);
      var pair = scenarios.calculateMaterials(Map.of(request(), selected), inputs, Map.of()).get(1L);
      assertThat(pair.customerPrice()).isEqualByComparingTo(sample[2]);
      assertThat(pair.financialPrice()).isEqualByComparingTo("69");
    }
  }

  @Test void customerContextOnlyOverridesSupportedFactorsAndKeepsFinancialEvidence() {
    var baseline = financial();
    var inputs = new CustomerMaterialPrices(d("100000"), d("24000"), d("16000000"), d("400000"), null, null);
    var customer = baseline.withCustomerMaterials(inputs);
    assertThat(customer.requiredPrice("AVG|AG", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("16000");
    assertThat(customer.requiredPrice("AVG|1#AG", FactorPriceUnit.CNY_G, true)).isEqualByComparingTo("16");
    assertThat(customer.requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("100");
    assertThat(customer.requiredPrice("AVG|PCU", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("115.979");
    assertThat(customer.financial()).isSameAs(baseline.financial());
    assertThat(customer.financialOnly()).isEqualTo(baseline);
    assertThat(customer.evidenceForFactors(Set.of("AVG|1#AG")).customerMaterialPrices()).isEqualTo(inputs);
  }

  @Test void quotationValuesUseDeclaredTonUnitsAndMissingValuesRemainFinancial() throws Exception {
    var form = new com.sanhua.marketingcost.entity.OaForm();
    form.setSilverPrice(d("15000000")); form.setSus304Price(d("25000"));
    form.setCopperPrice(BigDecimal.ZERO); form.setZincPrice(d("-1"));
    var inputs = CustomerMaterialPrices.fromQuotation(form);
    assertThat(inputs).isEqualTo(new CustomerMaterialPrices(null, null, d("15000000"), null, d("25000"), null));
    var customer = boardFinancial().withCustomerMaterials(inputs);
    assertThat(customer.requiredPrice("AVG|1#AG", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("15000");
    assertThat(customer.requiredPrice("AVG|1#SN", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("390");
    assertThat(customer.requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("90");
    assertThat(customer.requiredPrice("AVG|316L/2B", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("32.5");
    var json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.readValue(json.writeValueAsString(customer), PriceFactorContext.class)).isEqualTo(customer);
    assertThat(customer.withCustomerCopperPerTon(d("110000")).requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("110");
  }

  @Test void originalLinkedBoardFormulaRepricesAllSixFactorsFromTheSameSavedSource() {
    var context = boardFinancial();
    var identity = new MaterialPriceRuleKey("COMMERCIAL", "220", "BOARD", "SAVED-SUPPLIER", "原供应商", "SUS304", "件");
    String expression = "cu*0.1+zn*0.2+ag*0.002+sn*0.1+s304*0.3+s316*0.4+2";
    var factors = new LinkedHashMap<String, OriginalLinkedWorkbookParser.FactorInput>();
    Map.of("cu", "AVG|1#CU", "zn", "AVG|1#ZN", "ag", "AVG|1#AG", "sn", "AVG|1#SN",
        "s304", "AVG|304/2B/1219", "s316", "AVG|316L/2B").forEach((name,key) -> factors.put(name,
        new OriginalLinkedWorkbookParser.FactorInput(key, new Address("基价", "D3"), FactorPriceUnit.CNY_KG,
            "CNY/KG", key, "SAME_CANONICAL_FACTOR")));
    var rule = new OriginalLinkedWorkbookParser.Rule(identity, "板换物料", "原稿", 8, "H8", expression,
        expression, "0.000000", false, new CompiledFormula(expression, List.of(), Map.of()), factors, BigDecimal.ZERO);
    var calculator = new OriginalLinkedRuleCalculator();
    var base = calculator.calculate(rule, context);
    var publication = new FinanceEffectivePrice();
    publication.setCalculationJson(json(new FinanceEffectivePriceService.Calculation(context, base, null, rule)));
    var selection = source("LINKED", base.priceExcludingTax(), Map.of("selectedPublication", publication,
        "calculationJson", json(Map.of("context", context, "inputs", base.inputs(), "factorBindings", factors))));
    var prices = new CustomerMaterialPrices(d("100000"), d("24000"), d("15000000"), d("400000"), d("25000"), d("35000"));
    var pair = scenarios.calculateMaterials(Map.of(boardRequest(), selection), prices, Map.of()).get(1L);
    assertThat(pair.financialPrice()).isEqualByComparingTo("101.4");
    assertThat(pair.customerPrice()).isEqualByComparingTo("108.3");
    assertThat(pair.customerPrice().subtract(pair.financialPrice()).multiply(d("2.5"))).isEqualByComparingTo("17.25");
    assertThat(new MonthlyMetalExposure(codec).read(Map.of(boardRequest(), selection))).containsEntry(1L, true);
    assertThat(context.financial().required("AVG|304/2B/1219").price()).isEqualByComparingTo("20");
    assertThat(rule.identity().supplierCode()).isEqualTo("SAVED-SUPPLIER");
  }

  @Test void originalRangesReselectBandsForSilverTinAndEachStainlessGrade() {
    for (String key : List.of("AVG|1#AG", "AVG|1#SN", "AVG|304/2B/1219", "AVG|316L/2B")) {
      var context = boardFinancial();
      BigDecimal baseline = context.requiredPrice(key, FactorPriceUnit.CNY_KG, true);
      var identity = new MaterialPriceRuleKey("COMMERCIAL", "220", "BOARD", "SAVED-SUPPLIER", "原供应商", "SPEC", "件");
      var sourceCell = new OriginalRangeWorkbookParser.CellSource(new Address("矩阵", "H8"), "10", "NUMERIC", null, "0.000000");
      var low = new OriginalRangeWorkbookParser.Band(new OriginalRangeWorkbookParser.Bounds(BigDecimal.ZERO, baseline, true, true),
          sourceCell, sourceCell.address(), sourceCell, new CompiledFormula("10", List.of(), Map.of()), d("10"), null);
      var high = new OriginalRangeWorkbookParser.Band(new OriginalRangeWorkbookParser.Bounds(baseline, baseline.multiply(d("2")), false, true),
          sourceCell, new Address("矩阵", "I8"), sourceCell, new CompiledFormula("14", List.of(), Map.of()), d("14"), null);
      var rule = new OriginalRangeWorkbookParser.Rule(identity, "区间物料", "原稿", 8, sourceCell, false, key,
          FactorPriceUnit.CNY_KG, "矩阵", 8, "SPEC", "SAVED-SUPPLIER", "EXACT_SOURCE_IDENTITY", List.of(low, high), List.of());
      var row = new PriceRangeFactorRule();
      row.setId(1L); row.setBusinessUnitType("COMMERCIAL"); row.setOrgCode("220"); row.setMaterialCode("BOARD");
      row.setSupplierCode("SAVED-SUPPLIER"); row.setSupplierName("原供应商"); row.setSpecModel("SPEC"); row.setPriceUnit("件");
      row.setRuleIdentityKey(identity.identityKey()); row.setFactorCode(key); row.setFactorUnit(FactorPriceUnit.CNY_KG.name());
      row.setSourceInputSnapshotJson(new OriginalRangeRuleSnapshot(new ObjectMapper().findAndRegisterModules()).write(List.of(rule), "saved"));
      var result = new OriginalRangeRuleCalculator().calculate(rule, context);
      var calculation = new OriginalRangePriceRuntime.Calculation("RANGE_ORIGINAL_V1", 1L, 1, "2026-11", 1L,
          identity, key, FactorPriceUnit.CNY_KG, context, result, 1L);
      var selected = source("RANGE_FACTOR", d("10"), Map.of("selected", row, "calculationJson", json(calculation)));
      var higher = new CustomerMaterialPrices(null, null, d("15000000"), d("400000"), d("25000"), d("35000"));
      var pair = scenarios.calculateMaterials(Map.of(boardRequest(), selected), higher, Map.of()).get(1L);
      assertThat(pair.financialPrice()).as(key).isEqualByComparingTo("10");
      assertThat(pair.customerPrice()).as(key).isEqualByComparingTo("14");
      assertThat(scenarios.calculateMaterials(Map.of(boardRequest(), selected), CustomerMaterialPrices.copperAndZinc(null, null), Map.of())
          .get(1L).customerPrice()).as("missing " + key).isEqualByComparingTo("10");
    }
  }

  private Selection source(String kind, BigDecimal price, Object source) {
    return new Selection(1L, new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any()),
        PriceFact.normal(price, SourceEvidence.ofJson(json(Map.of("snapshotSchema", 1, "sourceKind", kind, "source", source))), Set.of()));
  }

  private MonthlyPriceRequest boardRequest() {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11), "COMMERCIAL", "BOARD",
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "220", NonCuConditions.empty(), BigDecimal.ONE);
  }

  private PriceFactorContext boardFinancial() {
    var factors = new LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
    Map.of("AVG|1#CU", "90", "AVG|1#ZN", "22", "AVG|1#AG", "14000", "AVG|1#SN", "390",
        "AVG|304/2B/1219", "20", "AVG|316L/2B", "32.5").forEach((key, price) -> factors.put(key,
        new FactorPriceSnapshot.FactorValue(1L, 1L, key, key, d(price), "CNY/KG", true, List.of())));
    var snapshot = new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL", "220", "2026-11", "BOARD",
        FactorPriceScope.Condition.NON_TENDER), 1L, 1, "board.xlsx", "hash", "hash", factors,
        List.of("BOARD"), "test", java.time.LocalDateTime.of(2026,11,1,0,0), null);
    return new PriceFactorContext("BOARD", new PriceMonthSelection("2026-11", "2026-11"), snapshot, null)
        .withBoardInputs(new BoardPriceInputs(null, null, false));
  }

  private String json(Object value) { return codec.unwrap(codec.encode(value)); }

  @Test void oldContextWithoutNewCustomerFieldsStillRestores() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var base = financial();
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(base.withCustomerMetalsPerTon(d("100000"), d("24000")));
    tree.remove("customerMaterialPrices");
    var restored = json.treeToValue(tree, PriceFactorContext.class);
    assertThat(restored.requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("100");
    assertThat(restored.requiredPrice("AVG|1#ZN", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("24");
    assertThat(restored.requiredPrice("AVG|1#AG", FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("15772.86");
  }

  private Selection inquiry(String expression, String price, Map<String,String> factors, Map<String,String> inputs) {
    var bases = new LinkedHashMap<String,Object>();
    factors.forEach((name,key) -> bases.put(key, Map.of("unit", "CNY/KG", "price", inputs.get(name))));
    var calculation = Map.of("priceType", "联动价", "expression", expression, "inputs", inputs,
        "factors", factors, "financeFactors", bases);
    var evidence = SourceEvidence.ofJson(codec.unwrap(codec.encode(Map.of("snapshotSchema", 1, "sourceKind", "INQUIRY",
        "source", Map.of("selected", Map.of("price", price, "calculation", calculation))))));
    return new Selection(1L, new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()),
        PriceFact.normal(d(price), evidence, Set.of()));
  }

  private MonthlyPriceRequest request() {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11), "COMMERCIAL", "AG-SN",
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }
  private PriceFactorContext financial() {
    var factors = new LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
    Map.of("AVG|1#CU", "90", "AVG|1#ZN", "21.636", "AVG|1#AG", "15772.86", "AVG|PCU", "115.979")
        .forEach((key, price) -> factors.put(key, new FactorPriceSnapshot.FactorValue(1L, 1L, key, key,
            d(price), "CNY/KG", true, List.of())));
    var snapshot = new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL", "210", "2026-11", "DEFAULT",
        FactorPriceScope.Condition.GENERAL), 1L, 1, "base.xlsx", "hash", "hash", factors,
        List.of("DEFAULT"), "test", java.time.LocalDateTime.of(2026,11,1,0,0), null);
    return new PriceFactorContext("AG-SN", new PriceMonthSelection("2026-11", "2026-11"), snapshot, null);
  }
  private static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
}
