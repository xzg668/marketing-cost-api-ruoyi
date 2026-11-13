package com.sanhua.marketingcost.security;
import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
class TechnicalDataPendingSourceReadsTest {
  TechnicalDataPendingSourceReads reads=new TechnicalDataPendingSourceReads();
  @AfterEach void close(){reads.close();QuoteVisibilityContext.clear();SecurityContextHolder.clearContext();}
  void actor(String name,QuoteVisibilityContext.Scope scope,long allowed) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,List.of()));
    QuoteVisibilityContext.bind(scope,id->id==allowed);
  }
  @Test void workersKeepAuthorizationAndInputOrderAcrossDifferentRequests() {
    for(int turn=0;turn<2;turn++) {
      String name="actor"+turn;long allowed=turn+1;var scope=turn==0?QuoteVisibilityContext.Scope.COMMERCIAL:QuoteVisibilityContext.Scope.PLATE;
      actor(name,scope,allowed);
      var result=reads.map(List.of(3,1,2,4),value->{
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(name);
        assertThat(QuoteVisibilityContext.scope()).isEqualTo(scope);assertThat(QuoteVisibilityContext.canAccessForm(allowed)).isTrue();
        assertThat(QuoteVisibilityContext.canAccessForm(9L)).isFalse();return value*2;
      });assertThat(result).containsExactly(6,2,4,8);
      assertThat(QuoteVisibilityContext.scope()).isEqualTo(scope);
    }
  }
  @Test void concurrencyIsBoundedAndMultipleProductsCanProgressTogether() {
    var active=new AtomicInteger();var maximum=new AtomicInteger();var started=new CountDownLatch(4);
    reads.map(java.util.stream.IntStream.range(0,20).boxed().toList(),value->{
      int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);started.countDown();
      try {assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();return value;}
      catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
      finally {active.decrementAndGet();}
    });assertThat(maximum.get()).isEqualTo(4);assertThat(active.get()).isZero();
  }
  @Test void failureWaitsForAllReadsAndDoesNotLeakThePreviousCaller() {
    actor("first",QuoteVisibilityContext.Scope.COMMERCIAL,1);var finished=new AtomicInteger();
    assertThatThrownBy(()->reads.map(List.of(1,2,3),value->{try {if(value==2)throw new IllegalStateException("source failure");return value;}finally{finished.incrementAndGet();}})).hasRootCauseMessage("source failure");
    assertThat(finished.get()).isEqualTo(3);actor("second",QuoteVisibilityContext.Scope.PLATE,2);
    assertThat(reads.map(List.of(1,2),value->SecurityContextHolder.getContext().getAuthentication().getName())).containsOnly("second");
  }
}
