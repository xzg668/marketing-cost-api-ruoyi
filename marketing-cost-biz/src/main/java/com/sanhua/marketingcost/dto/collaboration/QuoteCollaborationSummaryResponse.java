package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record QuoteCollaborationSummaryResponse(
    String oaNo, String summaryVersion, List<QuoteItemCollaborationResponse> items) {
  public QuoteCollaborationSummaryResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
