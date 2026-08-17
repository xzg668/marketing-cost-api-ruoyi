package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalTaskListResponse(int total, List<Item> items) {
  public TechnicalTaskListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long taskId,
      String taskNo,
      String productCode,
      String productName,
      String productSpec,
      String productModel,
      String primaryScope,
      String primaryScopeLabel,
      String status,
      String statusLabel,
      int openGapCount,
      String nextAction,
      String nextActionLabel,
      boolean editable,
      Integer taskVersion,
      LocalDateTime updatedAt) {}
}
