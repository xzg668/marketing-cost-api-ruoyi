package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.NetLossRateQuery;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyRepriceInputProjectionTest {
  record Fixture(MonthlyRepriceCalculation calculation, ResolvedCostRun resolved,
      MonthlyQuotationCosting.MaterialInputs materials, QuoteCostRunVersion version) {}
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final MonthlyBomSnapshotDetailService boms = mock(MonthlyBomSnapshotDetailService.class);
  final CostRunPreparedInputs.Catalog catalog = new CostRunPreparedInputs.Catalog(Map.of(
      new CostRunFeeQuery.OtherExpense("P"),List.of(),new CostRunFeeQuery.OtherExpense("UNRELATED"),List.of()));
  final MonthlyRepriceInputProjection projection = new MonthlyRepriceInputProjection(json,
      new MonthlyPriceDocumentCodec(json),boms);

  @Test void compressedNestedPreparationDoesNotChangeTheCompleteCalculationInput() throws Exception {
    var codec = new MonthlyPriceDocumentCodec(json);
    var paths = new MonthlyRepriceInputProjection.TechnicalPaths(null);
    var request=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(java.time.YearMonth.of(2026,11),"COMMERCIAL","M",null,"件","CNY",TaxBasis.EXCLUDED,null),
        "210",NonCuConditions.empty(),BigDecimal.ONE);
    var selected=new MonthlyAdoptionPlan.Selection(1L,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
        MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN,MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"value\":\""+"重复内容".repeat(500)+"\"}"),Set.of()));
    String original = codec.encode(MonthlyPreparationPrice.of(new QuotationPriceSession.Value(request,selected,BigDecimal.TEN,BigDecimal.TEN),"P/M",BigDecimal.ONE));
    String compact = codec.compact(original);
    var adopted=Map.of(request,selected);
    assertThat(compact).isNotEqualTo(original);
    assertThat(projection.preparation(original, paths,adopted)).isEqualTo(projection.preparation(compact, paths,adopted));
    String prefix = "{\"calculatorVersion\":\"PACKAGE_PRICE_V1\",\"components\":[{\"inputs\":";
    assertThat(projection.preparation(prefix + original + "}]}", paths,adopted))
        .isEqualTo(projection.preparation(prefix + compact + "}]}", paths,adopted));
    assertThat(projection.preparation(original,paths,adopted).path("selection").toString()).isEqualTo("{\"monthlyPriceId\":1}");
    assertThat(projection.preparation(original,paths,Map.of()).path("selection").has("fact")).isTrue();
    var changed=new MonthlyAdoptionPlan.Selection(selected.monthlyPriceId(),selected.applicability(),
        MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.ONE,selected.fact().evidence(),Set.of()));
    assertThatThrownBy(()->projection.preparation(original,paths,Map.of(request,changed))).hasMessageContaining("不一致");
  }

  @Test void equalWholeInputsAcrossOasKeepEveryOccurrenceButExcludeOnlyTheirPrivateCoordinates() {
    when(boms.load(anyLong())).thenReturn(List.of());
    var a = capture(fixture(1,"1.0","2.00","10.0",null));
    var b = capture(fixture(2,"1.000","2.0000","10.000",null));
    assertThat(a.sameInputs(b)).isTrue();
    assertThat(a.canonical()).doesNotContain("OA-1","CUSTOMER-1","private-1","oaFormItemId","profileVersionId");
    assertThat(a.canonical()).contains("@TECH_INPUT_0","parentBaseQty","quantityBasis","rawUnitPrice");
  }

  @Test void everyCostBearingQuantityWeightRateFeeAndCopperChangeSeparatesTheGroup() {
    when(boms.load(anyLong())).thenReturn(List.of());
    var original = fixture(1,"1","2","10",null); var a = capture(original);
    for (var changed : List.of(fixture(2,"2","2","10",null),fixture(2,"1","3","10",null),
        fixture(2,"1","2","11",null),fixture(2,"1","2","10",BigDecimal.ZERO)))
      assertThat(a.sameInputs(capture(changed))).isFalse();
    var copper = fixture(2,"1","2","10",null); copper.version().setOaCuPrice(new BigDecimal("81"));
    assertThat(a.sameInputs(capture(copper))).isFalse();
    var amended = original.materials().preparations().getFirst();
    var altered = new MonthlyQuotationCosting.PreparationInputs(amended.path(),amended.materialCode(),amended.priceOrganization(),
        amended.resultType(),amended.quantity(),amended.financialUnitPrice(),amended.customerUnitPrice(),
        amended.financialAmount(),amended.customerAmount(),amended.financialInputs().replace("\"rawUnitPrice\":2","\"rawUnitPrice\":3"),amended.customerInputs());
    var changed = new MonthlyQuotationCosting.MaterialInputs(original.materials().plan(),List.of(),Map.of(),List.of(altered),Map.of());
    assertThat(a.sameInputs(capture(new Fixture(original.calculation(),original.resolved(),changed,original.version())))).isFalse();
  }

  @Test void publicInputEvidenceContainsOnlyTheConsumersActualQueriesAndIsDefensive() {
    var first = fixture(1,"1","2","10",null); var second = fixture(2,"1","2","10",null);
    var a = first.resolved().context().getPreparedInputs(); var b = second.resolved().context().getPreparedInputs();
    a.fees().require(new CostRunFeeQuery.OtherExpense("P"),OtherExpenseRate.class);
    assertThat(a.resolvedFacts().path("fees")).hasSize(1);
    assertThat(b.resolvedFacts().path("fees")).isEmpty();
    ((com.fasterxml.jackson.databind.node.ObjectNode)a.resolvedFacts()).removeAll();
    assertThat(a.resolvedFacts().path("fees")).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"Cu","Zn","Ag","SUS304","SUS316"})
  void fullCustomerMaterialConditionsSeparateGroupsEvenWhenTheFixtureAmountsCoincide(String factor) {
    when(boms.load(anyLong())).thenReturn(List.of());
    var original=fixture(1,"1","2","10",null);
    var other=fixture(2,"1.0","2.00","10.000",null);
    setCustomerPrices(original.calculation().input().form());
    setCustomerPrices(other.calculation().input().form());
    var same=capture(other);assertThat(capture(original).sameInputs(same)).isTrue();
    var form=other.calculation().input().form();
    switch(factor) {
      case "Cu"->form.setCopperPrice(new BigDecimal("80001"));
      case "Zn"->form.setZincPrice(new BigDecimal("20001"));
      case "Ag"->form.setSilverPrice(new BigDecimal("500001"));
      case "SUS304"->form.setSus304Price(new BigDecimal("15001"));
      case "SUS316"->form.setSus316lPrice(new BigDecimal("25001"));
      default->throw new AssertionError(factor);
    }
    // Deliberately identical projected amounts expose a missing input, rather than relying on output differences.
    assertThat(capture(original).sameInputs(capture(other))).as(factor+" quotation condition").isFalse();
    assertThat(same.canonical()).contains("customerMaterialPrices","MONTHLY_MATERIAL_V4","materialPriceProjection");
  }

  private static void setCustomerPrices(OaForm form) {
    form.setCopperPrice(new BigDecimal("80000"));form.setZincPrice(new BigDecimal("20000"));
    form.setSilverPrice(new BigDecimal("500000"));form.setSus304Price(new BigDecimal("15000"));
    form.setSus316lPrice(new BigDecimal("25000"));
  }

  @Test void privateSettlementKeyOrderDoesNotChangeTheCompletePreparationCollection() {
    when(boms.load(anyLong())).thenReturn(List.of());
    var f=fixture(1,"1","2","10",null);var first=f.materials().preparations().getFirst();
    String otherPath="/P/BRANCH/M@TECH_private-1";
    var second=new MonthlyQuotationCosting.PreparationInputs(otherPath,first.materialCode(),first.priceOrganization(),
        first.resultType(),first.quantity(),first.financialUnitPrice(),first.customerUnitPrice(),first.financialAmount(),first.customerAmount(),
        first.financialInputs().replace(first.path(),otherPath),first.customerInputs().replace(first.path(),otherPath));
    var a=new MonthlyQuotationCosting.MaterialInputs(f.materials().plan(),List.of(),Map.of(),List.of(first,second),Map.of());
    var b=new MonthlyQuotationCosting.MaterialInputs(f.materials().plan(),List.of(),Map.of(),List.of(second,first),Map.of());
    assertThat(capture(new Fixture(f.calculation(),f.resolved(),a,f.version())).sameInputs(
        capture(new Fixture(f.calculation(),f.resolved(),b,f.version())))).isTrue();
  }

  MonthlyRepriceInputDocument capture(Fixture fixture) {
    return projection.capture(fixture.calculation(),fixture.resolved(),fixture.materials(),fixture.version());
  }

  Fixture fixture(long id, String quantity, String base, String rate, BigDecimal fee) {
    var form = new OaForm(); form.setId(id); form.setOaNo("OA-"+id); form.setCustomer("CUSTOMER-"+id);
    form.setBusinessUnitType("COMMERCIAL"); form.setProcessCode("FI-SC-006"); form.setCopperPrice(new BigDecimal("80000"));
    var item = new OaFormItem(); item.setId(id); item.setOaFormId(id); item.setMaterialNo("P"); item.setBusinessUnitType("COMMERCIAL");
    var input = new MonthlyRepriceBomService.Input(form,item,new QuoteBomContext("2026-11","P",null,null,new QuoteDataOrganization("210","COMMERCIAL")));
    var bom = new QuoteBomStatus(); bom.setSyncRecordId(id); bom.setCostPeriodMonth("2026-11");
    var calculation = new MonthlyRepriceCalculation("MRP1130-test","a".repeat(64),input,bom,LocalDateTime.of(2026,11,30,12,0));
    var context = calculation.costContext();
    context.setEffectiveTechnicalData(new EffectiveTechnicalDataInput(id,id,1,"2026-11",EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        "private-"+id,LocalDateTime.of(2026,11,30,12,0),false,false,false,null,null,null,List.of(),List.of(),List.of(),
        new EffectiveTechnicalDataInput.ProductFees(true,fee,null,null,"CNY",null,2),null,null,List.of(),
        List.of(new EffectiveTechnicalDataInput.MaterialLine("PACKAGE","private-"+id,"M","M",new BigDecimal(quantity),"kg",id))));
    context.setPreparedInputs(new CostRunPreparedInputs(context,Set.of("P"),new CostRunManufacturingSources.Sources(List.of(),List.of()),
        catalog,
        new NetLossRateQuery.Source("AVAILABLE",null,null,1L,"P","P","MODEL","BARE","COMMERCIAL",2026,"COMMERCIAL",1L,new BigDecimal(".01")),2026,"2026-11"));
    var ordinary = new CostRunCostItemDto(); ordinary.setCostCode("MATERIAL"); ordinary.setRate(new BigDecimal(rate));
    var resolved = new ResolvedCostRun(context,List.of(),new ResolvedOrdinaryCost(List.of(ordinary),BigDecimal.ZERO,true));
    String path="/P/M@TECH_private-"+id;
    // This unit test exercises projection only; production preparation integrity has independent DB gates.
    String snapshot="{\"calculatorVersion\":\"PACKAGE_PRICE_V1\",\"snapshotId\":"+id+",\"sourcePath\":\""+path+"\",\"parentBaseQty\":"+base
        +",\"components\":[{\"snapshotDetailId\":"+id+",\"quantity\":"+quantity+",\"quantityBasis\":\"PACKAGE_PARENT\",\"rawUnitPrice\":2}]}";
    var prep = new MonthlyQuotationCosting.PreparationInputs(path,"M","210","PACKAGE",new BigDecimal(quantity),new BigDecimal("2"),new BigDecimal("2"),
        new BigDecimal("2"),new BigDecimal("2"),snapshot,snapshot);
    var materials = new MonthlyQuotationCosting.MaterialInputs(new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of()),List.of(),Map.of(),List.of(prep),Map.of());
    var version = new QuoteCostRunVersion(); version.setFinanceCuPrice(new BigDecimal("70")); version.setOaCuPrice(new BigDecimal("80")); version.setFinanceBasePriceId(1L);
    return new Fixture(calculation,resolved,materials,version);
  }
}
