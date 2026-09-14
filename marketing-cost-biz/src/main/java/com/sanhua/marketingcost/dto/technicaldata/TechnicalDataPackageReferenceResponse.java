package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataPackageReferenceResponse(
    Long productId,
    String keyword,
    int total,
    List<Candidate> candidates) {

  public TechnicalDataPackageReferenceResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public record Candidate(
      Long sourceProductId,
      Long sourceVersionId,
      Integer sourceVersionNo,
      String materialNo,
      String productName,
      String productModel,
      String validFromMonth,
      String validToMonth,
      String sourceType,
      String contentFingerprint,
      int itemCount,
      List<TechnicalDataPackageResponse.Item> items) {
    public Candidate {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }
}
