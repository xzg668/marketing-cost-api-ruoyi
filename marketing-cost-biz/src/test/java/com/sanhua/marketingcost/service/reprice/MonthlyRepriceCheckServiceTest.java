package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyRepriceCheckServiceTest {
  final MonthlyRepriceContractMapper batches=mock(MonthlyRepriceContractMapper.class);
  final MonthlyRepriceCheckMapper query=mock(MonthlyRepriceCheckMapper.class);
  final MonthlyCostVersionService costs=mock(MonthlyCostVersionService.class);
  final MonthlyRepriceCheckService checks=new MonthlyRepriceCheckService(batches,query,costs,new ObjectMapper());
  final List<CostRunTask> tasks=new ArrayList<>();
  final Map<String,MonthlyRepriceResult> results=new LinkedHashMap<>();
  final MonthlyRepriceBatch batch=new MonthlyRepriceBatch();

  MonthlyRepriceCheckServiceTest() {
    batch.setId(1L);batch.setRepriceNo("CHECK");batch.setBusinessUnitType("COMMERCIAL");batch.setPricingMonth("2026-11");
    batch.setResultRevision(10L);batch.setSuccessCount(0);batch.setFailedCount(0);batch.setUnresolvedSourceCount(0);
    when(batches.readBatch("CHECK")).thenReturn(batch);
    when(query.tasks(eq("CHECK"),anyLong())).thenAnswer(call->tasks.stream()
        .filter(t->t.getId()>(long)call.getArgument(1)).limit(64).toList());
    when(query.results(eq("CHECK"),anyList())).thenAnswer(call->((List<String>)call.getArgument(1)).stream()
        .map(results::get).filter(Objects::nonNull).toList());
  }

  void pending(int count) {
    batch.setTotalCount(count);
    when(query.counts("CHECK")).thenReturn(new MonthlyRepriceCheckMapper.Counts(count,count,0));
    for(long id=1;id<=count;id++) {
      var t=new CostRunTask();t.setId(id);t.setCalcObjectKey("key-"+id);t.setOaNo("OA-"+id);t.setOaFormItemId(id);
      t.setProductCode("P");t.setBusinessUnitType("COMMERCIAL");t.setPricingMonth("2026-11");t.setStatus("PENDING");
      t.setRequestSnapshotJson("{\"sourceCostVersionId\":1}");tasks.add(t);
      var r=new MonthlyRepriceResult();r.setCalcObjectKey(t.getCalcObjectKey());r.setOaNo(t.getOaNo());r.setOaFormItemId(id);
      r.setProductCode("P");r.setBusinessUnitType("COMMERCIAL");r.setPricingMonth("2026-11");r.setSourceCostVersionId(1L);
      r.setCalcStatus("PENDING");results.put(r.getCalcObjectKey(),r);
    }
  }
  @Test void pagesAllObjectsWithoutLoadingWholeBatchOrTurningMissingCostsIntoZero() {
    pending(130);var report=checks.verify(batch);
    assertThat(report.passed()).isTrue();assertThat(report.objects()).isEqualTo(130);
    assertThat(report.evidence()).containsEntry("uncalculatedObjects",130).containsEntry("pages",3);
    assertThat((List<?>)report.evidence().get("uncalculatedSample")).hasSize(50);
    verify(query).tasks("CHECK",0);verify(query).tasks("CHECK",64);verify(query).tasks("CHECK",128);verify(query).tasks("CHECK",130);
    verify(batches,never()).tasks(anyString());verify(batches,never()).results(anyString());verifyNoInteractions(costs);
    assertThat(results.values()).allSatisfy(r->assertThat(r.getTotalCost()).isNull());
  }
  @Test void unknownCustomersRemainSeparateSourceExclusions() {
    pending(0);batch.setUnresolvedSourceCount(3);
    var report=checks.verify(batch);assertThat(report.passed()).isTrue();
    assertThat(report.evidence()).containsEntry("objects",0).containsEntry("unresolvedSources",3);
  }
  @Test void missingResultAndWrongOwnerAreIntegrityFailures() {
    pending(2);results.remove("key-1");results.get("key-2").setOaNo("WRONG");
    assertThat(checks.verify(batch).passed()).isFalse();
  }
  @Test void pendingObjectWithInventedZeroCostIsRejected() {
    pending(1);results.get("key-1").setTotalCost(java.math.BigDecimal.ZERO);
    assertThat(checks.verify(batch).passed()).isFalse();
  }
  @Test void brokenSourceMembershipIsNotAnOrdinaryMissingCustomer() {
    pending(1);when(query.inconsistentSources(1L)).thenReturn(1L);
    assertThat(checks.verify(batch).problems()).anySatisfy(p->assertThat(p).contains("来源"));
  }
  @Test void revisionChangeDuringPagingCannotPass() {
    pending(1);var newer=new MonthlyRepriceBatch();newer.setResultRevision(11L);when(batches.readBatch("CHECK")).thenReturn(newer);
    assertThat(checks.verify(batch).problems()).anySatisfy(p->assertThat(p).contains("修订"));
  }
  @Test void databaseOutageEscapesForRetryInsteadOfBecomingAStoredBusinessFailure() {
    pending(1);when(query.tasks("CHECK",0)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
    assertThatThrownBy(()->checks.verify(batch)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
  }
  @Test void shutdownInterruptDoesNotPublishAnIncompleteSuccess() {
    pending(1);Thread.currentThread().interrupt();
    try {assertThatThrownBy(()->checks.verify(batch)).isInstanceOf(java.util.concurrent.CancellationException.class);}
    finally {Thread.interrupted();}
    verify(query,never()).tasks(anyString(),anyLong());
  }
}
