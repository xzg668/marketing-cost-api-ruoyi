package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.*;
import java.time.Duration;
import java.util.Objects;

/** Each retry reads saved prices before resolving missing source prices again. */
public final class MonthlyAdoptionExecutor {
  @FunctionalInterface public interface Planner {MonthlyAdoptionPlan plan(Duration remainingBudget);}
  private final MonthlyMaterialPrices prices;
  public MonthlyAdoptionExecutor(MonthlyMaterialPrices prices){this.prices=Objects.requireNonNull(prices);}
  public AdoptionResult execute(Context context,Planner planner) {
    Objects.requireNonNull(planner);long started=System.nanoTime();
    for(int attempt=1;attempt<=MonthlyAdoptionRetryPolicy.MAX_ATTEMPTS;attempt++) {
      Duration remaining=MonthlyAdoptionRetryPolicy.OPERATION_BUDGET.minus(elapsed(started));
      if(remaining.isNegative() || remaining.isZero())throw new MonthlyPriceProblem(Failure.TRANSIENT_CONCURRENCY_FAILURE,"Monthly adoption budget exhausted");
      try {
        context.supplierReviews().clear();
        var plan=planner.plan(remaining);
        return prices.adopt(context,plan,MonthlyAdoptionRetryPolicy.OPERATION_BUDGET.minus(elapsed(started)));
      } catch(MonthlyPriceProblem problem) {
        var decision=MonthlyAdoptionRetryPolicy.afterFailure(problem.reason(),attempt,elapsed(started));
        if(decision.action()==Action.STOP)throw problem;
        try{Thread.sleep(decision.delay().toMillis());}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new MonthlyPriceProblem(Failure.TRANSIENT_CONCURRENCY_FAILURE,"Adoption retry interrupted",interrupted);}
      }
    }
    throw new IllegalStateException("Adoption retry limit exceeded");
  }
  private static Duration elapsed(long started){return Duration.ofNanos(System.nanoTime()-started);}
}
