package com.sanhua.marketingcost.dto.quotebom;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;

/** 单产品最终树、第2步结算行和确认记录的一致事务结果。 */
public record QuoteEffectiveBomConfirmResponse(
    Long monthlySnapshotId,
    String buildBatchId,
    boolean reusedMonthlyFreeze,
    boolean reusedExistingConfirmation,
    int effectiveNodeCount,
    int costingRowCount,
    QuoteBomConfirmResponse confirmation) {}
