package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalDataReviewTaskResponse(
    TechnicalDataTaskResponse task,
    int pendingCount,
    int passedCount,
    int returnedCount,
    List<Item> reviewItems) {

  public TechnicalDataReviewTaskResponse {
    reviewItems = reviewItems == null ? List.of() : List.copyOf(reviewItems);
  }

  public record Item(
      Long id,
      Long taskId,
      Integer reviewRound,
      Long productId,
      Long submittedVersionId,
      Integer submittedVersionNo,
      String moduleType,
      String decision,
      String decisionReason,
      Long inheritedFromReviewItemId,
      String differenceSnapshotJson,
      String validationSnapshotJson,
      Long decidedBy,
      String decidedByName,
      LocalDateTime decidedAt,
      Integer rowVersion) {}
}
