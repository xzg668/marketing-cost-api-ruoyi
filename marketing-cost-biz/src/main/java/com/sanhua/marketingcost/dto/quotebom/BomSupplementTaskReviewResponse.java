package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

public record BomSupplementTaskReviewResponse(
    Long taskId,
    String taskStatus,
    Long preparationRecordId,
    String preparationStatus,
    String reviewStatus,
    String supplementVersionStatus,
    String packageReferenceStatus,
    LocalDateTime reviewedAt) {}
