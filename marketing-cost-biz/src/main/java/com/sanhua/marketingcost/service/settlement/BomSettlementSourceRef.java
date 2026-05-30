package com.sanhua.marketingcost.service.settlement;

/** 结算行来源追溯输入；字段对齐 lp_bom_costing_row_source_ref，costing_row_id 留给落库后回填。 */
public record BomSettlementSourceRef(
    String oaNo,
    Long oaFormItemId,
    String quoteProductCode,
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
    String sourcePath) {}
