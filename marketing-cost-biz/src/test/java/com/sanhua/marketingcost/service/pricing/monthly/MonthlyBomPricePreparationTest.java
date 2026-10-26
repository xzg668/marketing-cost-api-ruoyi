package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.settlement.*;
import com.sanhua.marketingcost.service.rule.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyBomPricePreparationTest {
  final MonthlyMaterialPrices prices=mock(MonthlyMaterialPrices.class);
  final HouseholdInquiryStorage sourceStorage=mock(HouseholdInquiryStorage.class);
  final HouseholdMonthlyPriceSource household=new HouseholdMonthlyPriceSource(sourceStorage,new MonthlyPriceDocumentCodec(new ObjectMapper()), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class));
  final BomSettlementRowBuildEngine engine=new BomSettlementRowBuildEngine(
      new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
      new BomByproductCostRuleMatcher(new BomByproductCostRuleConditionEvaluator(new ObjectMapper())));
  final MonthlyBomPricePreparation app=new MonthlyBomPricePreparation(prices,household,engine);
  final Context context=new Context("COMMERCIAL","MPP06",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,0,0),"test","210");

  static MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
  }
  static BomSettlementNode node(String code,String parent,String path,int level,String qty) {
    return new BomSettlementNode(null,"TOP",parent,code,level,path,new BigDecimal(qty),new BigDecimal(qty),code,null,
        level==0?"制造件":"采购件",level==0?"制造件":"采购件",null,null,null,null,"主制造","v1",1,level==0?0:1,
        null,null,null,"210","COMMERCIAL","COMMERCIAL",null);
  }
  static BomSettlementBuildRequest bom(List<BomSettlementNode> nodes) {
    return new BomSettlementBuildRequest("MPP06","TOP",LocalDate.of(2026,10,23),"2026-10","test",LocalDateTime.of(2026,10,23,0,0),"COMMERCIAL","主制造",nodes,List.of());
  }
  static Map<String,MonthlyBomPricePreparation.PriceInput> inputs(BomSettlementBuildRequest bom) {
    var result=new LinkedHashMap<String,MonthlyBomPricePreparation.PriceInput>();
    bom.nodes().forEach(node -> result.put(node.path(),new MonthlyBomPricePreparation.PriceInput(request(node.materialCode()),BigDecimal.ONE,MonthlyBomPricePreparation.PricePurpose.MATERIAL)));
    return result;
  }
  void noHousehold() {
    when(prices.lookup(anyCollection())).thenAnswer(invocation -> new Lookup(Map.of(),Set.copyOf(invocation.getArgument(0))));
    when(sourceStorage.current(anyString(),anyCollection())).thenReturn(List.of());
  }

  @Test void rejectsForgedOrdinaryPathMaterialAndQuantityBeforeReadingTheirSources() {
    noHousehold();var bom=bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","TOP","TOP/A",1,"2.5")));
    for(var use:List.of(new Use("TOP/UNKNOWN",request("A"),new BigDecimal("2.5")),
        new Use("TOP/A",request("OTHER"),new BigDecimal("2.5")),new Use("TOP/A",request("A"),BigDecimal.ONE))) {
      assertThatThrownBy(() -> app.preview(context,bom,inputs(bom),(visible,rows) -> List.of(use)))
          .isInstanceOfSatisfying(MonthlyPriceProblem.class,error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT));
    }
    verify(prices,never()).resolveCandidates(any(),anySet());
    verify(prices,never()).adopt(any(),any(),any());
  }

  @Test void validatesOriginalIdentityAndQuantitiesBeforeAnyHouseholdProbe() {
    var malformed=List.of(
        bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","WRONG","TOP/A",1,"1"))),
        bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","TOP","TOP/A",3,"1"))),
        bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","TOP","TOP/A",1,"1"),node("B","A","TOP/A/B",2,"1"))),
        bom(List.of(node("WRONG",null,"TOP",0,"1"))),
        bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","TOP","TOP/A",1,"-1"))));
    for(var bom:malformed)assertThatThrownBy(() -> app.preview(context,bom,inputs(bom),(visible,rows) -> List.of()))
        .isInstanceOf(MonthlyPriceProblem.class);
    verifyNoInteractions(prices,sourceStorage);
  }

  @Test void explicitConversionCannotBeMissingZeroOrNegative() {
    for(BigDecimal factor:Arrays.asList(null,BigDecimal.ZERO,BigDecimal.ONE.negate())) {
      assertThatThrownBy(() -> new MonthlyBomPricePreparation.PriceInput(request("A"),factor,MonthlyBomPricePreparation.PricePurpose.MATERIAL))
          .isInstanceOf(MonthlyPriceProblem.class);
    }
  }

  @Test void wrongQuotationScopeIsRejectedBeforeAnyLookup() {
    var bom=bom(List.of(node("TOP",null,"TOP",0,"1")));
    var other=new Context("COMMERCIAL","OTHER",1L,"TOP",context.month(),context.priceAsOfTime(),"test","210");
    assertThatThrownBy(() -> app.preview(other,bom,inputs(bom),(visible,rows) -> List.of())).isInstanceOf(MonthlyPriceProblem.class);
    verifyNoInteractions(prices,sourceStorage);
  }

  @Test void syntheticSettlementUseCannotChangeItsConsumerOrganization() {
    noHousehold();
    var bom=bom(List.of(node("TOP",null,"TOP",0,"1"),node("A","TOP","TOP/A",1,"1")));
    var synthetic=new com.sanhua.marketingcost.entity.BomCostingRow();
    synthetic.setPath("TOP/A__BYPRODUCT__/S");synthetic.setMaterialCode("S");synthetic.setPriceOrgCode("210");
    synthetic.setQtyPerTop(BigDecimal.ONE.negate());synthetic.setSettlementRowType(BomSettlementRowBuildEngine.ROW_TYPE_BYPRODUCT_EXTRA);
    var built=new BomSettlementRowBuildResult(List.of(synthetic),List.of(),List.of(),List.of(),
        new BomSettlementRowBuildStats(2,1,0,0,0,0,0,0,1));
    var controlledEngine=mock(BomSettlementRowBuildEngine.class);when(controlledEngine.build(any())).thenReturn(built);
    var app=new MonthlyBomPricePreparation(prices,household,controlledEngine);
    var wrongOrg=new MonthlyPriceRequest(request("S").family(),"220",NonCuConditions.empty(),BigDecimal.ONE);
    var inputs=inputs(bom);inputs.put(synthetic.getPath(),new MonthlyBomPricePreparation.PriceInput(wrongOrg,BigDecimal.ONE,MonthlyBomPricePreparation.PricePurpose.SCRAP));
    assertThatThrownBy(() -> app.preview(context,bom,inputs,(visible,rows) -> List.of(new Use(synthetic.getPath(),wrongOrg,BigDecimal.ONE))))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT));
    verify(prices,never()).resolveCandidates(any(),anySet());
  }

  @Test void preservesExistingPurchaseClassificationWhenProductionCategoryHasVirtualText() {
    noHousehold();
    var leaf=new BomSettlementNode(null,"TOP","TOP","A",1,"TOP/A",BigDecimal.ONE,BigDecimal.ONE,"A",null,
        "采购件","虚拟件",null,null,null,null,"主制造","v1",1,1,null,null,null,"210","COMMERCIAL","COMMERCIAL",null);
    var bom=bom(List.of(node("TOP",null,"TOP",0,"1"),leaf));
    var fact=PriceFact.normal(BigDecimal.TEN,SourceEvidence.ofJson("{\"sourceKind\":\"FIXED\"}"),Set.of());
    var selected=new Selection(null,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),fact);
    when(prices.resolveCandidates(eq(context),eq(Set.of(request("A")))))
        .thenReturn(new Candidates(Map.of(request("A"),new Candidate(selected,null)),Map.of()));
    var result=app.preview(context,bom,inputs(bom),(visible,rows) -> rows.costingRows().stream()
        .map(row -> new Use(row.getPath(),request(row.getMaterialCode()),row.getQtyPerTop())).toList());
    assertThat(result.ordinaryRows().costingRows()).extracting(row -> row.getMaterialCode()).containsExactly("A");
    assertThat(result.plan().selections()).containsOnlyKeys(request("A"));
  }

  @Test void preservesExistingScrapRouteWithoutSendingScrapToHouseholdOrOrdinarySources() {
    noHousehold();
    var bom=bom(List.of(node("TOP",null,"TOP",0,"1"),node("SCRAP","TOP","TOP/S",1,"2.5")));
    var inputs=inputs(bom);inputs.put("TOP/S",new MonthlyBomPricePreparation.PriceInput(request("SCRAP"),BigDecimal.ONE,
        MonthlyBomPricePreparation.PricePurpose.SCRAP));
    var selected=new Selection(null,new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()),
        PriceFact.normal(new BigDecimal("7"),SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP\"}"),Set.of()));
    when(prices.resolveScrapCandidates(eq(context),eq(Set.of(request("SCRAP")))))
        .thenReturn(new Candidates(Map.of(request("SCRAP"),new Candidate(selected,null)),Map.of()));
    var result=app.preview(context,bom,inputs,(visible,rows) -> List.of(new Use("TOP/S",request("SCRAP"),new BigDecimal("2.5"))));
    assertThat(result.plan().selections()).containsEntry(request("SCRAP"),selected);
    verify(sourceStorage).current("COMMERCIAL",Set.of("TOP"));
    verify(prices,never()).resolveCandidates(any(),anySet());
    verify(prices).resolveScrapCandidates(context,Set.of(request("SCRAP")));
  }
}
