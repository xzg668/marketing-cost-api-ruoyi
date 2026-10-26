package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Action;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MonthlyAdoptionRetryPolicyTest {
  @ParameterizedTest
  @EnumSource(value = Failure.class, names = {"REPLAN_REQUIRED", "TRANSIENT_CONCURRENCY_FAILURE", "COMMIT_OUTCOME_UNKNOWN"})
  void concurrencyFailuresReplanWithinThreeAttemptsAndTenSeconds(Failure failure) {
    var first = MonthlyAdoptionRetryPolicy.afterFailure(failure, 1, Duration.ofSeconds(1));
    assertThat(first.action()).isEqualTo(Action.REPLAN);
    assertThat(first.delay()).isEqualTo(Duration.ofMillis(25));
    assertThat(first.remainingBudget()).isEqualTo(Duration.ofMillis(8975));
    var second = MonthlyAdoptionRetryPolicy.afterFailure(failure, 2, Duration.ofSeconds(4));
    assertThat(second.action()).isEqualTo(Action.REPLAN);
    assertThat(second.delay()).isEqualTo(Duration.ofMillis(100));
    assertThat(second.remainingBudget()).isEqualTo(Duration.ofMillis(5900));
    assertThat(MonthlyAdoptionRetryPolicy.afterFailure(failure, 3, Duration.ofSeconds(5)).action())
        .isEqualTo(Action.STOP);
    assertThat(MonthlyAdoptionRetryPolicy.afterFailure(failure, 1, Duration.ofMillis(9975)).action())
        .isEqualTo(Action.STOP);
    assertThat(MonthlyAdoptionRetryPolicy.afterFailure(failure, 1, Duration.ofSeconds(10)).action())
        .isEqualTo(Action.STOP);
  }

  @ParameterizedTest
  @EnumSource(value = Failure.class, names = {"MISSING", "WAITING", "INVALID_INPUT", "INVALID_PLAN",
      "EVIDENCE_UNAVAILABLE", "PERSISTENCE_INCOMPLETE"})
  void businessAndEvidenceFailuresDoNotStartRepeatedAdoption(Failure failure) {
    var decision = MonthlyAdoptionRetryPolicy.afterFailure(failure, 1, Duration.ZERO);
    assertThat(decision.action()).isEqualTo(Action.STOP);
    assertThat(decision.delay()).isZero();
  }

  @Test
  void rejectsImpossibleAttemptCountersAndNegativeElapsedTime() {
    for (int attempts : new int[] {0, -1, 4}) {
      assertThatThrownBy(() -> MonthlyAdoptionRetryPolicy.afterFailure(
          Failure.REPLAN_REQUIRED, attempts, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> MonthlyAdoptionRetryPolicy.afterFailure(
        Failure.REPLAN_REQUIRED, 1, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
  }
}
