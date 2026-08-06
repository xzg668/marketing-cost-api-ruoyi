package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

/** 一个替代组的单次默认、切换、恢复或失效历史。 */
public record QuoteBomAlternativeSelectionHistoryResponse(
    String selectionNo,
    String alternativeGroupKey,
    Integer selectionVersion,
    String standardMaterialCode,
    String selectedMaterialCode,
    String selectedChildType,
    String selectionSource,
    String selectionStatus,
    String selectedBy,
    LocalDateTime selectedAt,
    String selectionRemark,
    String candidateSnapshotJson,
    String sourceImportBatchId,
    String sourceBuildBatchId,
    boolean stale) {
}
