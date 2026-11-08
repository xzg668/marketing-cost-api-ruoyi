package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class MonthlyRepriceSupplementalPriceSourceTest {
  final MonthlyPriceStorage storage=mock(MonthlyPriceStorage.class);
  final MonthlyRepriceBomReadScope scope=mock(MonthlyRepriceBomReadScope.class);
  final MonthlyPriceDocumentCodec codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
  final MonthlyRepriceSupplementalPriceSource source=new MonthlyRepriceSupplementalPriceSource(storage,scope,codec);
  final Context context=new Context("COMMERCIAL","OA",1L,"PRODUCT",YearMonth.of(2026,12),LocalDateTime.of(2026,12,1,0,0),"test","210");
  final MonthlyPriceRequest request=request("件",TaxBasis.EXCLUDED,"210",BigDecimal.ONE);
  @BeforeEach void activeReprice() {when(scope.priceContext(context)).thenReturn(true);}
  MonthlyPriceRequest request(String unit,TaxBasis tax,String org,BigDecimal quantity) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(),"COMMERCIAL","MATERIAL",null,unit,"CNY",tax,null),org,NonCuConditions.empty(),quantity);
  }
  MonthlyPriceStorage.StoredPrice previous(long id,String month,String amount) {
    var family=new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL","MATERIAL",null,"件","CNY",TaxBasis.EXCLUDED,null);
    var applicability=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()));
    return new MonthlyPriceStorage.StoredPrice(id,family,applicability,PricingType.NORMAL,new BigDecimal(amount),null,
        SourceEvidence.ofJson("{\"snapshotSchema\":1,\"sourceKind\":\"TECH_FIXED\",\"source\":{\"selected\":{\"id\":154,\"fixedPrice\":"+amount+"}},\"invoiceRevision\":8}"),null,"{}");
  }
  Candidates blocked(Failure reason) {return new Candidates(Map.of(),Map.of(request,new Problem(reason,"current source changed")));}
  @Test void previousSuccessfulSupplementalPriceSurvivesLaterSourceVersionAndConfirmationChanges() {
    var old=previous(66,"2026-11","80.38566265");
    when(storage.previousSupplemental(anyString(),any(),anyCollection())).thenReturn(List.of(old));
    for(var reason:List.of(Failure.MISSING,Failure.WAITING)) {
      var result=source.fill(context,blocked(reason));
      assertThat(result.problems()).isEmpty();var selected=result.values().get(request).selection();
      assertThat(selected.monthlyPriceId()).as("new month requires its own adopted row").isNull();
      assertThat(selected.fact().price()).isEqualByComparingTo("80.38566265");
      var evidence=codec.decode(codec.wrap(selected.fact().evidence().json()),JsonNode.class);
      assertThat(evidence.path("carriedSupplemental").path("monthlyPriceId").asLong()).isEqualTo(66);
      assertThat(evidence.path("carriedSupplemental").path("month").asText()).isEqualTo("2026-11");
      assertThat(evidence.path("invoiceRevision").asLong()).isZero();
      assertThat(old.evidence().json()).doesNotContain("carriedSupplemental");
    }
  }
  @Test void currentApplicablePriceAlwaysWinsWithoutReadingHistory() {
    var current=new Candidate(new Selection(null,previous(1,"2026-11","7").applicability(),PriceFact.normal(new BigDecimal("9"),SourceEvidence.ofJson("{\"sourceKind\":\"PURCHASE_FIXED\"}"),Set.of())),null);
    var found=new Candidates(Map.of(request,current),Map.of());
    assertThat(source.fill(context,found)).isSameAs(found);verifyNoInteractions(storage);
  }
  @Test void ordinaryQuotationKeepsItsExistingConfirmationChecks() {
    when(scope.priceContext(context)).thenReturn(false);var current=blocked(Failure.WAITING);
    assertThat(source.fill(context,current)).isSameAs(current);verifyNoInteractions(storage);
  }
  @Test void nearestEarlierMatchingMonthWinsAndCurrentOrFutureMonthCannotBeBorrowed() {
    when(storage.previousSupplemental(anyString(),any(),anyCollection())).thenReturn(List.of(
        previous(20,"2026-10","6"),previous(99,"2027-01","99"),previous(30,"2026-11","7"),previous(98,"2026-12","98")));
    assertThat(source.fill(context,blocked(Failure.MISSING)).values().get(request).selection().fact().price()).isEqualByComparingTo("7");
  }
  @Test void unitTaxAndOrganizationMustRemainApplicable() {
    when(storage.previousSupplemental(anyString(),any(),anyCollection())).thenReturn(List.of(previous(66,"2026-11","7")));
    for(var other:List.of(request("千克",TaxBasis.EXCLUDED,"210",BigDecimal.ONE),request("件",TaxBasis.INCLUDED,"210",BigDecimal.ONE),request("件",TaxBasis.EXCLUDED,"220",BigDecimal.ONE))) {
      var result=source.fill(context,new Candidates(Map.of(),Map.of(other,new Problem(Failure.MISSING,"missing"))));
      assertThat(result.values()).isEmpty();assertThat(result.problems()).containsKey(other);
    }
  }
  @Test void invalidCurrentDataCannotBeConcealedByOldSupplementalPrices() {
    var current=blocked(Failure.INVALID_INPUT);assertThat(source.fill(context,current)).isSameAs(current);verifyNoInteractions(storage);
  }
  @Test void absentHistoricalPriceRemainsMissingInsteadOfZero() {
    when(storage.previousSupplemental(anyString(),any(),anyCollection())).thenReturn(List.of());
    var result=source.fill(context,blocked(Failure.MISSING));assertThat(result.values()).isEmpty();assertThat(result.problems()).containsKey(request);
  }
}
