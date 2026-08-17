package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

public record QuoteCollaborationBatchStartRequest(List<Item> items) {
  public QuoteCollaborationBatchStartRequest {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(Long itemId, Long technicianUserId, String expectedProjectionVersion) {}
}
