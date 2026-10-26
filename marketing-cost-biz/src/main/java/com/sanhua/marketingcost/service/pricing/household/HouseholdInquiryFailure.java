package com.sanhua.marketingcost.service.pricing.household;

/** Stable API outcome; a rejected publication never reports a partial import as success. */
public class HouseholdInquiryFailure extends RuntimeException {
  private final String code;
  private final int status;
  public HouseholdInquiryFailure(String code,int status,String message) { super(message);this.code=code;this.status=status; }
  public String code() { return code; }
  public int status() { return status; }
}
