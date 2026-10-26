package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.mapper.monthly.MonthlyLegacyRangeSourceMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class MonthlyPriceSourcesTest {
  final MaterialPricePriorityService priorities=fake(MaterialPricePriorityService.class);
  final HouseholdMonthlyPriceSource household=fake(HouseholdMonthlyPriceSource.class);
  final MonthlyFixedPriceSource fixed=fake(MonthlyFixedPriceSource.class);
  final MonthlySettlementPriceSource settlement=fake(MonthlySettlementPriceSource.class);
  final MonthlyLegacyLinkedPriceSource linked=fake(MonthlyLegacyLinkedPriceSource.class);
  final MonthlyOriginalLinkedPriceSource originalLinked=fake(MonthlyOriginalLinkedPriceSource.class);
  final MonthlyLegacyRangePriceSource range=fake(MonthlyLegacyRangePriceSource.class);
  final MonthlyOriginalRangePriceSource originalRange=fake(MonthlyOriginalRangePriceSource.class);
  final MonthlyQuantityRangeSource quantities=fake(MonthlyQuantityRangeSource.class);
  final MonthlyLegacyRangeSourceMapper rangeRows=fake(MonthlyLegacyRangeSourceMapper.class);
  final MonthlySupplementalFixedPriceSource supplemental=fake(MonthlySupplementalFixedPriceSource.class);
  final MonthlyScrapPriceSource scrap=fake(MonthlyScrapPriceSource.class);
  final MonthlyOdsPriceSource ods=fake(MonthlyOdsPriceSource.class);
  final MonthlyPriceSources sources=new MonthlyPriceSources(priorities,household,fixed,settlement,linked,originalLinked,range,originalRange,quantities,rangeRows,supplemental,scrap,ods);
  final Context context=new Context("COMMERCIAL","route",1L,"product",YearMonth.of(2026,10),LocalDateTime.of(2026,10,1,0,0),"test","210");
  static <T> T fake(Class<T> type){return java.lang.reflect.Modifier.isFinal(type.getModifiers())?mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE)):mock(type);}
  boolean manual=false;
  List<MaterialPriceSourceType> order=MaterialPriceSourceType.DEFAULT_ORDER;

  @BeforeEach void missingSources() {
    when(household.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of()));
    when(scrap.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of()));
    when(priorities.readBatch(any(),anyMap(),eq(false))).thenAnswer(call->{
      var scope=call.getArgument(0,MaterialPricePriorityService.Scope.class);Map<String,String> names=call.getArgument(1);
      var configurations=new LinkedHashMap<String,MaterialPricePriorityService.Configuration>();
      names.forEach((code,name)->configurations.put(code,new MaterialPricePriorityService.Configuration(scope,
          order.stream().map(type->new MaterialPricePriorityService.Entry(type,order.indexOf(type)+1)).toList(),manual,"saved")));
      return configurations;
    });
    when(fixed.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(linked.read(any(),anySet(),any())).thenAnswer(call->missing(call.getArgument(1)));
    when(originalLinked.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(range.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(originalRange.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(quantities.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(settlement.read(any(),anyMap())).thenAnswer(call->missing(((Map<MonthlyPriceRequest,?>)call.getArgument(1)).keySet()));
    when(ods.orders(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(ods.tiers(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(supplemental.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
  }
  Candidates read(Set<MonthlyPriceRequest> requests){return sources.resolve(context,requests,ignored->{throw new AssertionError("No formula dependency expected");});}
  MonthlyPriceRequest request(String code){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);}
  static Candidates missing(Collection<MonthlyPriceRequest> requests){var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();requests.forEach(request->problems.put(request,new Problem(Failure.MISSING,"missing")));return new Candidates(Map.of(),problems);}
  static Candidate candidate(String kind){return new Candidate(new Selection(null,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any())),PriceFact.normal(new BigDecimal("12"),SourceEvidence.ofJson("{\"sourceKind\":\""+kind+"\"}"),Set.of())),null);}
  static Candidates found(MonthlyPriceRequest request,String kind){return new Candidates(Map.of(request,candidate(kind)),Map.of());}

  @Test void financeCanDesignateFixedWithoutTryingOtherOrdinaryTypes() {
    manual=true;
    order=List.of(MaterialPriceSourceType.FIXED,MaterialPriceSourceType.LINKED,MaterialPriceSourceType.RANGE,MaterialPriceSourceType.SETTLE_FIXED,MaterialPriceSourceType.SETTLE_LINKED);
    var request=request("fixed-first");when(fixed.read(any(),anySet())).thenReturn(found(request,"PURCHASE_FIXED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    verifyNoInteractions(linked,originalLinked,settlement,range,originalRange,quantities,supplemental);
  }
  @Test void onlyCompleteAbsenceOfExistingPricesReachesSupplementalFixed() {
    var request=request("supplemental");when(supplemental.read(any(),anySet())).thenReturn(found(request,"TECH_FIXED"));
    assertThat(read(Set.of(request)).values().get(request).selection().fact().evidence().json()).contains("TECH_FIXED");
    verify(fixed).read(context,Set.of(request));verify(linked).read(eq(context),eq(Set.of(request)),any());
    verify(settlement,times(2)).read(eq(context),anyMap());verify(quantities).read(context,Set.of(request));
  }
  @Test void waitingForAnExistingPriceNeverFallsBackToSupplementalOrPersistsZero() {
    var request=request("waiting");when(linked.read(any(),anySet(),any())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.WAITING,"finance not ready"))));
    var result=read(Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.WAITING);
    verifyNoInteractions(originalLinked,fixed,settlement,supplemental);
  }
  @Test void householdDoesNotReadOrdinaryPriceTables() {
    var request=request("household");when(household.read(any(),anySet())).thenReturn(found(request,"HOUSEHOLD_INQUIRY"));
    assertThat(read(Set.of(request)).problems()).isEmpty();verifyNoInteractions(fixed,linked,settlement,supplemental);
  }
  @Test void originalLinkedUsesTheSamePriorityPositionAsLegacyLinked() {
    var request=request("original");when(originalLinked.read(any(),anySet())).thenReturn(found(request,"LINKED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);verifyNoInteractions(fixed,settlement,supplemental);
  }
  @Test void anExistingFactorRuleDoesNotFallThroughToQuantityBandsWhenItsMonthIsMissing() {
    var request=request("factor");when(rangeRows.factorMaterials(anyString(),anyString(),anyCollection())).thenReturn(List.of("factor"));
    var result=read(Set.of(request));assertThat(result.problems().get(request).reason()).isEqualTo(Failure.MISSING);
    verify(range).read(context,Set.of(request));verify(originalRange).read(context,Set.of(request));verifyNoInteractions(quantities);
  }
  @Test void priorityReadsStayBatchedForAThousandUniqueMaterials() {
    order=List.of(MaterialPriceSourceType.FIXED,MaterialPriceSourceType.LINKED,MaterialPriceSourceType.RANGE,MaterialPriceSourceType.SETTLE_FIXED,MaterialPriceSourceType.SETTLE_LINKED);
    var requests=new LinkedHashSet<MonthlyPriceRequest>();for(int i=0;i<1000;i++)requests.add(request("M"+i));
    when(fixed.read(any(),anySet())).thenAnswer(call->{Set<MonthlyPriceRequest> batch=call.getArgument(1);var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();batch.forEach(request->values.put(request,candidate("PURCHASE_FIXED")));return new Candidates(values,Map.of());});
    assertThat(read(requests).values()).hasSize(1000);verify(priorities,times(4)).readBatch(any(),argThat(names->names.size()<=256),eq(false));verifyNoInteractions(supplemental,linked);
  }
  @Test void scrapDependencyReadsOnlyScrapAndDoesNotUseTheOrdinaryFallback() {
    var request=request("scrap");when(scrap.read(any(),anySet())).thenReturn(found(request,"SCRAP_PRICE"));
    assertThat(sources.resolveScrap(context,Set.of(request),ignored->{throw new AssertionError();}).values()).containsKey(request);
    verifyNoInteractions(household,priorities,fixed,linked,supplemental);
  }
  @Test void financeDesignatedFixedMissingDoesNotFallBackToOtherTypesOrOds() {
    manual=true;order=List.of(MaterialPriceSourceType.FIXED,MaterialPriceSourceType.LINKED,MaterialPriceSourceType.RANGE,MaterialPriceSourceType.SETTLE_FIXED,MaterialPriceSourceType.SETTLE_LINKED);
    var request=request("finance-fixed-missing");var result=read(Set.of(request));
    assertThat(result.problems().get(request).reason()).isEqualTo(Failure.MISSING);
    assertThat(result.problems().get(request).message()).contains("财务指定");
    verifyNoInteractions(household,linked,originalLinked,range,originalRange,quantities,settlement,ods,supplemental);
  }
  @Test void defaultOrderThenOrderPriceThenTierThenSupplemental() {
    var request=request("ods-order");when(ods.orders(any(),anySet())).thenReturn(found(request,"ODS_ORDER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var calls=inOrder(fixed,ods);calls.verify(fixed).read(context,Set.of(request));calls.verify(ods).orders(context,Set.of(request));
    verify(ods,never()).tiers(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void onlyMissingOrderPriceReachesTierAndTierMissingReachesSupplemental() {
    var request=request("ods-tier");when(ods.tiers(any(),anySet())).thenReturn(found(request,"ODS_TIER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var calls=inOrder(ods);calls.verify(ods).orders(context,Set.of(request));calls.verify(ods).tiers(context,Set.of(request));
    verifyNoInteractions(supplemental);
  }
  @Test void orderSupplierWaitingDoesNotFallThroughToTierOrSupplemental() {
    var request=request("order-wait");when(ods.orders(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.WAITING,"确认选供"))));
    assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.WAITING);
    verify(ods,never()).tiers(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void missingAnnualUsageOnlyBlocksWhenTierSourceReportsItsActualNeed() {
    var request=request("needs-demand");when(ods.tiers(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.INVALID_INPUT,"阶梯取价缺少年用量"))));
    assertThat(read(Set.of(request)).problems().get(request).message()).contains("年用量");verifyNoInteractions(supplemental);
  }
}
