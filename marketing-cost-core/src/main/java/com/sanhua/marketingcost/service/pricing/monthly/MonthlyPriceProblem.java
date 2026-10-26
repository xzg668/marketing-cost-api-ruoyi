package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.Objects;

/** An operation failure is not a zero-price row or a partially successful adoption. */
public final class MonthlyPriceProblem extends RuntimeException {
  private final MonthlyAdoptionRetryPolicy.Failure reason;
  public MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure reason,String message) {this(reason,message,null);}
  public MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure reason,String message,Throwable cause) {
    super(message,cause);this.reason=Objects.requireNonNull(reason);
  }
  public MonthlyAdoptionRetryPolicy.Failure reason(){return reason;}
}
