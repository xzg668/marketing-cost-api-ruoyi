package com.sanhua.marketingcost.service;

import java.util.Set;

/** A new attempt's actual outcome takes precedence over differences from an older success. */
public final class QuoteCostingStateProjection {
  private static final Set<String> ATTEMPT_STATES = Set.of("QUEUED", "RUNNING", "WAIT_BOM",
      "WAIT_TECH_DATA", "WAIT_PRICE_TYPE", "WAIT_PRICE", "SYSTEM_FAILED");
  private QuoteCostingStateProjection() {}
  public static boolean hasCurrentAttemptState(String status) {
    return status != null && ATTEMPT_STATES.contains(status);
  }
}
