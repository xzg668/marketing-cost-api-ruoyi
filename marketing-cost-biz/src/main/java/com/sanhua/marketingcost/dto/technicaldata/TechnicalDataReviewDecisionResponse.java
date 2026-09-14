package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalDataReviewDecisionResponse(
    Long taskId,
    Long reviewItemId,
    String decision,
    String taskStatus,
    String reviewStatus,
    Integer reviewRound,
    Integer taskVersion,
    int pendingCount,
    int returnedCount,
    boolean roundCompleted,
    boolean effective,
    TechnicalDataReviewTaskResponse detail) {}
