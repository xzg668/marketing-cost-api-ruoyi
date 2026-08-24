package com.sanhua.marketingcost.service.bomalternative;

/** 标准/替代选择变更结果；选择变化后由用户显式重新核算。 */
public record QuoteBomAlternativeRebuildResult(
    QuoteBomAlternativeSelectionResult selection,
    boolean idempotent,
    boolean recalculationRequired) {
}
