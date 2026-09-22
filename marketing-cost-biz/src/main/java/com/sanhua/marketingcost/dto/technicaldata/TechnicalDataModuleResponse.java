package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataModuleResponse(
    Long id,
    String moduleType,
    boolean required,
    String requirementReasonCode,
    String requirementReason,
    String entryMode,
    String moduleStatus,
    Long currentVersionId,
    int itemCount,
    Integer rowVersion,
    String sourceAvailability,
    String sourceReference,
    LocalDateTime sourceCheckedAt, Long assigneeUserId, String assigneeName) {}
