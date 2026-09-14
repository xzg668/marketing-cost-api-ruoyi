package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataAuxiliaryReferenceResponse(
    Long productId,
    String keyword,
    int total,
    List<Candidate> candidates) {

  public TechnicalDataAuxiliaryReferenceResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public record Candidate(
      String sourceType,
      String sourceId,
      String sourceVersion,
      Long sourceProductId,
      Long sourceVersionId,
      String materialNo,
      String productName,
      String productModel,
      String validFromMonth,
      String validToMonth,
      String contentFingerprint,
      BigDecimal totalAmount,
      int itemCount,
      List<TechnicalDataAuxiliaryResponse.Item> items) {
    public Candidate {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }
}
