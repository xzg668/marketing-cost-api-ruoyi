package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

public record QuoteCollaborationHistoryResponse(
    Long itemId,
    Long productTaskId,
    String productTaskNo,
    String currentStatus,
    String currentStatusLabel,
    String assigneeName,
    List<Entry> entries) {
  public QuoteCollaborationHistoryResponse {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  public record Entry(LocalDateTime occurredAt, String type, String title, String description) {}
}
