package com.sanhua.marketingcost.dto.quotebom;

public record BomSupplementCollaborationSaveResponse(
    Long taskId,
    String taskStatus,
    Long preparationRecordId,
    String preparationStatus,
    String reviewStatus,
    Long supplementVersionId,
    int savedSupplementLineCount,
    Long packageReferenceId,
    int savedPackageLineCount,
    int insertedChangeLogCount) {}
