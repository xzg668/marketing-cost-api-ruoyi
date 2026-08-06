package com.sanhua.marketingcost.service.effectivebom;

/** 确认后创建或复用的不可变最终构建。 */
public record QuoteEffectiveBomPersistenceResult(
    String buildBatchId,
    String variantHash,
    boolean reused,
    int nodeCount) {}
