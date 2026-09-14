package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataWorkbenchRowResponse(
    Long taskId,
    String taskNo,
    String oaNo,
    String accountingMonth,
    Long assigneeUserId,
    String assigneeName,
    String taskStatus,
    Integer taskVersion,
    Integer reviewRound,
    LocalDateTime dueAt,
    TechnicalDataProductResponse product) {}
