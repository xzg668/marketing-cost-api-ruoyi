package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class MonthlyRepriceWorkerTest {
  @Test void independentWorkerRunsSavedTaskWithOnlyItsBusinessUnitAndRestoresCallerContext() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();props.setId("test-worker");
    var task=new CostRunTask();task.setId(7L);task.setSourceNo("MRP1130-test");task.setExecutionNo(3);task.setBusinessUnitType("HOUSEHOLD");
    when(dispatch.claim("test-worker")).thenReturn(task);
    var previous=SecurityContextHolder.getContext();
    doAnswer(invocation->{
      assertThat(BusinessUnitContext.getCurrentBusinessUnitType()).isEqualTo("HOUSEHOLD");
      assertThat(BusinessUnitContext.isAdmin()).isFalse();
      assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).isEmpty();return null;
    }).when(costing).execute("MRP1130-test",7L,3);
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,true))){
      worker.value.poll();assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
      verify(costing).execute("MRP1130-test",7L,3);verify(dispatch,times(2)).checkCompleted();
      verify(dispatch,never()).fail(any(),anyString(),any());
    } finally {SecurityContextHolder.clearContext();}
  }
  @Test void taskFailureUsesLeasedIdentityAndStillRestoresSecurityContext() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();props.setId("test-worker");
    var task=new CostRunTask();task.setId(9L);task.setSourceNo("MRP1130-failed");task.setExecutionNo(2);task.setBusinessUnitType("COMMERCIAL");
    when(dispatch.claim("test-worker")).thenReturn(task);
    var failure=new IllegalStateException("缺资料");when(costing.execute("MRP1130-failed",9L,2)).thenThrow(failure);
    var previous=SecurityContextHolder.getContext();
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,true))){
      worker.value.poll();verify(dispatch).fail(task,"test-worker",failure);assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    } finally {SecurityContextHolder.clearContext();}
  }
  @Test void disablingWorkerNeverClaimsOrChecksSavedBatches() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();props.setEnabled(false);
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,true))){worker.value.poll();}
    verifyNoInteractions(dispatch,costing);
  }
  private record ClosingWorker(MonthlyRepriceWorker value) implements AutoCloseable {
    @Override public void close(){value.close();}
  }
}
