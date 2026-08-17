package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record ElectronicBomVerificationResponse(
    boolean verified,
    String status,
    String message,
    Integer taskVersion,
    String electronicBomFingerprint,
    String sourceVersion,
    int nodeCount,
    String priceScanStatus,
    int priceGapCount,
    List<Issue> issues) {

  public ElectronicBomVerificationResponse {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public record Issue(
      String nodeKey,
      String bomPath,
      String code,
      String message) {}
}
