package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyZincScenarioTest {
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper().findAndRegisterModules());
  final MonthlyPriceScenarios scenarios = MonthlyScenarioTestSupport.create(null);

  @Test void inquiryUsesBothQuotationMetalsButNeverChangesThePublishedPrice() {
    var request = request();
    var selected = inquiry("cu*0.1+zn*0.2+2", "15.4", true);
    var before = selected.fact().evidence();
    for (var sample : List.of(new String[]{"100000","24000","16.8"}, new String[]{"80000","20000","14"},
        new String[]{"90000","22000","15.4"}, new String[]{"100000",null,"16.4"}, new String[]{null,"24000","15.8"},
        new String[]{null,null,"15.4"})) {
      var pair = scenarios.calculate(Map.of(request, selected), d(sample[0]), d(sample[1]), Map.of()).get(1L);
      assertThat(pair.financialPrice()).isEqualByComparingTo("15.4");
      assertThat(pair.customerPrice()).isEqualByComparingTo(sample[2]);
      assertThat(pair.customerEvidenceJson()).contains("customerZincPerTon", CustomerMaterialPrices.CALCULATION_VERSION);
      assertThat(selected.fact().evidence()).isEqualTo(before);
      assertThat(selected.fact().price()).isEqualByComparingTo("15.4");
    }
  }

  @Test void zincOnlyFormulaChangesAndUnusedQuotationMetalsDoNotCreateDifferences() {
    var request = request();
    var zinc = inquiry("zn*0.2+2", "6.4", false);
    assertThat(scenarios.calculate(Map.of(request, zinc), d("100000"), d("24000"), Map.of()).get(1L).customerPrice())
        .isEqualByComparingTo("6.8");
    assertThat(scenarios.calculate(Map.of(request, zinc), d("100000"), null, Map.of()).get(1L).customerPrice())
        .isEqualByComparingTo("6.4");
    var fixed = new Selection(1L, zinc.applicability(), PriceFact.normal(d("6.4"), SourceEvidence.ofJson(json(Map.of(
        "snapshotSchema",1,"sourceKind","PURCHASE_FIXED","source",Map.of("selected",Map.of("fixedPrice","6.4"))))), Set.of()));
    assertThat(scenarios.calculate(Map.of(request, fixed), d("100000"), d("24000"), Map.of()).get(1L).customerPrice())
        .isEqualByComparingTo("6.4");
    assertThat(new MonthlyMetalExposure(codec).read(Map.of(request,zinc))).containsEntry(1L,true);
  }

  @Test void settlementFormulaUsesQuotedZincAndBlankZincKeepsTheSavedBase() {
    var rule = new com.sanhua.marketingcost.service.settlement.SettlementPriceRecord();
    rule.setBusinessUnitType("COMMERCIAL"); rule.setMaterialCode("SETTLE-ZN");
    rule.setPriceType(com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType.SETTLE_LINKED);
    rule.setGivenPrice(d("4.841")); rule.setPlannedPrice(d("10")); rule.setMarkupRatio(d("1.05"));
    rule.setBudgetCu(d("80")); rule.setBudgetZn(d("20")); rule.setTaxDivisor(d("1.13"));
    rule.setCopperWeightG(d("2")); rule.setZincWeightG(d("3")); rule.setWeightsConfirmed(true);
    rule.setExpression(com.sanhua.marketingcost.service.settlement.SettlementFormula.EXPRESSION);
    rule.setFormulaVersion(com.sanhua.marketingcost.service.settlement.SettlementFormula.VERSION);
    var base = new com.sanhua.marketingcost.service.settlement.SettlementBasePrice(1L,"COMMERCIAL","2026-11",1,true,
        d("100"),d("30"),"CNY/KG","BEFORE_FORMULA_TAX","FINANCE_PUBLICATION","hash",null,"test",java.time.LocalDateTime.now());
    var selection = new Selection(1L,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
        PriceFact.normal(d("10.565044247788"),SourceEvidence.ofJson(json(Map.of("snapshotSchema",1,"sourceKind","SETTLE_LINKED",
            "source",Map.of("rule",rule,"base",base)))),Set.of()));
    var both = scenarios.calculate(Map.of(request(),selection),d("80000"),d("20000"),Map.of()).get(1L);
    assertThat(both.financialPrice()).isEqualByComparingTo("10.565044247788");
    assertThat(both.customerPrice()).isEqualByComparingTo("10.5");
    assertThat(scenarios.calculate(Map.of(request(),selection),d("80000"),null,Map.of()).get(1L).customerPrice())
        .isEqualByComparingTo("10.527876106195");
    assertThat(scenarios.calculate(Map.of(request(),selection),null,d("20000"),Map.of()).get(1L).customerPrice())
        .isEqualByComparingTo("10.537168141593");
  }

  private Selection inquiry(String expression, String price, boolean copper) {
    var factors = new LinkedHashMap<String,String>(); factors.put("zn",PriceFactorContext.ZINC);
    var inputs = new LinkedHashMap<String,String>(); inputs.put("zn","22");
    var bases = new LinkedHashMap<String,Object>(); bases.put(PriceFactorContext.ZINC,Map.of("unit","CNY/KG","price","22"));
    if(copper) { factors.put("cu",PriceFactorContext.COPPER); inputs.put("cu","90");
      bases.put(PriceFactorContext.COPPER,Map.of("unit","CNY/KG","price","90")); }
    var calc=Map.of("priceType","联动价","expression",expression,"inputs",inputs,"factors",factors,"financeFactors",bases);
    var evidence=SourceEvidence.ofJson(json(Map.of("snapshotSchema",1,"sourceKind","INQUIRY",
        "source",Map.of("selected",Map.of("price",price,"calculation",calc)))));
    return new Selection(1L,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
        PriceFact.normal(d(price),evidence,Set.of()));
  }
  private MonthlyPriceRequest request() { return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11),
      "COMMERCIAL","ZN-QUOTE",null,"件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE); }
  private String json(Object value) { return codec.unwrap(codec.encode(value)); }
  private static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
}
