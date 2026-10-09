package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SupplierPriceReview(
    String scopeKey, String fingerprint, SupplierPriceScope scope,
    List<Candidate> candidates, String status, String confirmedBy,
    LocalDateTime confirmedAt, String message) {
  public record Candidate(SupplierPriceCandidate price, BigDecimal supplyRatio) {}
}
