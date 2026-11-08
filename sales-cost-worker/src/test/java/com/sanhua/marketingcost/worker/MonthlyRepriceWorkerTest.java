package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceDispatchService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceInstance;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class MonthlyRepriceWorkerTest {
  @Test void reusedExecutionThreadHasOnlyTheCurrentCustomersBusinessUnit() throws Exception {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();var instance=new MonthlyRepriceInstance("test-worker");
    var a=task(7,"HOUSEHOLD",instance);var b=task(8,"COMMERCIAL",instance);var done=new CountDownLatch(2);
    when(dispatch.claimBatch(eq(instance.value()),anyInt())).thenReturn(List.of(a),List.of(b),List.of());
    var previous=SecurityContextHolder.getContext();
    doAnswer(invocation->{
      var lease=invocation.getArgument(0,MonthlyRepriceCostingService.Lease.class);
      assertThat(BusinessUnitContext.getCurrentBusinessUnitType()).isEqualTo(lease.taskId()==7?"HOUSEHOLD":"COMMERCIAL");
      assertThat(BusinessUnitContext.isAdmin()).isFalse();
      assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).isEmpty();done.countDown();return null;
    }).when(costing).execute(any());
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,instance,new MonthlyRepriceWorkerSettings(),mock(MonthlyRepriceWorkerResources.class)))) {
      worker.value.start();assertThat(done.await(3,TimeUnit.SECONDS)).isTrue();
      assertThat(SecurityContextHolder.getContext()).isSameAs(previous);verify(dispatch,never()).fail(any(),anyString(),any());
    } finally {SecurityContextHolder.clearContext();}
  }

  @Test void taskFailureUsesLeasedIdentityAndDoesNotStopNextCustomer() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();var instance=new MonthlyRepriceInstance("test-worker");
    var task=task(9,"COMMERCIAL",instance);var next=task(10,"HOUSEHOLD",instance);
    when(dispatch.claimBatch(eq(instance.value()),anyInt())).thenReturn(List.of(task),List.of(next),List.of());
    var failure=new IllegalStateException("缺资料");when(costing.execute(MonthlyRepriceCostingService.Lease.from(task))).thenThrow(failure);
    var previous=SecurityContextHolder.getContext();
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,instance,new MonthlyRepriceWorkerSettings(),mock(MonthlyRepriceWorkerResources.class)))) {
      worker.value.start();verify(costing,timeout(3000)).execute(MonthlyRepriceCostingService.Lease.from(next));
      verify(dispatch).fail(task,instance.value(),failure);assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    } finally {SecurityContextHolder.clearContext();}
  }

  @Test void eitherWorkerDisableSwitchPreventsClaimsAndChecks() {
    for(boolean global:new boolean[]{true,false}) {
      var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
      var props=new CostRunWorkerProperties();props.setEnabled(global);var settings=new MonthlyRepriceWorkerSettings();settings.setWorkerEnabled(!global);
      try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,new MonthlyRepriceInstance("disabled"),settings,mock(MonthlyRepriceWorkerResources.class)))) {worker.value.start();}
      verifyNoInteractions(dispatch,costing);
    }
  }

  @Test void availableWorkContinuesWithoutWaitingForAnotherSchedulerTick() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var props=new CostRunWorkerProperties();var instance=new MonthlyRepriceInstance("continuous");
    var settings=new MonthlyRepriceWorkerSettings();settings.setIdleBackoffInitialMs(60000);settings.setPollIntervalMs(60000);
    when(dispatch.claimBatch(eq(instance.value()),anyInt())).thenReturn(List.of(task(1,"COMMERCIAL",instance)),
        List.of(task(2,"COMMERCIAL",instance)),List.of(task(3,"COMMERCIAL",instance)),List.of(task(4,"COMMERCIAL",instance)),List.of());
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,instance,settings,mock(MonthlyRepriceWorkerResources.class)))) {
      worker.value.start();verify(costing,timeout(1500).times(4)).execute(any());
    }
  }

  @Test void insufficientPoolCapacityPreventsAnyLeaseBeforeStartup() {
    var dispatch=mock(MonthlyRepriceDispatchService.class);var costing=mock(MonthlyRepriceCostingService.class);
    var resources=mock(MonthlyRepriceWorkerResources.class);var props=new CostRunWorkerProperties();
    var instance=new MonthlyRepriceInstance("invalid-capacity");var settings=new MonthlyRepriceWorkerSettings();
    doThrow(new IllegalArgumentException("连接池不足")).when(resources).verify(instance.value(),1,props);
    try(var worker=new ClosingWorker(new MonthlyRepriceWorker(dispatch,costing,props,instance,settings,resources))) {
      assertThatThrownBy(worker.value::start).hasMessageContaining("连接池不足");
      verifyNoInteractions(dispatch,costing);
    }
  }

  private CostRunTask task(long id,String unit,MonthlyRepriceInstance instance) {
    var task=new CostRunTask();task.setId(id);task.setSourceNo("MRP1130-worker-test");task.setExecutionNo(3);
    task.setBusinessUnitType(unit);task.setWorkerId(instance.value());return task;
  }
  private record ClosingWorker(MonthlyRepriceWorker value) implements AutoCloseable {
    @Override public void close(){value.close();}
  }
}
