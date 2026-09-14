package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataTaskSummaryResponse(
    Long id,
    String taskNo,
    String oaNo,
    String accountingMonth,
    String businessUnitType,
    String applicableOrgCode,
    Long assigneeUserId,
    String assigneeName,
    Long reviewerUserId,
    String reviewerName,
    String taskStatus,
    String reviewStatus,
    Integer reviewRound,
    String externalTaskStatus,
    LocalDateTime dueAt,
    LocalDateTime updatedAt) {}
