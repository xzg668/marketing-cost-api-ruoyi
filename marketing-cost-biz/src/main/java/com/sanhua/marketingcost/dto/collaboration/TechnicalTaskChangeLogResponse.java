package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalTaskChangeLogResponse(Long taskId, List<Entry> entries) {
  public TechnicalTaskChangeLogResponse {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  public record Entry(
      LocalDateTime occurredAt, String eventType, String title, String description) {}
}
