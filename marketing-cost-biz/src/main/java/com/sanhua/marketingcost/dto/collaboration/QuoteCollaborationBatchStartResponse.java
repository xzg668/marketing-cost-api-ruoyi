package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record QuoteCollaborationBatchStartResponse(
    int successCount, int failureCount, List<ItemResult> results) {
  public QuoteCollaborationBatchStartResponse {
    results = results == null ? List.of() : List.copyOf(results);
  }

  public record ItemResult(
      Long itemId,
      boolean success,
      String resultAction,
      boolean replay,
      String errorCode,
      String message,
      QuoteItemCollaborationResponse item) {}
}
