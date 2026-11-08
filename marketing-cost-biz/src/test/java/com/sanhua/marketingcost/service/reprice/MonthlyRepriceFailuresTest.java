package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceProblem;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class MonthlyRepriceFailuresTest {
  @Test void inputFailureKeepsAllStructuredMaterialDetailsAfterRollback() throws Exception {
    var issues=java.util.stream.IntStream.range(0,300).mapToObj(i->
        new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue(
            "000"+i,"material "+i,"MISSING_PRICE","needs applicable monthly price")).toList();
    var failure=new MonthlyRepriceInputNotReady("missing material prices",issues);
    var decision=MonthlyRepriceFailures.decide(new IllegalStateException("wrapper",failure),null,3);
    assertThat(decision.retry()).isFalse();assertThat(decision.failure().issues()).containsExactlyElementsOf(issues);
    var saved=new ObjectMapper().readTree(decision.json());assertThat(saved.path("issues").size()).isEqualTo(300);
    assertThat(saved.path("issues").get(0).path("materialCode").asText()).isEqualTo("0000");
    assertThat(saved.path("issues").get(299).path("materialCode").asText()).isEqualTo("000299");
  }
  @Test void transientFailuresReceiveThreeDurableBackoffsThenRequireAttention() throws Exception {
    String summary=null;
    for(int retry=1;retry<=4;retry++) {
      var decision=MonthlyRepriceFailures.decide(new CannotAcquireLockException("deadlock"),summary,3);
      assertThat(decision.failure().code()).isEqualTo("TRANSIENT_DATABASE");
      assertThat(decision.retry()).isEqualTo(retry<=3);
      assertThat(decision.delaySeconds()).isEqualTo(retry<=3?1<<retry:0);
      assertThat(decision.failure().automaticRetries()).isEqualTo(Math.min(retry,3));
      assertThat(decision.failure().exhausted()).isEqualTo(retry==4);
      summary="{\"failure\":"+decision.json()+"}";
      assertThat(new ObjectMapper().readTree(summary).path("failure").path("message").asText()).isEqualTo("deadlock");
    }
  }

  @Test void missingAndInvalidBusinessInputsNeverAutomaticallyRetry() {
    for(var reason:new Failure[]{Failure.MISSING,Failure.WAITING,Failure.INVALID_INPUT,Failure.EVIDENCE_UNAVAILABLE,
        Failure.INVALID_PLAN,Failure.PERSISTENCE_INCOMPLETE}) {
      var decision=MonthlyRepriceFailures.decide(new MonthlyPriceProblem(reason,"need applicable input"),null,3);
      assertThat(decision.retry()).isFalse();assertThat(decision.failure().automaticRetries()).isZero();
    }
    assertThat(MonthlyRepriceFailures.decide(new MonthlyRepriceInputNotReady("BOM待确认"),null,3).failure().code())
        .isEqualTo("INPUT_NOT_READY");
  }

  @Test void innerDatabaseFailuresCanRetryButExplicitBusinessFailureTakesPriority() {
    var database=new CannotAcquireLockException("lock");
    assertThat(MonthlyRepriceFailures.decide(new IllegalStateException("wrapper",database),null,3).retry()).isTrue();
    assertThat(MonthlyRepriceFailures.decide(new MonthlyPriceProblem(Failure.INVALID_PLAN,"invalid plan",database),null,3).retry()).isFalse();
    assertThat(MonthlyRepriceFailures.decide(new org.springframework.dao.DataIntegrityViolationException("bad value"),null,3).retry()).isFalse();
  }

  @Test void priceReplanAndSharedLeaseLossAreDistinguished() {
    for(var reason:new Failure[]{Failure.REPLAN_REQUIRED,Failure.TRANSIENT_CONCURRENCY_FAILURE,Failure.COMMIT_OUTCOME_UNKNOWN})
      assertThat(MonthlyRepriceFailures.decide(new MonthlyPriceProblem(reason,"replan"),null,3).failure().code()).isEqualTo("PRICE_REPLAN");
    assertThat(MonthlyRepriceFailures.decide(new MonthlyRepriceSharedNumbers.GroupBusy("busy",null),null,3).failure().code())
        .isEqualTo("SHARED_GROUP_BUSY");
    assertThat(MonthlyRepriceFailures.decide(new MonthlyRepriceGroupLeases.LeaseLost(),null,3).failure().code())
        .isEqualTo("GROUP_LEASE_LOST");
  }

  @Test void driverConnectionAndRollbackStatesRetryWithoutRetryingSyntaxOrAuthorizationErrors() {
    for(String state:new String[]{"08S01","08006","40001"})
      assertThat(MonthlyRepriceFailures.decide(new java.sql.SQLException("driver failure",state),null,3).retry())
          .as(state).isTrue();
    for(int code:new int[]{1205,1213})
      assertThat(MonthlyRepriceFailures.decide(new java.sql.SQLException("mysql lock", "HY000",code),null,3).retry()).isTrue();
    for(String state:new String[]{"28000","42000","23000"})
      assertThat(MonthlyRepriceFailures.decide(new java.sql.SQLException("permanent error",state),null,3).retry()).isFalse();
  }

  @Test void configuredLimitsCannotProduceUnlimitedRetries() {
    var error=new CannotAcquireLockException("lock");
    assertThat(MonthlyRepriceFailures.decide(error,null,0).retry()).isFalse();
    assertThat(MonthlyRepriceFailures.decide(error,"{\"failure\":{\"automaticRetries\":3}}",1000).retry()).isFalse();
    assertThat(MonthlyRepriceFailures.decide(error,null,null).retry()).isTrue();
  }

  @Test void malformedRetryHistoryFailsClosedInsteadOfStartingAnotherRetrySeries() {
    for(String invalid:new String[]{"{","null","[]","{\"failure\":{\"automaticRetries\":-1}}","{\"failure\":{\"automaticRetries\":999}}"}) {
      var decision=MonthlyRepriceFailures.decide(new CannotAcquireLockException("lock"),invalid,3);
      assertThat(decision.retry()).isFalse();assertThat(decision.failure().code()).isEqualTo("RETRY_STATE_INVALID");
    }
  }

  @Test void boundedMessagesRemainValidUnicodeAndHaveAnActionableFallback() {
    assertThat(MonthlyRepriceFailures.decide(new IllegalStateException(),null,3).failure().message()).contains("请检查资料");
    String message="a".repeat(999)+"😀";
    var saved=MonthlyRepriceFailures.decide(new IllegalStateException(message),null,3).failure().message();
    assertThat(saved).hasSizeLessThanOrEqualTo(1000);
    assertThat(Character.isHighSurrogate(saved.charAt(saved.length()-1))).isFalse();
  }
}
