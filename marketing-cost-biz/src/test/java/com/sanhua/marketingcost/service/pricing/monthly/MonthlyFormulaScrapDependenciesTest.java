package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonthlyFormulaScrapDependenciesTest {
  final MonthlyFormulaScrapDependencies bridge=new MonthlyFormulaScrapDependencies(new MonthlyPriceDocumentCodec(new ObjectMapper()));
  static Context context(){return new Context("COMMERCIAL","MPP05-SCRAP-DEPS",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,9,0),"fixture","210");}
  static MonthlyPriceRequest parent(String code,String org,NonCuConditions conditions){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),org,conditions,new BigDecimal("20"));}
  static MaterialScrapRef ref(String parent,String child){var row=new MaterialScrapRef();row.setId(31L);row.setMaterialCode(parent);row.setScrapCode(child);row.setRatio(new BigDecimal("0.5"));row.setBusinessUnitType("COMMERCIAL");return row;}
  static Candidate child(Long id,String kind){return new Candidate(new Selection(id,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any())),PriceFact.normal(new BigDecimal("10"),SourceEvidence.ofJson("{\"sourceKind\":\""+kind+"\",\"fixture\":\"saved-public-basis\"}"),Set.of())),null);}
  @Test void repeatedParentsReadOneActualDependencyBatchAndKeepThePublicChildFact(){
    var a=parent("A","210",NonCuConditions.empty());var b=parent("B","210",NonCuConditions.empty());var calls=new AtomicInteger();var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01")),b,List.of(ref("B","SCRAP-01"))),requests->{calls.incrementAndGet();assertThat(requests).hasSize(1);var r=requests.iterator().next();assertThat(r.family().unit()).isEqualTo("KG");assertThat(r.family().taxBasis()).isEqualTo(TaxBasis.EXCLUDED);assertThat(r.priceBasisQuantity()).isEqualByComparingTo("1");return new Candidates(Map.of(r,child(null,"SCRAP_PRICE")),Map.of());});assertThat(calls).hasValue(1);assertThat(result.problems()).isEmpty();assertThat(result.values()).hasSize(2);assertThat(result.values().get(a).dependencies()).isEqualTo(result.values().get(b).dependencies());assertThat(result.values().get(a).availability().matches(a)).isTrue();
  }
  @Test void actualBoardConditionsRemainInTheDependencyLookup(){
    var terms=new NonCuConditions(new BoardTerms(new BigDecimal("3000"),null,true));var a=parent("A","220",terms);var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->{var r=requests.iterator().next();assertThat(r.priceOrgCode()).isEqualTo("220");assertThat(r.conditions()).isEqualTo(terms);return new Candidates(Map.of(r,child(null,"SCRAP_PRICE")),Map.of());});assertThat(result.problems()).isEmpty();assertThat(result.values().get(a).availability().matches(a)).isTrue();
  }
  @Test void dependencyProblemsBlockTheParentWithoutInventingAnAmount(){
    var a=parent("A","210",NonCuConditions.empty());for(var reason:List.of(MonthlyAdoptionRetryPolicy.Failure.MISSING,MonthlyAdoptionRetryPolicy.Failure.WAITING,MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT)){var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->new Candidates(Map.of(),Map.of(requests.iterator().next(),new Problem(reason,"child not ready"))));assertThat(result.values()).isEmpty();assertThat(result.problems().get(a).reason()).isEqualTo(reason==MonthlyAdoptionRetryPolicy.Failure.MISSING?MonthlyAdoptionRetryPolicy.Failure.WAITING:reason);}
  }
  @Test void anAlreadyAdoptedNormalFactWinsWhenTheScrapSourceWasAddedLater(){
    var a=parent("A","210",NonCuConditions.empty());var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->new Candidates(Map.of(requests.iterator().next(),child(17L,"FIXED_PURCHASE")),Map.of()));assertThat(result.problems()).isEmpty();assertThat(result.values()).containsKey(a);
  }
  @Test void anUnadoptedOrdinarySourceCannotPretendToBeAValidScrapSource(){
    var a=parent("A","210",NonCuConditions.empty());var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->new Candidates(Map.of(requests.iterator().next(),child(null,"FIXED_PURCHASE")),Map.of()));assertThat(result.values()).isEmpty();assertThat(result.problems().get(a).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }
  @Test void invalidMappingDoesNotResolveOrAdoptAnUnneededChild(){
    var a=parent("A","210",NonCuConditions.empty());var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","X"),ref("A","Y"))),requests->{throw new AssertionError("Conflicting mapping must stop before dependency I/O");});assertThat(result.values()).isEmpty();assertThat(result.problems().get(a).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }
  @Test void aSpecificSavedChildRestrictsTheParentAvailabilityInsteadOfClaimingAnyCondition(){
    var terms=new NonCuConditions(new BoardTerms(new BigDecimal("3000"),null,true));var a=parent("A","220",terms);var specific=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("220"),new QuantityInterval(BigDecimal.ONE,true,new BigDecimal("10"),true),ConditionDomain.exact(terms)));var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->new Candidates(Map.of(requests.iterator().next(),new Candidate(new Selection(17L,specific,child(17L,"FIXED_PURCHASE").selection().fact()),null)),Map.of()));assertThat(result.problems()).isEmpty();var available=result.values().get(a).availability();assertThat(available.matches(a)).isTrue();assertThat(available.matches(parent("A","220",NonCuConditions.empty()))).isFalse();
  }
  @Test void parentInputReferencesTheImmutableChildInsteadOfCopyingItsEntireSourceDocument(){
    var a=parent("A","210",NonCuConditions.empty());var evidence=SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP_PRICE\",\"raw\":\""+"x".repeat(500000)+"\"}");var value=child(null,"SCRAP_PRICE").selection();var large=new Candidate(new Selection(null,value.applicability(),PriceFact.normal(new BigDecimal("10"),evidence,Set.of())),null);var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->new Candidates(Map.of(requests.iterator().next(),large),Map.of()));assertThat(result.problems()).isEmpty();var snapshot=result.values().get(a).scrap().prices().get("SCRAP-01").evidence().json();assertThat(snapshot.length()).isLessThan(4096);assertThat(snapshot).contains(evidence.sha256());assertThat(result.values().get(a).dependencies()).hasSize(1);
  }
  @Test void legalSavedDependencyClosureIsAcceptedButUnrelatedCallbackRowsAreRejected(){
    var a=parent("A","210",NonCuConditions.empty());var result=bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->{var root=requests.iterator().next();var grand=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(root.family().month(),root.family().businessUnitType(),"GRAND",null,"KG","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);var base=child(17L,"FIXED_PURCHASE").selection();var parent=new Candidate(new Selection(17L,base.applicability(),PriceFact.normal(base.fact().price(),base.fact().evidence(),Set.of(grand))),null);return new Candidates(Map.of(root,parent,grand,child(18L,"FIXED_PURCHASE")),Map.of());});assertThat(result.problems()).isEmpty();assertThat(result.values()).containsKey(a);
    assertThatThrownBy(()->bridge.resolve(context(),Map.of(a,List.of(ref("A","SCRAP-01"))),requests->{var root=requests.iterator().next();var unrelated=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(root.family().month(),root.family().businessUnitType(),"UNRELATED",null,"KG","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);return new Candidates(Map.of(root,child(17L,"FIXED_PURCHASE"),unrelated,child(18L,"FIXED_PURCHASE")),Map.of());})).isInstanceOf(MonthlyPriceProblem.class);
  }
  @Test void aSharedChildSourceDocumentIsDecodedOnceForFiveHundredParents(){
    var codec=mock(MonthlyPriceDocumentCodec.class,withSettings().useConstructor(new ObjectMapper()).defaultAnswer(CALLS_REAL_METHODS).mockMaker(org.mockito.MockMakers.INLINE));var bridge=new MonthlyFormulaScrapDependencies(codec);var parents=new LinkedHashMap<MonthlyPriceRequest,List<MaterialScrapRef>>();for(int i=0;i<500;i++){String code="P"+i;parents.put(parent(code,"210",NonCuConditions.empty()),List.of(ref(code,"SCRAP-01")));}var fact=PriceFact.normal(new BigDecimal("10"),SourceEvidence.ofJson("{\"sourceKind\":\"SCRAP_PRICE\",\"raw\":\""+"x".repeat(10000)+"\"}"),Set.of());var result=bridge.resolve(context(),parents,requests->{assertThat(requests).hasSize(1);return new Candidates(Map.of(requests.iterator().next(),new Candidate(new Selection(null,child(null,"SCRAP_PRICE").selection().applicability(),fact),null)),Map.of());});assertThat(result.values()).hasSize(500);verify(codec,times(1)).decode(anyString(),eq(Map.class));
  }
}
