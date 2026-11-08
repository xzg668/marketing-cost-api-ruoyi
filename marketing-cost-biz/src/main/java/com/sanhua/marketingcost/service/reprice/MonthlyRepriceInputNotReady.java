package com.sanhua.marketingcost.service.reprice;

/** Applicable business inputs must be completed before an operator retries this customer. */
public final class MonthlyRepriceInputNotReady extends IllegalStateException {
  private final java.util.List<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue> issues;
  public MonthlyRepriceInputNotReady(String message) { this(message,java.util.List.of()); }
  public MonthlyRepriceInputNotReady(String message,
      java.util.List<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue> issues) {
    super(message);this.issues=java.util.List.copyOf(issues);
  }
  public java.util.List<com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult.Issue> issues(){return issues;}
}
