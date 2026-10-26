package com.sanhua.marketingcost.service.pricing.monthly;

import java.time.Duration;
import java.util.Objects;

/** Pure failure classification; transactions and rereading adopted prices belong to the adapter. */
public final class MonthlyAdoptionRetryPolicy {
  public static final int MAX_ATTEMPTS = 3;
  public static final Duration OPERATION_BUDGET = Duration.ofSeconds(10);
  public static final Duration TRANSACTION_BUDGET = Duration.ofSeconds(3);

  private MonthlyAdoptionRetryPolicy() {}

  public enum Failure {
    MISSING,
    WAITING,
    INVALID_INPUT,
    INVALID_PLAN,
    EVIDENCE_UNAVAILABLE,
    REPLAN_REQUIRED,
    TRANSIENT_CONCURRENCY_FAILURE,
    COMMIT_OUTCOME_UNKNOWN,
    PERSISTENCE_INCOMPLETE
  }

  public enum Action { STOP, REPLAN }

  public record Decision(Action action, Duration delay, Duration remainingBudget) {}

  public static Decision afterFailure(Failure failure, int completedAttempts, Duration elapsed) {
    Objects.requireNonNull(failure, "failure");
    Objects.requireNonNull(elapsed, "elapsed");
    if (completedAttempts < 1 || completedAttempts > MAX_ATTEMPTS || elapsed.isNegative()) {
      throw new IllegalArgumentException("Invalid adoption attempt counter or elapsed time");
    }
    Duration remaining = OPERATION_BUDGET.minus(elapsed);
    if (remaining.isNegative() || remaining.isZero()) {
      return new Decision(Action.STOP, Duration.ZERO, Duration.ZERO);
    }
    boolean canReplan = failure == Failure.REPLAN_REQUIRED
        || failure == Failure.TRANSIENT_CONCURRENCY_FAILURE
        || failure == Failure.COMMIT_OUTCOME_UNKNOWN;
    if (!canReplan || completedAttempts == MAX_ATTEMPTS) {
      return new Decision(Action.STOP, Duration.ZERO, remaining);
    }
    Duration delay = Duration.ofMillis(completedAttempts == 1 ? 25 : 100);
    if (remaining.compareTo(delay) <= 0) {
      return new Decision(Action.STOP, Duration.ZERO, remaining);
    }
    return new Decision(Action.REPLAN, delay, remaining.minus(delay));
  }
}
