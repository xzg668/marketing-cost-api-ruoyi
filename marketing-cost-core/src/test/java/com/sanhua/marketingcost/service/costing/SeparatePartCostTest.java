package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.costing.MonthlyProductCostTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class SeparatePartCostTest {
  static Ordinary ordinary(List<MaterialLine> lines) {
    return new Ordinary(lines, d("5"), d("2"), d("1"), d(".02"), d(".5"), d("1.05"), d(".1"), d(".15"), d(".05"), d("0"));
  }
  static MonthlyAdoptionPlan plan(boolean nested) {
    var a=request("A"); var b=request("B");
    return new MonthlyAdoptionPlan(Map.of(a,selection(1,PriceFact.normal(d("10"),SourceEvidence.ofJson("{\"a\":1}"),Set.of())),
        b,selection(2,PriceFact.normal(d("3"),SourceEvidence.ofJson("{\"b\":1}"),Set.of()))),
        List.of(new Use("root/A", a, d("2")), new Use(nested ? "root/A/B" : "root/B", b, d("1"))), Set.of());
  }
  static MaterialLine a(String rate) {
    return new MaterialLine("root/A", "A", d("2"), List.of(1L), d("20"), d("24"), null,
        ProductPartCostRuleTest.rule(1,"A",null,rate));
  }
  @Test void excludesFromOrdinaryFeesAndAppliesProductRatesOnceWithActualQuantity() {
    var result=MonthlyProductCost.calculate(plan(false), ordinary(List.of(a(".2"),
        new MaterialLine("root/B","B",d("1"),List.of(2L),d("3"),d("4"),null))),null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("8");
    // Ordinary: (8+2+1)*1.02/0.5*1.05*1.30=30.6306; A: 10*2*1.02/0.8*1.30=33.15.
    assertThat(result.totalCost()).isEqualByComparingTo("63.7806");
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("7.63");
    assertThat(result.finalQuoteAmount()).isEqualByComparingTo("71.4106");
  }
  @Test void bothParentAndChildPricedOccurrencesUseTheirOwnRule() {
    var b=new MaterialLine("root/A/B","B",d("1"),List.of(2L),d("3"),d("3"),null,
        ProductPartCostRuleTest.rule(2,"B",null,".4"));
    var input=ordinary(List.of(a(".2"),b));
    var result=MonthlyProductCost.calculate(plan(true),input,null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("5");
    assertThat(SeparatePartCost.amount(b,input,false)).isEqualByComparingTo("6.63");
    assertThat(result.totalCost()).isEqualByComparingTo("62.0568");
  }
  @Test void zeroManufacturingRateAndHighPrecisionUseUnroundedBase() {
    assertThat(SeparatePartCost.amount(d("20"),d(".02"),d("0"),d(".1"),d(".15"),d(".05"))).isEqualByComparingTo("26.52");
    assertThat(SeparatePartCost.amount(d(".00000049"),d("0"),d(".9"),d("0"),d("0"),d("0"))).isEqualByComparingTo(".000005");
  }
  @Test void changedRuleCannotReuseSharedNumbersAndCapturedRulesSurviveJson() throws Exception {
    var b=new MaterialLine("root/B","B",d("1"),List.of(2L),d("3"),d("3"),null);
    var first=MonthlyProductCost.resolve(plan(false),ordinary(List.of(a(".2"),b)),null);
    var changed=MonthlyProductCost.resolve(plan(false),ordinary(List.of(a(".3"),b)),null);
    var numbers=MonthlyProductCost.calculate(first.inputs());
    assertThatThrownBy(() -> changed.bind(numbers)).hasMessageContaining("inputs differ");
    var codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    var result=first.bind(numbers);
    assertThat(codec.decode(codec.encode(result),Result.class)).isEqualTo(result);
    var old=new ObjectMapper().readTree(codec.unwrap(codec.encode(b)));
    ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("separateRule");
    assertThat(codec.decode(codec.wrap(old.toString()),MaterialLine.class).separateRule()).isNull();
  }
  @Test void householdPricedPartUsesSpecialFormulaWithoutASecondHouseholdCharge() {
    var r=request("A");var plan=new MonthlyAdoptionPlan(Map.of(r,selection(1,household("10",".085"))),
        List.of(new Use("root/A",r,d("2"))),Set.of());
    var result=MonthlyProductCost.calculate(plan,ordinary(List.of(a(".2"))),null);
    assertThat(result.household()).isEmpty();
    assertThat(result.householdContribution()).isZero();
    assertThat(result.totalCost()).isEqualByComparingTo("55.4268");
  }
}
