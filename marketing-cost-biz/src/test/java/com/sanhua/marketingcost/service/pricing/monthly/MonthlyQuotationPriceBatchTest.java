package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyQuotationPriceBatchTest {
  final MonthlyMaterialPrices prices=mock(MonthlyMaterialPrices.class);
  final MonthlyQuotationPrices reader=new MonthlyQuotationPrices(prices,new HouseholdMonthlyPriceSource(
      mock(HouseholdInquiryStorage.class),null,mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class), mock(com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader.class)));

  @Test void completeRequestsAreLookedUpOnceAndResultsStayInConsumerOrder() {
    savedPrices();
    var part=item("P");
    var results=reader.readBatch(List.of(new Demand(context(1),List.of(part,part)),
        new Demand(context(2),List.of(part)),new Demand(context(3),List.of())));
    verify(prices).lookup(argThat(requests -> requests.size()==1));
    assertThat(results).hasSize(3);
    assertThat(results.get(0)).isEqualTo(results.get(1));
    assertThat(results.get(0).values()).hasSize(1);
    assertThat(results.get(2).values()).isEmpty();
    verifyNoMoreInteractions(prices);
  }

  @Test void missingSourcesNeverBorrowAnotherConsumersDecisionOrTemporaryPrice() {
    when(prices.lookup(anyCollection())).thenAnswer(call -> new Lookup(Map.of(),new HashSet<>(call.getArgument(0))));
    when(prices.resolveCandidates(any(),anySet())).thenAnswer(call -> {
      Context context=call.getArgument(0);Set<MonthlyPriceRequest> requests=call.getArgument(1);
      if(context.oaFormItemId()==1) return new Candidates(Map.of(),Map.of(requests.iterator().next(),
          new Problem(MonthlyAdoptionRetryPolicy.Failure.WAITING,"该客户尚未批准")));
      return new Candidates(Map.of(requests.iterator().next(),new Candidate(selection(null,"7",List.of()),null)),Map.of());
    });
    var part=item("P");
    var result=reader.readBatch(List.of(new Demand(context(1),List.of(part)),new Demand(context(2),List.of(part))));
    assertThat(result.get(0).problems().get(part).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
    assertThat(result.get(0).values()).isEmpty();
    assertThat(result.get(1).values().get(part).candidate().selection().fact().price()).isEqualByComparingTo("7");
    verify(prices,times(2)).resolveCandidates(any(),anySet());
    verify(prices,never()).adopt(any(),any());
  }

  @Test void everyApplicabilityDimensionSurvivesTheUnion() {
    savedPrices();var base=item("P");var items=new ArrayList<Item>();items.add(base);
    items.add(new Item("OTHER","210","件",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","220","件",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","210","kg",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","210","件","spec",TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","210","件",null,TaxBasis.INCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","210","件",null,TaxBasis.EXCLUDED,"SUPPLIER",NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL));
    items.add(new Item("P","210","件",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),new BigDecimal("2"),Purpose.MATERIAL));
    items.add(new Item("P","220","件",null,TaxBasis.EXCLUDED,null,
        new NonCuConditions(new BoardTerms(BigDecimal.ONE,BigDecimal.TEN,true)),BigDecimal.ONE,Purpose.MATERIAL));
    var c=context(1);
    var annual=new Context(c.businessUnitType(),c.oaNo(),c.oaFormItemId(),c.productCode(),c.month(),c.priceAsOfTime(),c.actor(),c.consumerOrg(),null,new AnnualUsageInput(new BigDecimal("1200"),"PIECES"));
    var month=new Context(c.businessUnitType(),c.oaNo(),c.oaFormItemId(),c.productCode(),YearMonth.of(2026,12),c.priceAsOfTime(),c.actor(),c.consumerOrg());
    var unit=new Context("HOUSEHOLD",c.oaNo(),c.oaFormItemId(),c.productCode(),c.month(),c.priceAsOfTime(),c.actor(),c.consumerOrg());
    var result=reader.readBatch(List.of(new Demand(c,items),new Demand(annual,List.of(base)),new Demand(month,List.of(base)),new Demand(unit,List.of(base))));
    verify(prices).lookup(argThat(requests -> requests.size()==12));
    assertThat(result).allSatisfy(batch -> assertThat(batch.problems()).isEmpty());
    assertThat(result.getFirst().values()).hasSize(9);
  }

  @Test void savedDependenciesAreSharedWithoutExposingOtherConsumersPrices() {
    var a=item("A");var b=item("B");var dep=MonthlyQuotationPrices.requests(context(1),item("DEPENDENCY")).getFirst();
    var ar=MonthlyQuotationPrices.requests(context(1),a).getFirst();var br=MonthlyQuotationPrices.requests(context(2),b).getFirst();
    when(prices.lookup(anyCollection())).thenReturn(new Lookup(Map.of(ar,selection(1L,"2",List.of(dep)),
        br,selection(2L,"3",List.of()),dep,selection(3L,"1",List.of())),Set.of()));
    var result=reader.readBatch(List.of(new Demand(context(1),List.of(a)),new Demand(context(2),List.of(b))));
    assertThat(result.get(0).candidates()).containsOnlyKeys(ar,dep);
    assertThat(result.get(1).candidates()).containsOnlyKeys(br);
  }

  @Test void taxAmbiguityIsStillAnErrorForOnlyTheAffectedConsumer() {
    savedPrices();var unknown=new Item("A","210","件",null,null,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL);
    var result=reader.readBatch(List.of(new Demand(context(1),List.of(unknown)),new Demand(context(2),List.of(item("B")))));
    assertThat(result.get(0).problems().get(unknown).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
    assertThat(result.get(1).problems()).isEmpty();
  }

  @Test void eachCallReadsAgainAndNoUnboundedGlobalCacheIsCreated() {
    var part=item("P");var request=MonthlyQuotationPrices.requests(context(1),part).getFirst();
    when(prices.lookup(anyCollection())).thenReturn(new Lookup(Map.of(request,selection(1L,"2",List.of())),Set.of()),
        new Lookup(Map.of(request,selection(2L,"3",List.of())),Set.of()));
    assertThat(reader.read(context(1),List.of(part)).values().get(part).candidate().selection().monthlyPriceId()).isEqualTo(1);
    assertThat(reader.read(context(1),List.of(part)).values().get(part).candidate().selection().monthlyPriceId()).isEqualTo(2);
    verify(prices,times(2)).lookup(anyCollection());
  }

  @Test void batchSizeIsBoundedAndEmptyInputDoesNotTouchStorage() {
    assertThat(reader.readBatch(List.of())).isEmpty();
    assertThatThrownBy(() -> reader.readBatch(Collections.nCopies(65,new Demand(context(1),List.of(item("P"))))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
    verifyNoInteractions(prices);
  }

  @Test void preparedFirstReadIsSharedButEveryLaterAttemptReadsCurrentDatabaseAgain() {
    savedPrices();var part=item("P");var consumers=List.of(context(1),context(2));
    var prepared=reader.prepareBatch(consumers.stream().map(c -> new Demand(c,List.of(part))).toList());
    clearInvocations(prices);
    for(int i=0;i<consumers.size();i++) {
      var c=consumers.get(i);var initial=prepared.get(i);
      reader.usePrepared(initial, () -> {
        assertThat(reader.read(c,List.of(part))).isEqualTo(initial.batch());
        assertThatThrownBy(() -> reader.usePrepared(initial, () -> null)).hasMessageContaining("不能嵌套");
        return null;
      });
    }
    verifyNoInteractions(prices);
    reader.usePrepared(prepared.getFirst(), () -> {
      reader.read(consumers.getFirst(),List.of(part));
      reader.read(consumers.getFirst(),List.of(part));
      return null;
    });
    verify(prices).lookup(anyCollection());
  }

  @Test void preparedReadCannotCrossCustomersAndExceptionsReleaseTheScope() {
    savedPrices();var part=item("P");var input=reader.prepareBatch(List.of(new Demand(context(1),List.of(part)))).getFirst();
    assertThatThrownBy(() -> reader.usePrepared(input, () -> reader.read(context(2),List.of(part))))
        .hasMessageContaining("不属于当前客户");
    clearInvocations(prices);
    reader.read(context(1),List.of(part));
    verify(prices).lookup(anyCollection());
    reader.usePrepared(input, () -> reader.read(context(1),List.of(part)));
  }

  @Test void changedBOMRequestsReadFreshAndNeverConsumeOldCandidatesLater() {
    savedPrices();var part=item("P");var input=reader.prepareBatch(List.of(new Demand(context(1),List.of(part)))).getFirst();
    clearInvocations(prices);
    reader.usePrepared(input, () -> {
      assertThat(reader.read(context(1),List.of(item("OTHER"))).values()).containsOnlyKeys(item("OTHER"));
      reader.read(context(1),List.of(part));
      return null;
    });
    verify(prices,times(2)).lookup(anyCollection());
  }

  @Test void prefetchNeverResolvesOrRetainsCurrentSourceCandidatesOrSupplierDecisions() {
    var part=item("P");
    when(prices.lookup(anyCollection())).thenAnswer(call -> new Lookup(Map.of(),new HashSet<>(call.getArgument(0))));
    var ready=reader.prepareBatch(List.of(new Demand(context(1),List.of(part)),new Demand(context(2),List.of(part))));
    assertThat(ready).allSatisfy(value -> assertThat(value.batch().values()).isEmpty());
    verify(prices,never()).resolveCandidates(any(),anySet());
    verify(prices,never()).resolveScrapCandidates(any(),anySet());
    when(prices.resolveCandidates(any(),anySet())).thenAnswer(call -> {
      Set<MonthlyPriceRequest> requests=call.getArgument(1);
      return new Candidates(Map.of(requests.iterator().next(),new Candidate(selection(null,"9",List.of()),null)),Map.of());
    });
    var actual=reader.usePrepared(ready.getFirst(), () -> reader.read(context(1),List.of(part)));
    assertThat(actual.values().get(part).candidate().selection().fact().price()).isEqualByComparingTo("9");
    verify(prices).resolveCandidates(any(),anySet());
  }

  private void savedPrices() {
    when(prices.lookup(anyCollection())).thenAnswer(call -> {
      Collection<MonthlyPriceRequest> requests=call.getArgument(0);var result=new LinkedHashMap<MonthlyPriceRequest,Selection>();
      requests.forEach(request -> result.put(request,selection((long)result.size()+1,"1",List.of())));
      return new Lookup(result,Set.of());
    });
  }
  static Context context(long item) {
    return new Context("COMMERCIAL","OA-"+item,item,"TOP",YearMonth.of(2026,11),LocalDateTime.of(2026,11,1,0,0),"test","210");
  }
  static Item item(String code) {return new Item(code,"210","件",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,Purpose.MATERIAL);}
  static Selection selection(Long id,String price,List<MonthlyPriceRequest> dependencies) {
    return new Selection(id,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any())),
        new PriceFact(PricingType.NORMAL,new BigDecimal(price),null,SourceEvidence.ofJson("{\"sourceKind\":\"PURCHASE_FIXED\"}"),Set.copyOf(dependencies)));
  }
}
