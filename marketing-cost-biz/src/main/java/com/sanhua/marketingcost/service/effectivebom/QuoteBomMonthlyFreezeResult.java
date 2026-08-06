package com.sanhua.marketingcost.service.effectivebom;

import java.time.LocalDateTime;

/** 月度冻结或复用后的最终构建指针。 */
public record QuoteBomMonthlyFreezeResult(
    Long monthlySnapshotId,
    String buildBatchId,
    String variantHash,
    boolean reusedFrozenSnapshot,
    boolean reusedEffectiveBuild,
    LocalDateTime frozenAt) {}
