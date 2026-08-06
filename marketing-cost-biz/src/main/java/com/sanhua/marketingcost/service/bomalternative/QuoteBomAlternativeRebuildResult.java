package com.sanhua.marketingcost.service.bomalternative;

/** 标准/替代选择及报价物料明细原子重建结果。 */
public record QuoteBomAlternativeRebuildResult(
    QuoteBomAlternativeSelectionResult selection,
    boolean idempotent,
    boolean rebuilt,
    boolean manualChangesDiscarded,
    int beforeRowCount,
    int afterRowCount,
    String buildBatchId,
    int priceTypeInvalidatedCount,
    int pricePrepareInvalidatedCount,
    int costRunInvalidatedCount) {
}
