package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.CostRunTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonthlyRepriceQueueTest {
  @Test void slowCheckDoesNotBlockClaimsOrAccumulateMoreChecks() throws Exception {
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
    var checking=new AtomicInteger();var calls=new AtomicInteger();
    try(var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(1,1,10,20,5,5),limit->{
      if(entered.getCount()!=0 || calls.getAndIncrement()!=0)return List.of();
      var task=new CostRunTask();task.setId(1L);return List.of(task);
    },task->done.countDown(),()->{
      checking.incrementAndGet();entered.countDown();
      try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();}
      catch(InterruptedException failure){throw new IllegalStateException(failure);}
    })) {
      queue.start();
      try {
        assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(done.await(1,TimeUnit.SECONDS)).as("claim while the checker is still busy").isTrue();
        assertThat(checking).hasValue(1);
      } finally {release.countDown();}
    }
  }

  @Test void drainsWorkContinuouslyWithinConcurrencyAndClaimLimits() throws Exception {
    exerciseDrain(2, 8);
    exerciseDrain(3, 1);
  }

  private void exerciseDrain(int concurrency, int claimLimit) throws Exception {
    var source = new ConcurrentLinkedQueue<CostRunTask>();
    for (long i=1;i<=12;i++) {var task=new CostRunTask();task.setId(i);source.add(task);}
    var entered=new CountDownLatch(concurrency);var release=new CountDownLatch(1);var done=new CountDownLatch(12);
    var active=new AtomicInteger();var maximum=new AtomicInteger();var largestClaim=new AtomicInteger();
    var seen=ConcurrentHashMap.<Long>newKeySet();
    var settings=new MonthlyRepriceQueue.Settings(concurrency,claimLimit,60000,60000,60000,5);
    try(var queue=new MonthlyRepriceQueue(settings,limit->{
      largestClaim.accumulateAndGet(limit,Math::max);var claimed=new ArrayList<CostRunTask>();
      for(int i=0;i<limit;i++){var task=source.poll();if(task==null)break;claimed.add(task);}return claimed;
    },task->{
      maximum.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
      try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();assertThat(seen.add(task.getId())).isTrue();}
      catch(InterruptedException e){throw new IllegalStateException(e);}
      finally {active.decrementAndGet();done.countDown();}
    },()->{})) {
      queue.start();queue.start();
      try {assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();}
      finally {release.countDown();}
      assertThat(done.await(3,TimeUnit.SECONDS)).as("Work must not wait for the60s idle timer").isTrue();
    }
    assertThat(maximum.get()).isEqualTo(concurrency);assertThat(largestClaim.get()).isLessThanOrEqualTo(Math.min(concurrency,claimLimit));
    assertThat(seen).hasSize(12);assertThat(source).isEmpty();
  }

  @Test void completedBatchesAreCheckedOnTimeEvenWithALongIdleQueueBackoff() throws Exception {
    var checks=new CountDownLatch(3);var claims=new AtomicInteger();
    try(var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(1,1,60000,60000,25,5),
        limit->{claims.incrementAndGet();return List.of();},task->{},checks::countDown)) {
      queue.start();assertThat(checks.await(1,TimeUnit.SECONDS)).as("25ms checks must not wait for60s idle backoff").isTrue();
      assertThat(claims.get()).as("Checking an idle batch must not defeat the configured queue backoff").isEqualTo(1);
    }
  }

  @Test void emptyQueueBacksOffInsteadOfBusyPolling() throws Exception {
    var calls=new AtomicInteger();var first=new CountDownLatch(1);
    try(var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(1,1,50,200,1000,5),limit->{
      calls.incrementAndGet();first.countDown();return List.of();
    },task->{throw new AssertionError("no work");},()->{})) {
      queue.start();assertThat(first.await(3,TimeUnit.SECONDS)).isTrue();
      Thread.sleep(160);assertThat(calls.get()).isBetween(1,4);
    }
  }

  @Test void stoppingWaitsForClaimedWorkAndDoesNotClaimMore() throws Exception {
    var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    var task=new CostRunTask();task.setId(1L);
    var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(1,1,20,1000,1000,5),limit->{
      calls.incrementAndGet();return List.of(task);
    },ignored->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}},()->{});
    queue.start();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
    try(var closer=Executors.newSingleThreadExecutor()) {
      var closing=closer.submit(queue::close);
      try {assertThatThrownBy(()->closing.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);assertThat(calls.get()).isEqualTo(1);}
      finally {release.countDown();}
      closing.get(3,TimeUnit.SECONDS);assertThat(calls.get()).isEqualTo(1);
    } finally {release.countDown();queue.close();}
  }

  @Test void oneTaskFailureDoesNotStrandOtherClaimedWork() throws Exception {
    var claims=new AtomicInteger();var done=new CountDownLatch(2);
    try(var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(2,2,20,1000,1000,5),limit->{
      if(claims.getAndIncrement()>0)return List.of();
      var a=new CostRunTask();a.setId(1L);var b=new CostRunTask();b.setId(2L);return List.of(a,b);
    },task->{done.countDown();if(task.getId()==1L)throw new IllegalStateException("controlled persistence failure");},()->{})) {
      queue.start();assertThat(done.await(3,TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test void closeBeforeStartupNeverClaims() {
    var calls=new AtomicInteger();var queue=new MonthlyRepriceQueue(new MonthlyRepriceQueue.Settings(1,1,20,1000,1000,5),
        limit->{calls.incrementAndGet();return List.of();},task->{},()->{});
    queue.close();queue.start();queue.close();assertThat(calls.get()).isZero();
  }

  @Test void invalidResourceLimitsAreRejectedInsteadOfOpeningUnboundedExecutors() {
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(0,1,1,1000,1000,5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(17,1,1,1000,1000,5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(1,65,1,1000,1000,5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(1,1,0,1000,1000,5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(1,1,1001,1000,1000,5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceQueue.Settings(1,1,1,1000,1000,0)).isInstanceOf(IllegalArgumentException.class);
  }
}
