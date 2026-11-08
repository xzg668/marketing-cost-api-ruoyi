package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class MonthlyProductCostTest {
  static BigDecimal d(String value) { return new BigDecimal(value); }
  static MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),
        "210",NonCuConditions.empty(),BigDecimal.ONE);
  }
  static Selection selection(long id, PriceFact fact) {
    return new Selection(id,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any())),fact);
  }
  static PriceFact household(String price, String rate) {
    return PriceFact.household(d(price),d(rate),SourceEvidence.ofJson("{\"snapshotSchema\":1,\"sourceKind\":\"HOUSEHOLD_INQUIRY\"}"));
  }
  static EffectiveTechnicalDataInput technical(List<BigDecimal> amounts) {
    var fees=new EffectiveTechnicalDataInput.ProductFees(null,amounts.get(3),amounts.get(0),amounts.get(1),"CNY",amounts.get(2),2);
    return new EffectiveTechnicalDataInput(1L,7L,1,"2026-10",EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,"fixture",LocalDateTime.of(2026,10,24,0,0),
        false,false,false,null,null,null,List.of(),List.of(),List.of(),fees,null);
  }
  static Stream<Arguments> goldens() throws Exception {
    try(var stream=MonthlyProductCostTest.class.getResourceAsStream("/monthly-cost/rational-goldens.json")) {
      JsonNode cases=new ObjectMapper().readTree(stream);
      return StreamSupport.stream(cases.spliterator(),false).map(row->Arguments.of(row.path("name").asText(),row)).toList().stream();
    }
  }
  static Ordinary ordinary(JsonNode row,List<MaterialLine> materials) {
    return new Ordinary(materials,d(row.path("material").asText()),d(row.path("direct").asText()),d(row.path("indirect").asText()),
        d(row.path("lossRate").asText()),d(row.path("manufactureRate").asText()),d(row.path("coefficient").asText()),
        d(row.path("managementRate").asText()),d(row.path("salesRate").asText()),d(row.path("financeRate").asText()),d(row.path("other").asText()));
  }
  @ParameterizedTest(name="{0}") @MethodSource("goldens")
  void independentlyComputedRationalGoldens(String name,JsonNode row) {
    var request=request("208200507");var selected=selection(1,household(row.path("P").asText(),row.path("H").asText()));
    var uses=new ArrayList<Use>();int i=0;
    for(var q:row.path("quantities"))uses.add(new Use("root/H"+(++i),request,d(q.asText())));
    // A separate ordinary Cu settlement exercises the positive/negative/zero adjustment identity.
    var normal=request("CU");var normalPrice=selection(2,PriceFact.normal(BigDecimal.ZERO,SourceEvidence.ofJson("{\"fixture\":\"normal\"}"),Set.of()));
    uses.add(new Use("root/CU",normal,BigDecimal.ONE));
    var selections=new HashMap<MonthlyPriceRequest,Selection>();if (!row.path("quantities").isEmpty()) selections.put(request,selected);selections.put(normal,normalPrice);
    var materials=List.of(new MaterialLine("root/CU","CU",BigDecimal.ONE,List.of(2L),BigDecimal.ZERO,d(row.path("cu").asText()),null));
    var fees=new ArrayList<BigDecimal>();row.path("fees").forEach(value->fees.add(value.isNull()?null:d(value.asText())));
    var result=MonthlyProductCost.calculate(new MonthlyAdoptionPlan(selections,uses,Set.of()),ordinary(row,materials),technical(fees));
    var e=row.path("expected");
    var actual=Map.ofEntries(Map.entry("loss",result.ordinary().loss()),Map.entry("F",result.ordinary().manufactureCost()),
        Map.entry("manufactureFee",result.ordinary().manufactureFee()),Map.entry("C",result.ordinary().adjustedManufactureCost()),
        Map.entry("G",result.ordinary().management()),Map.entry("S",result.ordinary().sales()),Map.entry("J",result.ordinary().finance()),
        Map.entry("Ai",result.householdSubtotal()),Map.entry("AH",result.householdContribution()),Map.entry("E",result.productFeeTotal()),
        Map.entry("total1",result.totalCost()),Map.entry("total2",result.finalQuoteAmount()));
    actual.forEach((key,value)->assertThat(value).as(name+" "+key).isEqualByComparingTo(e.path(key).asText()));
    assertThat(result.fees()).hasSize(4);
    for(i=0;i<4;i++)assertThat(result.fees().get(i).amount()).isEqualTo(fees.get(i));
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo(row.path("cu").asText());
  }

  static Ordinary zero(List<MaterialLine> lines) {
    return new Ordinary(lines,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,d("1.05"),d(".1"),d(".25"),d(".02"),BigDecimal.ZERO);
  }

  @Test void sharedNumbersIgnorePrivatePreparationOrderButKeepEveryOccurrenceAndOwnOutputOrder() {
    var r=request("B");var selected=selection(1,PriceFact.normal(d("3"),SourceEvidence.ofJson("{\"fixture\":1}"),Set.of()));
    var plan=new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new Use("root/A",r,d("1")),new Use("root/B",r,d("2"))),Set.of());
    var first=new MaterialLine("root/A","B",d("1"),List.of(1L),d("3"),d("4"),null);
    var second=new MaterialLine("root/B","B",d("2"),List.of(1L),d("6"),d("8"),null);
    var a=MonthlyProductCost.resolve(plan,zero(List.of(first,second)),null);
    var b=MonthlyProductCost.resolve(plan,zero(List.of(second,first)),null);
    var result=b.bind(MonthlyProductCost.calculate(a.inputs()));
    assertThat(result.ordinaryInputs().materials()).containsExactly(second,first);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("9");
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("3");
    assertThat(result.effectiveUses()).containsExactlyElementsOf(plan.uses());
  }
  @ParameterizedTest @CsvSource({"20.463109690363685593,4.702675","18.9343,0","18.426335398230088496,-1.562518"})
  void householdDifferenceUsesBothExpenseConversionsAndGroupedActualQuantityWithoutAnotherCoefficient(String customer,String expected) {
    var request=request("208200575");
    var plan=new MonthlyAdoptionPlan(Map.of(request,selection(1,household("18.9343",".085"))),
        List.of(new Use("root/A",request,d("1.25")),new Use("root/B",request,d("1.25"))),Set.of());
    var result=MonthlyProductCost.calculate(plan,zero(List.of()),null,Map.of(1L,d(customer)));
    assertThat(result.household()).hasSize(1);assertThat(result.household().getFirst().quantity()).isEqualByComparingTo("2.5");
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo(expected);
    assertThat(result.finalQuoteAmount()).isEqualByComparingTo(result.totalCost().add(d(expected)));
    var baseline=MonthlyProductCost.calculate(plan,zero(List.of()),null);
    assertThat(result.totalCost()).isEqualByComparingTo(baseline.totalCost());
    assertThat(result.householdSubtotal()).isEqualByComparingTo(baseline.householdSubtotal());
  }

  @Test void sharedHouseholdNumbersKeepExactCustomerPriceAndEachConsumersOwnPaths() {
    var request=request("H");var selected=selection(1,household("18.9343",".085"));
    var first=new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use("root/A",request,d("2.5"))),Set.of());
    var second=new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use("root/B",request,d("1.25")),new Use("root/C",request,d("1.25"))),Set.of());
    var price=d("20.463109690363685593");
    var a=MonthlyProductCost.resolve(first,zero(List.of()),null,Map.of(1L,price));
    var b=MonthlyProductCost.resolve(second,zero(List.of()),null,Map.of(1L,price));
    var shared=MonthlyProductCost.calculate(a.inputs());var result=b.bind(shared);
    assertThat(result.household().getFirst().paths()).containsExactly("root/B","root/C");
    assertThat(result.household().getFirst().effectiveCustomerPrice()).isEqualByComparingTo(price);
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("4.702675");
    assertThat(result.household().getFirst().effectiveCustomerAmount().subtract(result.household().getFirst().amount()))
        .isEqualByComparingTo(result.cuMaterialAdjustment());
    var changed=MonthlyProductCost.resolve(second,zero(List.of()),null,Map.of(1L,price.add(d(".000000000000000001"))));
    assertThatThrownBy(()->changed.bind(shared)).hasMessageContaining("inputs differ");
    assertThatThrownBy(()->MonthlyProductCost.resolve(second,zero(List.of()),null,Map.of(1L,d("-1"))))
        .hasMessageContaining("Invalid household customer price");
  }

  @Test void householdAndSuppressedPathsCannotEnterOrdinaryBasesButOtherBranchesCan() {
    var h=request("H");var b=request("B");var hPrice=selection(1,household("20.9853",".085"));
    var bPrice=selection(2,PriceFact.normal(d("3"),SourceEvidence.ofJson("{\"source\":\"fixed\"}"),Set.of()));
    var plan=new MonthlyAdoptionPlan(Map.of(h,hPrice,b,bPrice),List.of(new Use("root/A",h,BigDecimal.ONE),new Use("root/B",b,BigDecimal.ONE)),Set.of("root/A/B"));
    var outside=new MaterialLine("root/B","B",BigDecimal.ONE,List.of(2L),d("3"),d("3"),null);
    assertThat(MonthlyProductCost.calculate(plan,zero(List.of(outside)),null).financeMaterialCost()).isEqualByComparingTo("3");
    var covered=new MaterialLine("root/A/B","B",BigDecimal.ONE,List.of(2L),d("3"),d("3"),null);
    assertThatThrownBy(()->MonthlyProductCost.calculate(plan,zero(List.of(outside,covered)),null)).hasMessageContaining("Household scope");
  }
  @Test void explanationRowsDoNotDuplicateCopperAndNestedContributingRowsAreRejected() {
    var r=request("B");var selected=selection(1,PriceFact.normal(d("10"),SourceEvidence.ofJson("{\"fixture\":1}"),Set.of()));
    var plan=new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new Use("root/MAKE/B",r,BigDecimal.ONE)),Set.of());
    var settlement=new MaterialLine("root/MAKE","MAKE",BigDecimal.ONE,List.of(1L),d("20"),d("12"),null);
    var explanation=new MaterialLine("root/MAKE/B","B",BigDecimal.ONE,List.of(1L),d("10"),d("2"),"root/MAKE");
    var result=MonthlyProductCost.calculate(plan,zero(List.of(settlement,explanation)),null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("20");assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("-8");
    assertThatThrownBy(()->MonthlyProductCost.calculate(plan,zero(List.of(settlement,new MaterialLine(explanation.path(),"B",BigDecimal.ONE,List.of(1L),d("10"),d("2"),null))),null)).hasMessageContaining("twice");
  }
  @Test void directlyPricedProcessingFeeAndChildMaterialBothContributeOnce() {
    var fee = request("FEE"); var material = request("MAT");
    var evidence = SourceEvidence.ofJson("{\"source\":\"fixed\"}");
    var plan = new MonthlyAdoptionPlan(Map.of(
        fee, selection(1, PriceFact.normal(d("2"), evidence, Set.of())),
        material, selection(2, PriceFact.normal(d("3"), evidence, Set.of()))),
        List.of(new Use("root/FEE", fee, d("2.5")), new Use("root/FEE/MAT", material, d("7.5"))), Set.of());
    var parent = new MaterialLine("root/FEE", "FEE", d("2.5"), List.of(1L), d("5"), d("6"), null);
    var child = new MaterialLine("root/FEE/MAT", "MAT", d("7.5"), List.of(2L), d("22.5"), d("30"), null);
    var result = MonthlyProductCost.calculate(plan, zero(List.of(parent, child)), null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("27.5");
    assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("8.5");
    var wrongFee = new MaterialLine(parent.path(), "FEE", d("3"), List.of(1L), d("5"), d("6"), null);
    assertThatThrownBy(() -> MonthlyProductCost.calculate(plan, zero(List.of(wrongFee, child)), null))
        .hasMessageContaining("Direct ordinary settlement");
  }
  @Test void emptyFeesRemainNullAndFinalProfileAmountsAreNeitherUpliftedNorDistributed() {
    var plan=new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of());
    var blank=MonthlyProductCost.calculate(plan,zero(List.of()),null);
    assertThat(blank.fees()).allMatch(fee->fee.amount()==null&&fee.state()==ProductFeeContract.State.MISSING);
    var amounts=Arrays.asList(d("1"),null,d("999999999900.999999"),BigDecimal.ZERO);
    var priced=MonthlyProductCost.calculate(plan,zero(List.of()),technical(amounts));
    assertThat(priced.totalCost()).isEqualByComparingTo("999999999901.999999");
    assertThat(priced.fees().get(3).state()).isEqualTo(ProductFeeContract.State.EXPLICIT_NONE);
    assertThat(priced.fees().get(1).amount()).isNull();
  }
  @Test void oldFeeEncodingRetainsItsStateAndDraftInputCannotBecomeEffectiveFees() {
    var input=technical(Arrays.asList(d("1"),null,null,null));
    var fees=new EffectiveTechnicalDataInput.ProductFees(false,null,d("1"),null,"CNY",null,null);
    var legacy=new EffectiveTechnicalDataInput(1L,7L,1,"2026-10",input.sourceType(),"legacy",input.retrievedAt(),false,false,false,null,null,null,List.of(),List.of(),List.of(),fees,null);
    var result=MonthlyProductCost.calculate(new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of()),zero(List.of()),legacy);
    assertThat(result.fees().getFirst().state()).isEqualTo(ProductFeeContract.State.LEGACY);
    var draft=new EffectiveTechnicalDataInput(1L,7L,1,"2026-10","DRAFT","draft",input.retrievedAt(),false,false,false,null,null,null,List.of(),List.of(),List.of(),fees,null);
    assertThatThrownBy(()->MonthlyProductCost.calculate(new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of()),zero(List.of()),draft)).hasMessageContaining("effective");
  }
  @Test void differentHouseholdIdentitiesApplyCoefficientAfterTheirLineSum() {
    var a=request("A");var b=request("B");
    var plan=new MonthlyAdoptionPlan(Map.of(a,selection(1,household(".000005","0")),b,selection(2,household(".000005","0"))),
        List.of(new Use("root/A",a,d("1")),new Use("root/B",b,d("1"))),Set.of());
    var result=MonthlyProductCost.calculate(plan,zero(List.of()),null);
    assertThat(result.householdSubtotal()).isEqualByComparingTo(".000012");
    assertThat(result.householdContribution()).isEqualByComparingTo(".000013");
  }
  @Test void byproductCreditsKeepTheirSignedQuantitiesAndAmounts() {
    var r=request("SCRAP");var selected=selection(1,PriceFact.normal(d("10"),SourceEvidence.ofJson("{\"fixture\":1}"),Set.of()));
    var plan=new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new Use("root/SCRAP",r,d("2"))),Set.of());
    var credit=new MaterialLine("root/SCRAP","SCRAP",d("-2"),List.of(1L),d("-20"),d("-16"),null);
    var result=MonthlyProductCost.calculate(plan,zero(List.of(credit)),null);
    assertThat(result.financeMaterialCost()).isEqualByComparingTo("-20");assertThat(result.cuMaterialAdjustment()).isEqualByComparingTo("4");
  }

  @Test void ordinaryLinesRetainTheirExistingMoneyBoundaryBeforeMaterialSummation() {
    var r=request("N");var selected=selection(1,PriceFact.normal(d(".00000049"),SourceEvidence.ofJson("{\"fixture\":1}"),Set.of()));
    var plan=new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new Use("root/A",r,d("1")),new Use("root/B",r,d("1"))),Set.of());
    var a=new MaterialLine("root/A","N",d("1"),List.of(1L),d(".00000049"),d(".00000049"),null);
    var b=new MaterialLine("root/B","N",d("1"),List.of(1L),d(".00000049"),d(".00000049"),null);
    assertThat(MonthlyProductCost.calculate(plan,zero(List.of(a,b)),null).financeMaterialCost()).isEqualByComparingTo("0");
  }

  @Test void unusedPricesAndUnrelatedSuppressionCannotBecomeVersionEvidence() {
    var r=request("H");var selected=selection(1,household("1","0"));
    assertThatThrownBy(()->MonthlyProductCost.calculate(new MonthlyAdoptionPlan(Map.of(r,selected),List.of(),Set.of()),zero(List.of()),null))
        .hasMessageContaining("consumed");
    var plan=new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new Use("root/H",r,d("1"))),Set.of("root/OTHER/child"));
    assertThatThrownBy(()->MonthlyProductCost.calculate(plan,zero(List.of()),null)).hasMessageContaining("below a household");
  }

  @Test void ordinaryDetailCannotIntroduceASecondProfileFeeOrFinalTotal() {
    assertThatThrownBy(()->new OrdinaryDetail("OTHER_EXP_TECH_MOULD","模具费",null,null,d("1"),"public",1L,null,"EXPENSE")).hasMessageContaining("PROFILE");
    assertThatThrownBy(()->new OrdinaryDetail("TOTAL","总计",null,null,d("1"),null,null,null,"EXPENSE")).hasMessageContaining("final totals");
    assertThatThrownBy(()->new OrdinaryDetail("FINAL_QUOTE","最终报价",null,null,d("1"),null,null,null,"EXPENSE")).hasMessageContaining("final totals");
  }

  @Test void sharedNumbersKeepEachConsumersProfileAndOrdinaryDetailSources() throws Exception {
    var plan = new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of());
    var original = zero(List.of());
    var a = withDetail(original, 71L, d("12"));
    var b = withDetail(original, 92L, d("12"));
    var techA = technical(Arrays.asList(d("1"), null, d("2"), BigDecimal.ZERO));
    var techB = new EffectiveTechnicalDataInput(2L, 88L, 3, "2026-10", techA.sourceType(),
        "customer B", techA.retrievedAt(), false, false, false, null, null, null,
        List.of(), List.of(), List.of(), techA.productFees(), null);
    var ra = MonthlyProductCost.resolve(plan, a, techA);
    var rb = MonthlyProductCost.resolve(plan, b, techB);
    assertThat(rb.inputs()).isEqualTo(ra.inputs());
    var numbers = MonthlyProductCost.calculate(ra.inputs());
    var json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.writeValueAsString(numbers)).doesNotContain("sourceId", "profileVersionId", "customer B", "sourceVersionId");
    var bound = rb.bind(numbers);
    assertThat(bound).isEqualTo(MonthlyProductCost.calculate(plan, b, techB));
    assertThat(bound.technicalInput()).isEqualTo(techB);
    assertThat(bound.fees()).allSatisfy(fee -> assertThat(fee.profileVersionId()).isEqualTo(88L));
    assertThat(bound.ordinaryInputs().details().getFirst().sourceId()).isEqualTo(92L);
    assertThat(ra.bind(numbers).ordinaryInputs().details().getFirst().sourceId()).isEqualTo(71L);
  }

  @Test void equalTotalsCannotHideDifferentDetailInputsOrMissingFeeStates() {
    var plan = new MonthlyAdoptionPlan(Map.of(), List.of(), Set.of());
    var original = zero(List.of());
    var a = MonthlyProductCost.resolve(plan, withDetail(original, 1L, d("12")), null);
    var b = MonthlyProductCost.resolve(plan, withDetail(original, 1L, d("13")), null);
    var values = MonthlyProductCost.calculate(a.inputs());
    assertThat(MonthlyProductCost.calculate(b.inputs()).totalCost()).isEqualTo(values.totalCost());
    assertThatThrownBy(() -> b.bind(values)).hasMessageContaining("inputs differ");
    var explicit = MonthlyProductCost.resolve(plan, withDetail(original, 1L, d("12")),
        technical(Arrays.asList(BigDecimal.ZERO, null, null, null)));
    assertThatThrownBy(() -> explicit.bind(values)).hasMessageContaining("inputs differ");
  }

  @Test void sharedNumbersAllowEquivalentDecimalScalesWithoutLosingDetailPrecision() {
    var plan = new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of());
    var a = MonthlyProductCost.resolve(plan,withDetail(zero(List.of()),1L,d("12.000000000000000001")),
        technical(Arrays.asList(d("1.00"),null,null,null)));
    var b = MonthlyProductCost.resolve(plan,withDetail(zero(List.of()),2L,d("12.00000000000000000100")),
        technical(Arrays.asList(d("1.0000"),null,null,null)));
    var numbers = MonthlyProductCost.calculate(a.inputs());
    assertThat(b.bind(numbers).ordinaryInputs().details().getFirst().sourceId()).isEqualTo(2L);
    var different = MonthlyProductCost.resolve(plan,withDetail(zero(List.of()),2L,d("12.000000000000000002")),
        technical(Arrays.asList(d("1.0000"),null,null,null)));
    assertThatThrownBy(() -> different.bind(numbers)).hasMessageContaining("inputs differ");
  }

  private static Ordinary withDetail(Ordinary input, long source, BigDecimal amount) {
    return new Ordinary(input.materials(), input.nonBomMaterial(), input.directLabor(), input.indirectLabor(),
        input.lossRate(), input.manufactureRate(), input.coefficient(), input.managementRate(), input.salesRate(),
        input.financeRate(), input.otherIndependentFees(), List.of(new OrdinaryDetail("WATER_POWER", "水电费",
            null, null, amount, "lp_quote_tech_data_version", source, "own source " + source, "EXPENSE")));
  }

  @Test void sharedNumbersContainNoPrivateBomPathsAndRebindEachConsumersPaths() throws Exception {
    var h = request("H"); var b = request("B");
    var prices = Map.of(h, selection(1, household("20", ".085")),
        b, selection(2, PriceFact.normal(d("3"), SourceEvidence.ofJson("{\"source\":\"fixed\"}"), Set.of())));
    var planA = new MonthlyAdoptionPlan(prices, List.of(new Use("root/OA_A_101", h, d("2")), new Use("root/OA_A_102", b, d("1"))), Set.of());
    var planB = new MonthlyAdoptionPlan(prices, List.of(new Use("root/OA_B_201", h, d("2")), new Use("root/OA_B_202", b, d("1"))), Set.of());
    var a = MonthlyProductCost.resolve(planA, zero(List.of(new MaterialLine("root/OA_A_102", "B", d("1"), List.of(2L), d("3"), d("4"), null))), null);
    var bInput = zero(List.of(new MaterialLine("root/OA_B_202", "B", d("1"), List.of(2L), d("3"), d("4"), null)));
    var resolvedB = MonthlyProductCost.resolve(planB, bInput, null);
    assertThat(resolvedB.inputs()).isEqualTo(a.inputs());
    var numbers = MonthlyProductCost.calculate(a.inputs());
    assertThat(new ObjectMapper().writeValueAsString(numbers)).doesNotContain("OA_A_", "OA_B_");
    var bound = resolvedB.bind(numbers);
    assertThat(bound).isEqualTo(MonthlyProductCost.calculate(planB, bInput, null));
    assertThat(bound.household().getFirst().paths()).containsExactly("root/OA_B_201");
    assertThat(bound.ordinaryInputs().materials().getFirst().path()).isEqualTo("root/OA_B_202");
  }

}
