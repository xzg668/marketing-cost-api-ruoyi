package com.sanhua.marketingcost.service.pricing.monthly;

import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyOriginalLinkedPriceSourceIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceMonthlyPriceRefreshService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class FinanceMonthlyPriceRefreshIntegrationTest {
  @BeforeAll static void start() throws Exception { database(); }
  @AfterAll static void finish() { stop(); }

  @Test void sharedSavedCostReadsItsOriginalMonthlyFactAfterActualFinanceCorrection() {
    String month="2065-01", material=prepare(month);
    var old=adopt(material,month);
    var request=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var amount=old.fact().price();
    var result=new com.sanhua.marketingcost.service.costing.MonthlyProductCost.Result("MONTHLY_PRODUCT_COST_V1","fixture-policy",
        null,null,List.of(new com.sanhua.marketingcost.service.costing.MonthlyProductCost.AdoptedPrice(request,old)),
        List.of(new Use("P/M",request,java.math.BigDecimal.ONE)),Set.of(),List.of(),List.of(),amount,
        java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,amount,java.math.BigDecimal.ZERO,amount,null);
    var snapshot=new com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Snapshot(1,"COMMERCIAL",1,"COST","OA",1,"P",month,result,List.of(),Map.of());
    var documents=new com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments(codec,operation);
    String stored=codec.wrap(documents.write(snapshot,true));
    transaction.executeWithoutResult(status->correction(month));
    var current=adopt(material,month);
    assertThat(current.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(current.fact().price()).isEqualByComparingTo("115");
    assertThat(documents.read("COMMERCIAL",stored)).isEqualTo(snapshot);
    assertThat(operation.lookup(Set.of(request)).values().values()).containsExactly(current);
  }

  private static Selection candidate(String material,String month) {
    var request=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    return operation.resolveCandidates(context("COMMERCIAL",month,"210"),Set.of(request)).values().get(request).selection();
  }
  private static Selection adopt(String material,String month) {
    var request=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    return operation.adopt(context("COMMERCIAL",month,"210"),plan(request,candidate(material,month))).plan().selections().get(request);
  }
  private static long correction(String month) {
    long batch=publication("COMMERCIAL",month,"210",2);
    jdbc.update("UPDATE lp_factor_monthly_price SET revision_no=2,price=110 WHERE source_upload_batch_id=?",batch);
    effective.generateScope("COMMERCIAL","210");
    new FinanceMonthlyPriceRefreshService(jdbc,codec).refresh(new FinanceFactorImportContext("COMMERCIAL","210",month,null,null),batch);
    return batch;
  }
  private static String prepare(String month) {
    var material=code();publication("COMMERCIAL",month,"210");
    rule(material,"COMMERCIAL",month,"210","S1","[cu]+5",false);publish("COMMERCIAL","210");
    return material;
  }

  @Test void pendingCandidateAndPreviouslySavedPlanCannotReintroduceOldFinancialPrices() {
    String month="2061-01",material=prepare(month),unsaved=code();
    rule(unsaved,"COMMERCIAL",month,"210","S1","[cu]+5",false);publish("COMMERCIAL","210");
    var stale=candidate(unsaved,month);var old=adopt(material,month);
    transaction.executeWithoutResult(status->correction(month));
    var r=request(unsaved,"COMMERCIAL",month,"210",NonCuConditions.empty());
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",month,"210"),plan(r,stale)))
        .isInstanceOf(MonthlyPriceProblem.class).hasMessageContaining("财务基价");
    var savedRequest=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",month,"210"),plan(savedRequest,old)))
        .isInstanceOf(MonthlyPriceProblem.class).hasMessageContaining("已采用月价");
    assertThat(adopt(unsaved,month).fact().price()).isEqualByComparingTo("115");
    assertThat(adopt(material,month).fact().price()).isEqualByComparingTo("115");
  }

  @Test void aLaterPublishedMonthAndAnUnrelatedOrganizationKeepTheirSavedPrices() {
    String month="2062-01",next="2062-02",material=prepare(month);
    publication("COMMERCIAL",next,"210");publish("COMMERCIAL","210");
    var old=adopt(material,month);var later=adopt(material,next);
    var plate=code();publication("COMMERCIAL",month,"220");
    rule(plate,"COMMERCIAL",month,"220","S1","[cu]+5",false);publish("COMMERCIAL","220");
    var rp=request(plate,"COMMERCIAL",month,"220",NonCuConditions.empty());
    var cp=context("COMMERCIAL",month,"220");
    var plateOld=operation.adopt(cp,plan(rp,operation.resolveCandidates(cp,Set.of(rp)).values().get(rp).selection())).plan().selections().get(rp);
    transaction.executeWithoutResult(status->correction(month));
    assertThat(adopt(material,month).monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(adopt(material,next)).isEqualTo(later);
    assertThat(operation.lookup(Set.of(rp)).values().get(rp)).isEqualTo(plateOld);
  }

  @Test void preparedSavedPricesReplanAgainstFinanceCorrectionWithoutPublishingTheOldFact() {
    String month="2064-01",material=prepare(month);var old=adopt(material,month);
    var reader=new MonthlyQuotationPrices(operation,new HouseholdMonthlyPriceSource(
        org.mockito.Mockito.mock(HouseholdInquiryStorage.class),codec,
        org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader.class)));
    var context=context("COMMERCIAL",month,"210");
    var item=new MonthlyQuotationPrices.Item(material,"210","件","SPEC",TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),java.math.BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
    var prepared=reader.prepareBatch(List.of(new MonthlyQuotationPrices.Demand(context,List.of(item)))).getFirst();
    assertThat(prepared.batch().values().get(item).candidate().selection()).isEqualTo(old);
    transaction.executeWithoutResult(status -> correction(month));
    var attempts=new java.util.concurrent.atomic.AtomicInteger();
    var updated=reader.usePrepared(prepared, () -> new MonthlyAdoptionExecutor(operation).execute(context,budget -> {
      attempts.incrementAndGet();
      var bound=reader.read(context,List.of(item)).values().get(item);
      return plan(bound.request(),bound.candidate().selection());
    }));
    assertThat(attempts.get()).isEqualTo(2);
    var saved=updated.plan().selections().values().iterator().next();
    assertThat(saved.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(saved.fact().price()).isEqualByComparingTo("115");
    assertThat(operation.readByIds("COMMERCIAL",List.of(old.monthlyPriceId())).get(old.monthlyPriceId())).isEqualTo(old);
    assertThat(operation.lookup(Set.of(request(material,"COMMERCIAL",month,"210",NonCuConditions.empty()))).values().values())
        .containsExactly(saved);
  }

  @Test void publicationLockMakesConcurrentAdoptionReplanAfterTheCorrectionCommits() throws Exception {
    String month="2063-01",material=prepare(month);var stale=candidate(material,month);
    var locked=new CountDownLatch(1);var finish=new CountDownLatch(1);
    try(var workers=Executors.newFixedThreadPool(2)) {
      var publication=workers.submit(()->transaction.executeWithoutResult(status->{
        jdbc.queryForList("SELECT id FROM lp_factor_identity WHERE business_unit_type='COMMERCIAL' ORDER BY id FOR UPDATE");
        correction(month);locked.countDown();
        try { if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test latch timeout"); }
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
      }));
      assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
      var attempt=workers.submit(()->{
        var r=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
        return operation.adopt(context("COMMERCIAL",month,"210"),plan(r,stale));
      });
      try { assertThatThrownBy(()->attempt.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
      finally { finish.countDown(); }
      publication.get(5,TimeUnit.SECONDS);
      assertThatThrownBy(()->attempt.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(MonthlyPriceProblem.class);
      assertThat(adopt(material,month).fact().price()).isEqualByComparingTo("115");
    } finally { finish.countDown(); }
  }
}
