package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalDataTaskResponse(
    Long id,
    String taskNo,
    Long oaFormId,
    String oaNo,
    String accountingMonth,
    String businessUnitType,
    String applicableOrgCode,
    Long assigneeUserId,
    String assigneeName,
    String taskStatus,
    Integer taskVersion,
    Integer reviewRound,
    String reviewStatus,
    String sourceSystem,
    String sourceRequestId,
    String externalSystem,
    String externalTaskId,
    String externalTaskStatus,
    Long externalCallbackSeq,
    LocalDateTime externalLastSyncAt,
    Integer externalRetryCount,
    LocalDateTime externalNextRetryAt,
    String externalLastError,
    LocalDateTime dueAt,
    String submissionFingerprint,
    LocalDateTime submittedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<TechnicalDataProductResponse> products) {}
