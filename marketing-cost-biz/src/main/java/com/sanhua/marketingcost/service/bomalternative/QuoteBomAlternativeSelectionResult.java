package com.sanhua.marketingcost.service.bomalternative;

/** 当前选择、历史版本或失效结果的统一只读视图。 */
public record QuoteBomAlternativeSelectionResult(
    String selectionNo,
    String alternativeGroupKey,
    String standardMaterialCode,
    String selectedMaterialCode,
    BomChildType selectedChildType,
    String selectionSource,
    Integer selectionVersion,
    String selectionStatus,
    boolean idempotent,
    boolean reviewRequired,
    boolean persisted,
    String sourceImportBatchId,
    String sourceBuildBatchId) {
}
