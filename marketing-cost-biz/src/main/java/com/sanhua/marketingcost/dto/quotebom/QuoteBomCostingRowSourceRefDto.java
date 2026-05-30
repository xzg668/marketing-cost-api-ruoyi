package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

public record QuoteBomCostingRowSourceRefDto(
    Long id,
    Long costingRowId,
    String sourcePartType,
    Long sourceRawHierarchyId,
    Long sourceTaskId,
    Long preparationId,
    Long supplementVersionId,
    Long supplementDetailId,
    Long packageReferenceId,
    Long packageReferenceDetailId,
    String referenceFinishedCode,
    String sourceTopProductCode,
    Long sourceSnapshotId,
    Long sourceSnapshotDetailId,
    Long sourceU9BomId,
    String sourcePath,
    LocalDateTime createdAt) {}
