package com.sanhua.marketingcost.dto.technicaldata;

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
    Integer rowVersion) {}
