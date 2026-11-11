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
  final MonthlyInvoicePriceSource invoices=fake(MonthlyInvoicePriceSource.class);
  final MonthlyInquiryPriceSource inquiries=fake(MonthlyInquiryPriceSource.class);
  final MonthlyRepriceSupplementalPriceSource historical=fake(MonthlyRepriceSupplementalPriceSource.class);
  final MonthlyPriceSources sources=new MonthlyPriceSources(priorities,household,fixed,settlement,linked,originalLinked,range,originalRange,quantities,rangeRows,supplemental,scrap,ods,invoices,inquiries,historical);
  final Context context=new Context("COMMERCIAL","route",1L,"product",YearMonth.of(2026,10),LocalDateTime.of(2026,10,1,0,0),"test","210");
  static <T> T fake(Class<T> type){return java.lang.reflect.Modifier.isFinal(type.getModifiers())?mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE)):mock(type);}
  boolean manual=false;
  List<MaterialPriceSourceType> order=MaterialPriceSourceType.DEFAULT_ORDER;

  @BeforeEach void missingSources() {
    when(historical.fill(any(),any())).thenAnswer(call->call.getArgument(1));
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
    when(invoices.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(inquiries.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
    when(supplemental.read(any(),anySet())).thenAnswer(call->missing(call.getArgument(1)));
  }
  Candidates read(Set<MonthlyPriceRequest> requests){return sources.resolve(context,requests,ignored->{throw new AssertionError("No formula dependency expected");});}
  @Test void missingPlatePricesUseCommercialLinkedPricesAndKeepTheRealSourceEvidence() {
    var requests=new LinkedHashSet<MonthlyPriceRequest>();
    for(String code:List.of("301070077","301070076"))requests.add(inOrganization(request(code),"220"));
    when(originalLinked.read(any(),anySet())).thenAnswer(call->inOrganization(call.getArgument(1),"210","LINKED"));
    var result=read(requests);
    assertThat(result.problems()).isEmpty();
    for(var request:requests) {
      var selected=result.values().get(request).selection();
      assertThat(selected.fact().price()).isEqualByComparingTo("87.168142");
      assertThat(selected.fact().evidence().json()).contains("\"orgCode\":\"210\"");
      assertThat(selected.applicability().matches(request)).isTrue();
      assertThat(selected.applicability().fragments()).allSatisfy(fragment->assertThat(fragment.organizations()).containsExactly("220"));
    }
  }
  @Test void currentOrganizationIsExhaustedBeforeAnotherOrganizationsEarlierSource() {
    var request=inOrganization(request("local-fixed"),"220");
    when(fixed.read(any(),anySet())).thenAnswer(call->inOrganization(call.getArgument(1),"220","PURCHASE_FIXED"));
    when(originalLinked.read(any(),anySet())).thenAnswer(call->inOrganization(call.getArgument(1),"210","LINKED"));
    var result=read(Set.of(request));
    assertThat(result.values().get(request).selection().fact().evidence().json()).contains("PURCHASE_FIXED");
    verify(originalLinked).read(context,Set.of(request));
  }
  @Test void fallbackIsSymmetricAndRetainsTheOriginalFinanceDesignation() {
    manual=true;order=List.of(MaterialPriceSourceType.FIXED,MaterialPriceSourceType.LINKED,MaterialPriceSourceType.RANGE,MaterialPriceSourceType.SETTLE_FIXED,MaterialPriceSourceType.SETTLE_LINKED);
    var request=request("commercial-to-plate");
    when(fixed.read(any(),anySet())).thenAnswer(call->inOrganization(call.getArgument(1),"220","PURCHASE_FIXED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    verifyNoInteractions(linked,originalLinked,settlement,ods,inquiries,supplemental);
  }
  @Test void publicSourceApprovalAndInvalidInputAreRetained() {
    var request=inOrganization(request("waiting-local"),"220");
    for(var reason:List.of(Failure.WAITING,Failure.INVALID_INPUT)) {
      clearInvocations(originalLinked);
      when(linked.read(any(),anySet(),any())).thenAnswer(call->{Set<MonthlyPriceRequest> batch=call.getArgument(1);return batch.contains(request)?new Candidates(Map.of(),Map.of(request,new Problem(reason,"本组织待确认"))):missing(batch);});
      assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(reason);
      verifyNoInteractions(originalLinked);
    }
  }
  @Test void incompleteLocalTechnicalSupplementDoesNotHideAnAvailableCommercialLinkedPrice() {
    var excluded=inOrganization(request("pending-supplement"),"220");var f=excluded.family();
    var included=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),f.materialCode(),f.specification(),f.unit(),f.currency(),TaxBasis.INCLUDED,f.requestedSupplierCode()),"220",excluded.conditions(),BigDecimal.ONE);
    {
      when(supplemental.read(any(),anySet())).thenAnswer(call->{Set<MonthlyPriceRequest> batch=call.getArgument(1);var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();for(var r:batch)problems.put(r,new Problem(Failure.MISSING,r.family().taxBasis()==TaxBasis.INCLUDED?"补录固定价没有适用的含税口径":"未提交技术补录不参与取价"));return new Candidates(Map.of(),problems);});
      when(originalLinked.read(any(),anySet())).thenAnswer(call->{Set<MonthlyPriceRequest> batch=call.getArgument(1);var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();for(var r:batch)if(r.priceOrgCode().equals("210")&&r.family().taxBasis()==TaxBasis.EXCLUDED)values.put(r,orgCandidate("210","LINKED"));else problems.put(r,new Problem(Failure.MISSING,"没有适用联动价"));return new Candidates(values,problems);});
      var result=read(Set.of(included,excluded));
      assertThat(result.values()).containsOnlyKeys(excluded);
      assertThat(result.problems()).doesNotContainKey(excluded);
    }
  }
  @Test void anExistingLocalTaxVariantPreventsAnotherOrganizationFromCreatingTaxAmbiguity() {
    var excluded=inOrganization(request("local-tax"),"220");
    var f=excluded.family();
    var included=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),f.materialCode(),f.specification(),f.unit(),f.currency(),TaxBasis.INCLUDED,f.requestedSupplierCode()),"220",excluded.conditions(),BigDecimal.ONE);
    when(fixed.read(any(),anySet())).thenAnswer(call->{
      Set<MonthlyPriceRequest> batch=call.getArgument(1);
      if(batch.contains(included))return new Candidates(Map.of(included,orgCandidate("220","PURCHASE_FIXED")),Map.of(excluded,new Problem(Failure.MISSING,"没有未税价格")));
      return inOrganization(batch,"210","PURCHASE_FIXED");
    });
    var result=read(Set.of(included,excluded));
    assertThat(result.values()).containsOnlyKeys(included);
    verify(fixed).read(context,Set.of(included,excluded));
  }
  @Test void fallbackKeepsSupplierQuantityMonthAndBusinessUnitAndUsesOnlyTheOtherPriceOrganization() {
    var local=inOrganization(request("scope"),"220");
    var f=local.family();
    var request=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),f.materialCode(),f.specification(),f.unit(),f.currency(),f.taxBasis(),"S1"),"220",new NonCuConditions(new BoardTerms(null,null,true)),new BigDecimal("3"),new BigDecimal("1000"));
    when(originalLinked.read(any(),anySet())).thenAnswer(call->inOrganization(call.getArgument(1),"210","LINKED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    verify(originalLinked).read(eq(context),argThat(batch->batch.stream().allMatch(r->r.priceOrgCode().equals("210") && r.family().equals(request.family()) && r.conditions().boardTerms()==null && r.priceBasisQuantity().compareTo(new BigDecimal("3"))==0 && r.annualUsagePieces().compareTo(new BigDecimal("1000"))==0)));
  }
  static MonthlyPriceRequest inOrganization(MonthlyPriceRequest request,String org) {
    return new MonthlyPriceRequest(request.family(),org,"220".equals(org)?request.conditions():NonCuConditions.empty(),request.priceBasisQuantity(),request.annualUsagePieces());
  }
  static Candidate orgCandidate(String org,String kind) {
    return new Candidate(new Selection(null,new MonthlyPriceCoverage(Set.of(org),QuantityInterval.all(),ConditionDomain.any()),PriceFact.normal(new BigDecimal("87.168142"),SourceEvidence.ofJson("{\"snapshotSchema\":1,\"sourceKind\":\""+kind+"\",\"source\":{\"selected\":{\"orgCode\":\""+org+"\",\"fixedPrice\":87.168142}}}"),Set.of())),null);
  }
  static Candidates inOrganization(Set<MonthlyPriceRequest> requests,String org,String kind) {
    var found=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    for(var request:requests)if(request.priceOrgCode().equals(org))found.put(request,orgCandidate(org,kind));else problems.put(request,new Problem(Failure.MISSING,"本组织没有适用价格"));
    return new Candidates(found,problems);
  }
  @Test void laterEmptySupplementalSourceCannotHideTheActualPrimarySupplierRestriction() {
    var request=request("restricted");
    when(linked.read(any(),eq(Set.of(request)),any())).thenReturn(new Candidates(Map.of(),Map.of(request,
        new Problem(Failure.MISSING,"联动价有记录，但当前主供方没有适用价格"))));
    when(supplemental.read(any(),eq(Set.of(request)))).thenReturn(new Candidates(Map.of(),Map.of(request,
        new Problem(Failure.MISSING,"当前料号没有技术补录办理来源"))));
    assertThat(read(Set.of(request)).problems().get(request).message()).contains("主供方").doesNotContain("技术补录办理来源");
  }
  @Test void noAvailableSourceDoesNotExposeTheLastSupplementalLookupAsTheBusinessCause() {
    var request=request("missing");
    when(supplemental.read(any(),eq(Set.of(request)))).thenReturn(new Candidates(Map.of(),Map.of(request,
        new Problem(Failure.MISSING,"当前料号没有技术补录办理来源"))));
    assertThat(read(Set.of(request)).problems().get(request).message()).contains("未取得可用价格").doesNotContain("技术补录办理来源");
  }
  @Test void aLaterSuccessfulSourceStillWinsAfterAnEarlierMissingSupplierPrice() {
    var request=request("later-success");
    when(linked.read(any(),anySet(),any())).thenReturn(new Candidates(Map.of(),Map.of(request,
        new Problem(Failure.MISSING,"联动价有记录，但当前主供方没有适用价格"))));
    when(fixed.read(any(),anySet())).thenReturn(found(request,"PURCHASE_FIXED"));
    var result=read(Set.of(request));assertThat(result.values()).containsKey(request);assertThat(result.problems()).isEmpty();
  }
  @Test void missingOrUnconfirmedCurrentSupplementalCanUseThePreviouslyCostedPriceOnlyAtTheLastStep() {
    var request=request("previous-supplemental");
    when(supplemental.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.WAITING,"original confirmation changed"))));
    when(historical.fill(eq(context),any())).thenReturn(found(request,"TECH_FIXED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var ordered=inOrder(inquiries,supplemental,historical);
    ordered.verify(inquiries).read(context,Set.of(request));ordered.verify(supplemental).read(context,Set.of(request));
    ordered.verify(historical).fill(eq(context),any());
  }
  @Test void newPublicPriceWinsAndAnInvalidPublicSourceCannotActivateHistoricalSupplemental() {
    var request=request("new-fixed");when(fixed.read(any(),anySet())).thenReturn(found(request,"PURCHASE_FIXED"));
    assertThat(read(Set.of(request)).values()).containsKey(request);verifyNoInteractions(historical,supplemental);
    when(fixed.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.INVALID_INPUT,"supplier ratios missing"))));
    assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);
    verifyNoInteractions(historical,supplemental);
  }
  @Test void inquiryFollowsOrderAndPrecedesSupplementalFixed() {
    var request=request("inquiry");when(inquiries.read(any(),anySet())).thenReturn(found(request,"INQUIRY"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var calls=inOrder(ods,invoices,inquiries);calls.verify(ods).tiers(any(),anySet());calls.verify(invoices).read(any(),anySet());
    calls.verify(ods).orders(any(),anySet());calls.verify(inquiries).read(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void validOrderPreventsInquirySelection() {
    var request=request("order");when(ods.orders(any(),anySet())).thenReturn(found(request,"ODS_ORDER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);verifyNoInteractions(inquiries);
  }
  @Test void missingScrapDependencyMayUseInquiryButInvalidScrapCannotBeHidden() {
    var request=request("scrap-inquiry");when(inquiries.read(any(),anySet())).thenReturn(found(request,"INQUIRY"));
    assertThat(sources.resolveScrap(context,Set.of(request),ignored->{throw new AssertionError();}).values()).containsKey(request);
    clearInvocations(inquiries);
    when(scrap.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.INVALID_INPUT,"bad scrap"))));
    assertThat(sources.resolveScrap(context,Set.of(request),ignored->{throw new AssertionError();}).problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);
    verifyNoInteractions(inquiries);
  }
  @Test void waitingInquiryDoesNotBecomeSupplementalFixed() {
    var request=request("pending-inquiry");when(inquiries.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.WAITING,"waiting base"))));
    assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.WAITING);verifyNoInteractions(supplemental);
  }
  @Test void invoiceSitsAfterTierAndBeforeOrder() {
    var request=request("invoice");when(invoices.read(any(),anySet())).thenReturn(found(request,"INVOICE"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    verify(ods).tiers(context,Set.of(request));verify(ods,never()).orders(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void applicableTierPreventsInvoiceSelection() {
    var request=request("tier");when(ods.tiers(any(),anySet())).thenReturn(found(request,"ODS_TIER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);verifyNoInteractions(invoices);
  }
  @Test void invoiceFailureDoesNotFallThroughToOrder() {
    var request=request("invoice-error");when(invoices.read(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.INVALID_INPUT,"invalid"))));
    assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);verify(ods,never()).orders(any(),anySet());
  }
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
  @Test void defaultOrderThenTierBeforeOrderAndSupplemental() {
    var request=request("ods-tier");when(ods.tiers(any(),anySet())).thenReturn(found(request,"ODS_TIER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var calls=inOrder(linked,settlement,quantities,fixed,ods);
    calls.verify(linked).read(eq(context),eq(Set.of(request)),any());
    calls.verify(settlement).read(context,Map.of(request,com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType.SETTLE_LINKED));
    calls.verify(quantities).read(context,Set.of(request));
    calls.verify(settlement).read(context,Map.of(request,com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType.SETTLE_FIXED));
    calls.verify(fixed).read(context,Set.of(request));calls.verify(ods).tiers(context,Set.of(request));
    verify(ods,never()).orders(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void onlyMissingTierReachesOrderAndOrderMissingReachesSupplemental() {
    var request=request("ods-order");when(ods.orders(any(),anySet())).thenReturn(found(request,"ODS_ORDER"));
    assertThat(read(Set.of(request)).values()).containsKey(request);
    var calls=inOrder(ods);calls.verify(ods).tiers(context,Set.of(request));calls.verify(ods).orders(context,Set.of(request));
    verifyNoInteractions(supplemental);
  }
  @Test void tierSupplierWaitingDoesNotFallThroughToOrderOrSupplemental() {
    var request=request("tier-wait");when(ods.tiers(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.WAITING,"确认选供"))));
    assertThat(read(Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.WAITING);
    verify(ods,never()).orders(any(),anySet());verifyNoInteractions(supplemental);
  }
  @Test void missingAnnualUsageOnlyBlocksWhenTierSourceReportsItsActualNeed() {
    var request=request("needs-demand");when(ods.tiers(any(),anySet())).thenReturn(new Candidates(Map.of(),Map.of(request,new Problem(Failure.INVALID_INPUT,"阶梯取价缺少年用量"))));
    assertThat(read(Set.of(request)).problems().get(request).message()).contains("年用量");
    verify(ods,never()).orders(any(),anySet());verifyNoInteractions(supplemental);
  }
}
